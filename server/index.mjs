/* ==============================================================
   Claude Code bridge: the phone's connection to Claude Code on this machine.

   Claude Code (through the Claude Agent SDK) runs on the computer's own Claude Code login,
   so no API key is involved.

   - One long-lived `query()` per conversation in streaming-input mode, so the
     Claude Code process stays warm between turns.
   - A spare process is always pre-spawned, so the first message of a new
     conversation does not pay for process start-up.
   - The phone holds a WebSocket open; deltas are forwarded the moment they arrive.

   Protocol (JSON text frames):
     phone  -> { t: "send", conversation?: sessionId, model?, effort?, content: [blocks] }
               { t: "cancel" }
               { t: "warm", conversation }   resume that session now, before the next send
     server -> { t: "ready" }
               { t: "session", conversation }     the id to send back next time
               { t: "thinking", text } / { t: "text", text }
               { t: "step", name, input, label }  a Claude Code tool is running, for the chat
               { t: "done", stop, costUsd, ms } / { t: "error", message }
   ============================================================== */
import http from 'node:http';
import os from 'node:os';
import { query } from '@anthropic-ai/claude-agent-sdk';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8000);
// Listen only on this machine's Tailscale address by default: the bridge has no login of
// its own and Claude Code can do anything here, so it must not be reachable from the LAN.
const HOST = process.env.HOST || tailscaleAddress();
const IDLE_EVICT_MS = Number(process.env.IDLE_EVICT_MS || 30 * 60_000);
const TURN_TIMEOUT_MS = Number(process.env.TURN_TIMEOUT_MS || 30 * 60_000);
const DEFAULT_MODEL = process.env.DEFAULT_MODEL || 'sonnet';
const DEFAULT_EFFORT = process.env.DEFAULT_EFFORT || 'medium';
const WORK_DIR = process.env.WORK_DIR || process.env.HOME || process.cwd();

const MODELS = new Set(['haiku', 'sonnet', 'opus']);
const EFFORTS = new Set(['low', 'medium', 'high']);

/** A chat line for one of Claude Code's tools. */
function stepLabel(name, input = {}) {
  const short = s => { s = String(s ?? ''); return s.length > 60 ? `${s.slice(0, 57)}…` : s; };
  switch (name) {
    case 'Bash': return `$ ${short(input.command)}`;
    case 'Read': return `Reading ${short(input.file_path)}`;
    case 'Write': return `Writing ${short(input.file_path)}`;
    case 'Edit': return `Editing ${short(input.file_path)}`;
    case 'Glob': case 'Grep': return `Searching for ${short(input.pattern)}`;
    case 'WebFetch': return `Reading ${short(input.url)}`;
    case 'WebSearch': return `Searching the web for ${short(input.query)}`;
    default: return name;
  }
}

/** This machine's Tailscale IPv4 address (in 100.64.0.0/10), or null if Tailscale isn't up. */
function tailscaleAddress() {
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' && a.family !== 4) continue;
      const [x, y] = a.address.split('.').map(Number);
      if (x === 100 && y >= 64 && y <= 127) return a.address;
    }
  }
  return null;
}

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

/* ---------------------------------------------------------------
   Conversation: one warm Claude Code process
   --------------------------------------------------------------- */
class Conversation {
  #box = null;

  constructor({ model = DEFAULT_MODEL, effort = DEFAULT_EFFORT, resume = null } = {}) {
    this.sessionId = resume;
    this.model = model;
    this.effort = effort;
    this.q = null;
    this.busy = Promise.resolve();
    this.lastUsed = Date.now();
    this.#spawn();
  }

  #spawn() {
    const box = { queue: [], wake: null, closed: false };
    this.#box = box;
    const input = (async function* () {
      while (!box.closed) {
        if (box.queue.length) { yield box.queue.shift(); continue; }
        await new Promise(resolve => { box.wake = resolve; });
      }
    })();
    this.iter = null;
    this.q = query({
      prompt: input,
      options: {
        model: this.model,
        effort: this.effort,
        thinking: { type: 'adaptive', display: 'summarized' },
        // Plain Claude Code: its own prompt, tools and the user's/project's settings and CLAUDE.md.
        systemPrompt: { type: 'preset', preset: 'claude_code' },
        settingSources: ['user', 'project', 'local'],
        // TODO: permission prompts on the phone. For now nothing asks.
        permissionMode: 'bypassPermissions',
        allowDangerouslySkipPermissions: true,
        includePartialMessages: true,
        cwd: WORK_DIR,
        stderr: () => {},
        ...(this.sessionId ? { resume: this.sessionId } : {}),
      },
    });
  }

  #push(content) {
    this.#box.queue.push({
      type: 'user',
      message: { role: 'user', content },
      parent_tool_use_id: null,
      session_id: this.sessionId ?? '',
    });
    this.#box.wake?.();
    this.#box.wake = null;
  }

  async #apply(model, effort) {
    if (model && model !== this.model) {
      await this.q.setModel(model);
      this.model = model;
    }
    if (effort && effort !== this.effort) {
      await this.q.applyFlagSettings({ effortLevel: effort });
      this.effort = effort;
    }
  }

  /** Runs one turn; turns on the same conversation queue behind each other. */
  ask(content, { model, effort }, sink, signal) {
    const run = async () => {
      if (signal.aborted) return;
      this.lastUsed = Date.now();
      await this.#apply(model, effort);
      await this.#turn(content, sink, signal);
    };
    this.busy = this.busy.catch(() => {}).then(run);
    return this.busy;
  }

  async #turn(content, sink, signal) {
    const started = Date.now();
    let firstToken = 0;
    let emitted = false;
    const interrupt = () => { this.q?.interrupt?.().catch(() => {}); };
    const deadline = setTimeout(interrupt, TURN_TIMEOUT_MS);
    signal.addEventListener('abort', interrupt, { once: true });
    this.#push(content);

    // Read the stream by hand: `for await` would call return() when the turn ends,
    // which closes Claude Code and kills the warm process for the next turn.
    this.iter ??= this.q[Symbol.asyncIterator]();
    try {
      for (;;) {
        const { value: msg, done } = await this.iter.next();
        if (done) break;
        if (msg.session_id && msg.session_id !== this.sessionId) {
          this.sessionId = msg.session_id;
          sink({ t: 'session', conversation: this.sessionId });
        }
        if (msg.type === 'stream_event' && msg.event?.type === 'content_block_delta') {
          const d = msg.event.delta;
          if (d?.type === 'text_delta' && d.text) {
            if (!firstToken) firstToken = Date.now() - started;
            emitted = true;
            sink({ t: 'text', text: d.text });
          } else if (d?.type === 'thinking_delta' && d.thinking) {
            if (!firstToken) firstToken = Date.now() - started;
            sink({ t: 'thinking', text: d.thinking });
          }
          continue;
        }
        if (msg.type === 'assistant') {
          for (const block of msg.message?.content ?? []) {
            if (block.type === 'tool_use') {
              sink({ t: 'step', name: block.name, input: block.input, label: stepLabel(block.name, block.input) });
            }
          }
          continue;
        }
        if (msg.type === 'result') {
          if (msg.subtype !== 'success' && !emitted) {
            sink({ t: 'error', message: msg.result || `Turn ended: ${msg.subtype}` });
          } else {
            sink({ t: 'done', stop: msg.subtype, costUsd: msg.total_cost_usd, ms: Date.now() - started });
          }
          log(`turn ${this.sessionId?.slice(0, 8)} model=${this.model} first=${firstToken}ms total=${Date.now() - started}ms`);
          return;
        }
      }
      throw new Error('Claude Code exited before answering');
    } finally {
      clearTimeout(deadline);
      signal.removeEventListener('abort', interrupt);
    }
  }

  close() {
    if (this.#box) {
      this.#box.closed = true;
      this.#box.wake?.();
      this.#box = null;
    }
    try { this.q?.close?.(); } catch { /* already gone */ }
    this.q = null;
  }
}

/* ---------------------------------------------------------------
   Registry, with one spare warm process for the next new chat
   --------------------------------------------------------------- */
const conversations = new Map();   // sessionId -> Conversation
let spare = new Conversation();

function take(conversationId, model, effort) {
  if (conversationId) {
    const live = conversations.get(conversationId);
    if (live) return live;
    // Evicted or the server restarted: resume the saved Claude Code session.
    const resumed = new Conversation({ model, effort, resume: conversationId });
    conversations.set(conversationId, resumed);
    return resumed;
  }
  const fresh = spare;
  spare = new Conversation();
  return fresh;
}

setInterval(() => {
  const cutoff = Date.now() - IDLE_EVICT_MS;
  for (const [id, c] of conversations) {
    if (c.lastUsed < cutoff) { c.close(); conversations.delete(id); }
  }
}, 60_000).unref();

/* ---------------------------------------------------------------
   HTTP (health) + WebSocket
   --------------------------------------------------------------- */
if (!HOST) {
  console.error('No Tailscale address found on this machine. Start Tailscale and sign in, or set HOST to the address to listen on.');
  process.exit(1);
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ service: 'claude-remote', status: 'ok', live: conversations.size, uptime_s: Math.round(process.uptime()) }));
});

const wss = new WebSocketServer({ server, path: '/ws', perMessageDeflate: false });

wss.on('connection', (ws, req) => {
  log('connect', req.socket.remoteAddress);
  ws._socket.setNoDelay(true);
  let current = null;   // AbortController of the running turn
  const sink = frame => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(frame)); };
  sink({ t: 'ready' });

  ws.on('message', async raw => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return sink({ t: 'error', message: 'bad frame' }); }

    if (msg.t === 'cancel') { current?.abort(); return; }
    if (msg.t === 'warm') {
      if (typeof msg.conversation === 'string' && msg.conversation) take(msg.conversation);
      return;
    }
    if (msg.t !== 'send') return;

    const model = MODELS.has(msg.model) ? msg.model : undefined;
    const effort = EFFORTS.has(msg.effort) ? msg.effort : undefined;
    const content = Array.isArray(msg.content) && msg.content.length ? msg.content : null;
    if (!content) return sink({ t: 'error', message: 'empty message' });

    const conversation = take(msg.conversation, model, effort);
    current = new AbortController();
    try {
      await conversation.ask(content, { model, effort }, frame => {
        if (frame.t === 'session') conversations.set(frame.conversation, conversation);
        sink(frame);
      }, current.signal);
    } catch (err) {
      log('turn failed:', err.message);
      sink({ t: 'error', message: err.message });
      conversation.close();
      for (const [id, c] of conversations) if (c === conversation) conversations.delete(id);
    }
  });

  ws.on('close', () => current?.abort());
});

server.listen(PORT, HOST, () => log(`claude-remote server on ${HOST}:${PORT}, spare process warming`));

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    spare.close();
    for (const c of conversations.values()) c.close();
    process.exit(0);
  });
}
