/* ==============================================================
   Claude Remote bridge: lets a phone drive Claude Code on this computer.

   Claude Code runs through the Claude Agent SDK on this computer's own Claude
   Code login. Each session is one long-lived Claude Code process in streaming
   input mode; it keeps running (background tasks, subagents and all) whether or
   not a phone is connected. Phones attach to a session to get a snapshot of the
   chat so far and then its live events.

   Requests (phone -> bridge) carry an `id`; the reply has the same t and id:
     { t:"info" }                         -> { name, platform, home, sep }
     { t:"roots" }                        -> { roots:[{name, path}] }        drives / filesystem roots
     { t:"ls", path }                     -> { path, parent, dirs:[{name, path}] }
     { t:"sessions" }                     -> { open:[Session], closed:[Session] }
     { t:"open", cwd } | { t:"open", resume, cwd? }
                                          -> { key }                          an open session's handle
     { t:"attach", key }                  -> { session:Session, items:[Item] } then live { t:"ev", key, e }
     { t:"detach", key }
     { t:"send", key, text, images?:[{mediaType, data}], model?, effort? }
                                          images: base64 JPEG/PNG/GIF/WebP attached from the phone
     { t:"interrupt", key }
     { t:"close", key }                   ends the Claude Code process; the session becomes closed
     { t:"set_model", key, model?, effort? }   -> { session }   applied live
     { t:"models" }                       -> { models:[{value, displayName, description, efforts}] }
     { t:"usage" }                        -> { subscription, available, limits:[{name, utilization, resetsAt}] }
     { t:"account" }                      -> { loggedIn, email, org, plan, method }
     { t:"login_start", console? }        -> { url }                 sign-in link to open on the phone
     { t:"login_code", code }             -> { ok, account }         the code the sign-in page shows
     { t:"login_cancel" } / { t:"logout" }
   Errors: { t:"error", id, message }. Pushed: { t:"ev", key, e }, { t:"closed", key }.

   Session: { key?, sessionId, cwd, title, state:"running"|"idle", lastActive, model, effort,
              costUsd, contextTokens, background, origin:"bridge"|"terminal" }
            origin "terminal": a Claude Code session running in a terminal on this computer
            (found by its transcript being written in the last ACTIVE_MS). It is mirrored live
            from its transcript; sending a message continues it in the bridge seamlessly.
   Item:    { role:"user"|"assistant"|"note", text, images?:[{mediaType, data}], thinking?,
              steps?:[{id, name, label, detail, detailLang, sub, done, error, output}] }
            detail: the call in full (command, diff, file content, JSON input); detailLang: "bash"|"diff"|"json"|""
            output: the tool result as text (trimmed to OUTPUT_LIMIT), like `claude --verbose` shows
            images: images in the tool result (e.g. Read on a PNG), [{mediaType, data}]
   Event e: { k:"user"|"text"|"thinking"|"sep"|"step"|"stepDone"|"note"|"state"|"meta", ... }
            applied to the items with the same rules on both sides (applyEvent).
   ============================================================== */
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { getSessionMessages, listSessions, query } from '@anthropic-ai/claude-agent-sdk';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8000);
// Listen only on this machine's Tailscale address by default: the bridge has no login of
// its own and Claude Code can do anything here, so it must not be reachable from the LAN.
const HOST = process.env.HOST || tailscaleAddress();
const NAME = process.env.BRIDGE_NAME || os.hostname();
const DEFAULT_MODEL = process.env.DEFAULT_MODEL || 'sonnet';
const DEFAULT_EFFORT = process.env.DEFAULT_EFFORT || 'medium';
// An idle session's process is stopped after this long; the session stays open and
// resumes on the next message. Open sessions untouched for SESSION_TTL_MS are closed.
const IDLE_PROCESS_MS = Number(process.env.IDLE_PROCESS_MS || 30 * 60_000);
const SESSION_TTL_MS = Number(process.env.SESSION_TTL_MS || 24 * 3600_000);
const CLOSED_LIMIT = Number(process.env.CLOSED_LIMIT || 50);
// A saved session whose transcript changed this recently is treated as running in a terminal.
const ACTIVE_MS = Number(process.env.ACTIVE_MS || 2 * 60_000);
const CLAUDE_DIR = process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), '.claude');
// Images: the API accepts these types; each one is capped so chats stay light to sync.
const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/gif', 'image/webp']);
const IMAGE_LIMIT = Number(process.env.IMAGE_LIMIT || 5 * 1024 * 1024);   // base64 chars per image
const MAX_IMAGES = 10;
// Tool output kept per step (characters); longer output keeps its start and end.
const OUTPUT_LIMIT = Number(process.env.OUTPUT_LIMIT || 20_000);

const EFFORTS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);
const validModel = m => (typeof m === 'string' && /^[\w.:\[\]-]{1,80}$/.test(m) ? m : undefined);
const validEffort = e => (EFFORTS.has(e) ? e : undefined);

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
   Chat items and events (mirrored in the app)
   --------------------------------------------------------------- */
function applyEvent(items, e) {
  const last = items[items.length - 1];
  const assistant = () => {
    if (last?.role === 'assistant') return last;
    const item = { role: 'assistant', text: '', thinking: '', steps: [] };
    items.push(item);
    return item;
  };
  switch (e.k) {
    case 'user': items.push({ role: 'user', text: e.text, ...(e.images?.length ? { images: e.images } : {}) }); break;
    case 'text': assistant().text += e.text; break;
    case 'thinking': assistant().thinking += e.text; break;
    case 'sep': if (last?.role === 'assistant' && last.text && !last.text.endsWith('\n\n')) last.text += '\n\n'; break;
    case 'step':
      assistant().steps.push({
        id: e.id, name: e.name ?? '', label: e.label, detail: e.detail ?? '', detailLang: e.detailLang ?? '',
        sub: !!e.sub, done: false, error: false, output: '',
      });
      break;
    case 'stepDone':
      for (let i = items.length - 1; i >= 0; i--) {
        const s = items[i].steps?.find(st => st.id === e.id);
        if (s) {
          s.done = true; s.error = !!e.error; s.output = e.output ?? '';
          if (e.images?.length) s.images = e.images;
          break;
        }
      }
      break;
    case 'note': items.push({ role: 'note', text: e.text }); break;
    default: break;   // state, meta: session fields, not items
  }
}

/** A chat line for one Claude Code tool call. */
function stepLabel(name, input = {}) {
  const short = s => { s = String(s ?? '').replace(/\s+/g, ' '); return s.length > 80 ? `${s.slice(0, 77)}…` : s; };
  switch (name) {
    case 'Bash': return `${input.run_in_background ? 'Background: ' : ''}$ ${short(input.command)}`;
    case 'PowerShell': return `${input.run_in_background ? 'Background: ' : ''}PS> ${short(input.command)}`;
    case 'Read': return `Read ${short(input.file_path)}`;
    case 'Write': return `Write ${short(input.file_path)}`;
    case 'Edit': case 'MultiEdit': return `Edit ${short(input.file_path)}`;
    case 'NotebookEdit': return `Edit ${short(input.notebook_path)}`;
    case 'Glob': return `Find ${short(input.pattern)}`;
    case 'Grep': return `Search ${short(input.pattern)}`;
    case 'WebFetch': return `Fetch ${short(input.url)}`;
    case 'WebSearch': return `Web search: ${short(input.query)}`;
    case 'Task': case 'Agent': return `Agent${input.subagent_type ? ` (${input.subagent_type})` : ''}: ${short(input.description || input.prompt)}`;
    case 'TodoWrite': return `Todos: ${(input.todos ?? []).map(t => `${t.status === 'completed' ? '✓' : t.status === 'in_progress' ? '▸' : '·'} ${t.content}`).join('  ')}`;
    default: return name.startsWith('mcp__') ? name.split('__').slice(1).join(': ') : name;
  }
}

/** The full tool call, for the expandable step: [text, language]. */
function stepDetail(name, input = {}) {
  switch (name) {
    case 'Bash': return [String(input.command ?? ''), 'bash'];
    case 'PowerShell': return [String(input.command ?? ''), 'powershell'];
    case 'Edit': return [diffText(input.old_string, input.new_string), 'diff'];
    case 'MultiEdit': return [(input.edits ?? []).map(e => diffText(e.old_string, e.new_string)).join('\n@@\n'), 'diff'];
    case 'Write': return [trimText(String(input.content ?? '')), langOf(input.file_path)];
    case 'Read': case 'Glob': case 'Grep': case 'WebFetch': case 'WebSearch': case 'TodoWrite': return ['', ''];
    case 'Task': case 'Agent': return [String(input.prompt ?? ''), ''];
    default: return [trimText(JSON.stringify(input, null, 2)), 'json'];
  }
}

function diffText(oldS = '', newS = '') {
  const minus = String(oldS).split('\n').map(l => `-${l}`);
  const plus = String(newS).split('\n').map(l => `+${l}`);
  return trimText([...minus, ...plus].join('\n'));
}

function langOf(file = '') {
  const ext = path.extname(String(file)).slice(1).toLowerCase();
  return ext || '';
}

function trimText(t) {
  if (t.length <= OUTPUT_LIMIT) return t;
  const half = Math.floor(OUTPUT_LIMIT / 2);
  return `${t.slice(0, half)}\n… ${t.length - OUTPUT_LIMIT} characters not shown …\n${t.slice(-half)}`;
}

/** Image blocks (API shape) -> [{mediaType, data}] for the phone, within the size caps. */
function imagesOf(blocks) {
  if (!Array.isArray(blocks)) return [];
  return blocks
    .filter(b => b?.type === 'image' && b.source?.type === 'base64' && IMAGE_TYPES.has(b.source.media_type) && (b.source.data?.length ?? 0) <= IMAGE_LIMIT)
    .slice(0, MAX_IMAGES)
    .map(b => ({ mediaType: b.source.media_type, data: b.source.data }));
}

/** A tool result's content as plain text. */
function resultText(content) {
  if (typeof content === 'string') return trimText(content);
  if (!Array.isArray(content)) return '';
  return trimText(content.map(b => (b.type === 'text' ? b.text : b.type === 'image' ? '[image]' : '')).filter(Boolean).join('\n'));
}

function stepEvent(b, sub) {
  const [detail, detailLang] = stepDetail(b.name, b.input);
  return { k: 'step', id: b.id, name: b.name, label: stepLabel(b.name, b.input), detail, detailLang, sub };
}

/** One transcript entry (saved message) -> chat events. */
function entryEvents(m) {
  const out = [];
  if (m.isSidechain) return out;
  const msg = m.message ?? {};
  const sub = m.parent_tool_use_id != null;
  const content = typeof msg.content === 'string' ? [{ type: 'text', text: msg.content }] : (msg.content ?? []);
  if (m.type === 'user') {
    for (const b of content) if (b.type === 'tool_result') out.push({ k: 'stepDone', id: b.tool_use_id, error: !!b.is_error, output: resultText(b.content), images: imagesOf(b.content) });
    if (sub || m.isMeta) return out;
    const text = content.filter(b => b.type === 'text').map(b => b.text).join('\n').trim();
    const images = imagesOf(content);
    // Skip Claude Code's own injected notes (command output, reminders).
    if ((text && !text.startsWith('<')) || images.length) out.push({ k: 'user', text: text.startsWith('<') ? '' : text, images });
  } else if (m.type === 'assistant') {
    if (!sub) out.push({ k: 'sep' });
    for (const b of content) {
      if (b.type === 'tool_use') out.push(stepEvent(b, sub));
      else if (sub) continue;
      else if (b.type === 'text' && b.text) out.push({ k: 'text', text: b.text });
      else if (b.type === 'thinking' && b.thinking) out.push({ k: 'thinking', text: b.thinking });
    }
  }
  return out;
}

/** Saved transcript -> chat items (what a terminal session or an earlier run left on disk). */
function transcriptItems(messages) {
  const items = [];
  for (const m of messages) for (const e of entryEvents(m)) applyEvent(items, e);
  return items;
}

/** Where Claude Code keeps a session's transcript, or null. */
function transcriptFile(sessionId) {
  const root = path.join(CLAUDE_DIR, 'projects');
  let dirs = [];
  try { dirs = fs.readdirSync(root, { withFileTypes: true }).filter(d => d.isDirectory()); } catch { return null; }
  for (const d of dirs) {
    const f = path.join(root, d.name, `${sessionId}.jsonl`);
    if (fs.existsSync(f)) return f;
  }
  return null;
}

/* ---------------------------------------------------------------
   An open session: one Claude Code process (started on demand)
   --------------------------------------------------------------- */
class Session {
  constructor({ cwd, sessionId = null, title = '', items = [], key = null, origin = 'bridge' }) {
    this.key = key ?? randomUUID();
    this.origin = origin;
    this.tail = null;            // terminal mirror: { file, offset, rest, timer }
    this.cwd = cwd;
    this.sessionId = sessionId;
    this.title = title;
    this.items = items;
    this.state = 'idle';
    this.model = DEFAULT_MODEL;
    this.effort = DEFAULT_EFFORT;
    this.lastActive = Date.now();
    this.costUsd = 0;
    this.contextTokens = 0;
    this.background = 0;         // live background tasks
    this.watchers = new Set();   // sockets attached to this session
    this.q = null;
    this.box = null;
  }

  info() {
    return {
      key: this.key, sessionId: this.sessionId, cwd: this.cwd, title: this.title || path.basename(this.cwd) || this.cwd,
      state: this.state, lastActive: this.lastActive, model: this.model, effort: this.effort,
      costUsd: this.costUsd, contextTokens: this.contextTokens, background: this.background,
      origin: this.origin,
    };
  }

  emit(e) {
    applyEvent(this.items, e);
    const frame = JSON.stringify({ t: 'ev', key: this.key, e });
    for (const ws of this.watchers) if (ws.readyState === ws.OPEN) ws.send(frame);
  }

  /**
   * Mirrors a session that is running in a terminal: follows its transcript file and turns
   * each new entry into chat events, so the phone watches it live.
   */
  startTail(file) {
    let offset = 0;
    try { offset = fs.statSync(file).size; } catch { /* gone */ }
    this.tail = { file, offset, rest: '' };
    const poll = () => {
      if (!this.tail) return;
      let st;
      try { st = fs.statSync(file); } catch { return; }
      const age = Date.now() - st.mtimeMs;
      this.lastActive = Math.min(Date.now(), st.mtimeMs);
      if (st.size > this.tail.offset) {
        const fd = fs.openSync(file, 'r');
        try {
          const buf = Buffer.alloc(Math.min(st.size - this.tail.offset, 8 * 1024 * 1024));
          fs.readSync(fd, buf, 0, buf.length, this.tail.offset);
          this.tail.offset += buf.length;
          const lines = (this.tail.rest + buf.toString('utf8')).split('\n');
          this.tail.rest = lines.pop();
          for (const line of lines) {
            if (!line.trim()) continue;
            let entry;
            try { entry = JSON.parse(line); } catch { continue; }
            for (const e of entryEvents(entry)) this.emit(e);
            if (entry.type === 'summary' && entry.summary && !this.title) this.title = entry.summary;
          }
        } finally { fs.closeSync(fd); }
      }
      this.setState(age < 8_000 ? 'running' : 'idle');
    };
    this.tail.timer = setInterval(poll, 1000);
    this.tail.timer.unref?.();
  }

  stopTail() {
    if (this.tail) { clearInterval(this.tail.timer); this.tail = null; }
  }

  /** Pushes the session's changing fields (model, cost, context, background tasks) to attached phones. */
  meta() {
    this.emit({ k: 'meta', session: this.info() });
  }

  setState(state) {
    if (state === this.state) return;
    this.state = state;
    this.emit({ k: 'state', state });
  }

  #spawn() {
    const box = { queue: [], wake: null, closed: false };
    this.box = box;
    const input = (async function* () {
      while (!box.closed) {
        if (box.queue.length) { yield box.queue.shift(); continue; }
        await new Promise(resolve => { box.wake = resolve; });
      }
    })();
    const q = query({
      prompt: input,
      options: {
        model: this.model,
        effort: this.effort,
        thinking: { type: 'adaptive', display: 'summarized' },
        // Plain Claude Code: its own prompt and tools, plus this computer's and the folder's
        // settings and CLAUDE.md, exactly like running `claude` in that folder.
        systemPrompt: { type: 'preset', preset: 'claude_code' },
        settingSources: ['user', 'project', 'local'],
        // TODO: approve permissions from the phone. For now nothing asks.
        permissionMode: 'bypassPermissions',
        allowDangerouslySkipPermissions: true,
        // Nobody can answer its multiple-choice prompt yet; Claude asks in plain text instead.
        disallowedTools: ['AskUserQuestion'],
        includePartialMessages: true,
        // Stream subagents' own steps too, so the phone shows them nested under their Agent step.
        forwardSubagentText: true,
        cwd: this.cwd,
        stderr: () => {},
        ...(this.sessionId ? { resume: this.sessionId } : {}),
      },
    });
    this.q = q;
    this.#pump(q).catch(err => {
      log(`session ${this.key.slice(0, 8)} failed:`, err.message);
      this.emit({ k: 'note', text: `Claude Code stopped: ${err.message}` });
    }).finally(() => {
      if (this.q === q) { this.q = null; this.box = null; this.background = 0; }
      this.setState('idle');
    });
  }

  /** Reads everything Claude Code emits for as long as the process lives: turns, background tasks, subagents. */
  async #pump(q) {
    const it = q[Symbol.asyncIterator]();
    for (;;) {
      const { value: msg, done } = await it.next();
      if (done) return;
      this.lastActive = Date.now();
      const sub = msg.parent_tool_use_id != null;
      if (msg.session_id && msg.session_id !== this.sessionId) {
        this.sessionId = msg.session_id;
        this.meta();
      }
      switch (msg.type) {
        case 'stream_event': {
          if (sub) break;
          const ev = msg.event;
          if (ev?.type === 'message_start') this.emit({ k: 'sep' });
          else if (ev?.type === 'content_block_delta') {
            if (ev.delta?.type === 'text_delta' && ev.delta.text) this.emit({ k: 'text', text: ev.delta.text });
            else if (ev.delta?.type === 'thinking_delta' && ev.delta.thinking) this.emit({ k: 'thinking', text: ev.delta.thinking });
          }
          break;
        }
        case 'assistant':
          for (const b of msg.message?.content ?? []) {
            if (b.type === 'tool_use') this.emit(stepEvent(b, sub));
          }
          break;
        case 'user':
          for (const b of Array.isArray(msg.message?.content) ? msg.message.content : []) {
            if (b.type === 'tool_result') this.emit({ k: 'stepDone', id: b.tool_use_id, error: !!b.is_error, output: resultText(b.content), images: imagesOf(b.content) });
          }
          break;
        case 'result': {
          if (msg.subtype !== 'success') {
            const why = this.interrupted ? 'Stopped'
              : msg.subtype === 'error_max_turns' ? 'Stopped: reached the turn limit'
              : msg.subtype === 'error_max_budget_usd' ? 'Stopped: reached the budget limit'
              : msg.result || 'The turn ended with an error';
            this.emit({ k: 'note', text: why });
          }
          this.interrupted = false;
          if (typeof msg.total_cost_usd === 'number') this.costUsd = msg.total_cost_usd;
          const u = msg.usage ?? {};
          const ctx = (u.input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0);
          if (ctx) this.contextTokens = ctx;
          // Totals first, then idle: a phone treating idle as "turn over" already has them.
          this.meta();
          this.setState('idle');
          break;
        }
        case 'rate_limit_event': {
          const r = msg.rate_limit_info ?? {};
          if (r.status === 'rejected' || r.status === 'allowed_warning') {
            const when = r.resetsAt ? ` (resets ${new Date(r.resetsAt * 1000).toLocaleString()})` : '';
            const kind = r.rateLimitType ? `: ${r.rateLimitType.replace(/_/g, ' ')}` : '';
            this.emit({ k: 'note', text: `${r.status === 'rejected' ? 'Usage limit reached' : 'Close to your usage limit'}${kind}${when}` });
          }
          break;
        }
        case 'auth_status':
          if (msg.error) this.emit({ k: 'note', text: `Sign-in problem: ${msg.error}. Sign in again from the device screen.` });
          break;
        case 'system':
          if (msg.subtype === 'session_state_changed') this.setState(msg.state === 'idle' ? 'idle' : 'running');
          else if (msg.subtype === 'background_tasks_changed') { this.background = (msg.tasks ?? []).length; this.meta(); }
          else if (msg.subtype === 'compact_boundary') this.emit({ k: 'note', text: 'Conversation compacted to free up context' });
          else if (/^task_/.test(msg.subtype ?? '')) {
            const text = msg.summary || msg.description || msg.status;
            if (text) this.emit({ k: 'note', text: `Background task: ${text}` });
          }
          break;
        default: break;
      }
    }
  }

  async send(text, model, effort, images = []) {
    this.lastActive = Date.now();
    // Continuing a terminal session from the phone: stop mirroring and resume it here.
    if (this.origin === 'terminal') {
      this.stopTail();
      this.origin = 'bridge';
      this.emit({ k: 'note', text: 'Continued from the phone. The terminal on the computer won\'t see these new messages.' });
      this.meta();
    }
    if (!this.title) this.title = (text || 'Image').slice(0, 80);
    if (this.q) {
      if (model && model !== this.model) { await this.q.setModel(model).catch(() => {}); this.model = model; }
      if (effort && effort !== this.effort) { await this.q.applyFlagSettings({ effortLevel: effort }).catch(() => {}); this.effort = effort; }
    } else {
      if (model) this.model = model;
      if (effort) this.effort = effort;
      this.#spawn();
    }
    this.emit({ k: 'user', text, images });
    this.setState('running');
    const content = [
      ...images.map(i => ({ type: 'image', source: { type: 'base64', media_type: i.mediaType, data: i.data } })),
      ...(text ? [{ type: 'text', text }] : []),
    ];
    this.box.queue.push({
      type: 'user',
      message: { role: 'user', content },
      parent_tool_use_id: null,
      session_id: this.sessionId ?? '',
    });
    this.box.wake?.();
    this.box.wake = null;
  }

  async setModel(model, effort) {
    if (model) { if (this.q) await this.q.setModel(model).catch(() => {}); this.model = model; }
    if (effort) { if (this.q) await this.q.applyFlagSettings({ effortLevel: effort }).catch(() => {}); this.effort = effort; }
    this.meta();
  }

  interrupt() {
    if (this.q && this.state === 'running') this.interrupted = true;
    this.q?.interrupt?.().catch(() => {});
  }

  /** Stops the Claude Code process; the chat stays and resumes on the next message. */
  stopProcess() {
    this.stopTail();
    if (this.box) { this.box.closed = true; this.box.wake?.(); }
    try { this.q?.close?.(); } catch { /* already gone */ }
    this.q = null;
    this.box = null;
  }
}

/* ---------------------------------------------------------------
   Registry
   --------------------------------------------------------------- */
const open = new Map();   // key -> Session
// sessionId -> when the bridge stopped running it. Its transcript was last written by us then,
// so only writes after that mean someone continued it in a terminal.
const releasedAt = new Map();
const touchedSince = (sessionId, mtime) => mtime > (releasedAt.get(sessionId) ?? 0) + 2_000;

async function openSession({ cwd, resume, key }) {
  if (resume) {
    for (const s of open.values()) if (s.sessionId === resume) return s;
    const info = (await listSessions({ limit: 1000 }).catch(() => [])).find(s => s.sessionId === resume);
    const dir = cwd || info?.cwd;
    if (!dir) throw new Error('Unknown session');
    const messages = await getSessionMessages(resume, { dir }).catch(() => []);
    const file = transcriptFile(resume);
    const mtime = file ? fs.statSync(file).mtimeMs : 0;
    const inTerminal = file && Date.now() - mtime < ACTIVE_MS && touchedSince(resume, mtime);
    const s = new Session({
      cwd: dir, sessionId: resume, title: info?.customTitle || info?.summary || '', items: transcriptItems(messages),
      // Keep the key the phone asked for, so its live events reach it even if the terminal went quiet.
      ...(inTerminal ? { key: `term:${resume}`, origin: 'terminal' } : key ? { key } : {}),
    });
    if (inTerminal) { s.lastActive = Math.min(Date.now(), mtime); s.startTail(file); }
    open.set(s.key, s);
    return s;
  }
  if (!cwd) throw new Error('No folder given');
  const dir = path.resolve(String(cwd));
  if (!fs.statSync(dir, { throwIfNoEntry: false })?.isDirectory()) throw new Error(`Not a folder: ${cwd}`);
  const s = new Session({ cwd: dir });
  open.set(s.key, s);
  return s;
}

function closeSession(s) {
  if (s.sessionId && s.origin === 'bridge') releasedAt.set(s.sessionId, Date.now());
  s.stopProcess();
  open.delete(s.key);
  for (const ws of s.watchers) if (ws.readyState === ws.OPEN) ws.send(JSON.stringify({ t: 'closed', key: s.key }));
}

setInterval(() => {
  const now = Date.now();
  for (const s of open.values()) {
    if (s.origin === 'terminal' && !s.watchers.size && now - s.lastActive > ACTIVE_MS) { closeSession(s); continue; }
    if (s.state === 'running') continue;
    if (now - s.lastActive > SESSION_TTL_MS) closeSession(s);
    else if (s.q && now - s.lastActive > IDLE_PROCESS_MS) s.stopProcess();
  }
}, 60_000).unref();

/* ---------------------------------------------------------------
   Folders
   --------------------------------------------------------------- */
function roots() {
  const home = [{ name: 'Home', path: os.homedir() }];
  // WORK_DIR, if set, is offered first as a shortcut to where you usually work.
  if (process.env.WORK_DIR && fs.existsSync(process.env.WORK_DIR)) home.unshift({ name: 'Default folder', path: path.resolve(process.env.WORK_DIR) });
  if (process.platform !== 'win32') return [...home, { name: '/', path: '/' }];
  const drives = [];
  for (let c = 65; c <= 90; c++) {
    const p = `${String.fromCharCode(c)}:\\`;
    try { fs.accessSync(p); drives.push({ name: p, path: p }); } catch { /* no such drive */ }
  }
  return [...home, ...drives];
}

// Operating-system folders nobody runs Claude Code in.
const SYSTEM_DIRS = process.platform === 'win32'
  ? /^(\$.*|System Volume Information|Config\.Msi|Recovery|PerfLogs|Documents and Settings)$/i
  : /^(lost\+found)$/;

function listDirs(dir) {
  const full = path.resolve(dir);
  const dirs = [];
  for (const d of fs.readdirSync(full, { withFileTypes: true })) {
    let isDir = d.isDirectory();
    if (!isDir && d.isSymbolicLink()) {
      try { isDir = fs.statSync(path.join(full, d.name)).isDirectory(); } catch { /* broken link */ }
    }
    if (isDir && !SYSTEM_DIRS.test(d.name)) dirs.push({ name: d.name, path: path.join(full, d.name) });
  }
  // Ordinary folders first, then hidden ones; each alphabetical.
  dirs.sort((a, b) => (a.name.startsWith('.') - b.name.startsWith('.')) || a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
  const parent = path.dirname(full);
  return { path: full, parent: parent === full ? null : parent, dirs };
}

/* ---------------------------------------------------------------
   Account, models and plan usage
   --------------------------------------------------------------- */
/** The Claude Code executable the SDK runs, so login applies to exactly what the sessions use. */
function claudeBinary() {
  const require = createRequire(import.meta.url);
  try {
    const dir = path.dirname(require.resolve(`@anthropic-ai/claude-agent-sdk-${process.platform}-${process.arch}/package.json`));
    const exe = path.join(dir, process.platform === 'win32' ? 'claude.exe' : 'claude');
    if (fs.existsSync(exe)) return exe;
  } catch { /* fall back to PATH */ }
  return 'claude';
}

function runClaude(args, timeoutMs = 30_000) {
  return new Promise((resolve, reject) => {
    const p = spawn(claudeBinary(), args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    let out = '';
    p.stdout.on('data', d => { out += d; });
    p.stderr.on('data', d => { out += d; });
    const timer = setTimeout(() => { p.kill(); reject(new Error('Claude Code did not answer in time')); }, timeoutMs);
    p.on('error', err => { clearTimeout(timer); reject(err); });
    p.on('close', code => { clearTimeout(timer); resolve({ code, out }); });
  });
}

async function account() {
  const { out } = await runClaude(['auth', 'status', '--json']);
  let s;
  try { s = JSON.parse(out); } catch { return { loggedIn: false }; }
  return { loggedIn: !!s.loggedIn, email: s.email ?? null, org: s.orgName ?? null, plan: s.subscriptionType ?? null, method: s.authMethod ?? null };
}

/** Runs fn against a Claude Code process: a live session's if there is one, else a short-lived one. */
async function withProbe(fn) {
  const live = [...open.values()].find(s => s.q);
  if (live) return fn(live.q);
  const box = { closed: false, wake: null };
  const input = (async function* () { while (!box.closed) await new Promise(r => { box.wake = r; }); })();
  const q = query({ prompt: input, options: { cwd: os.homedir(), settingSources: ['user'], stderr: () => {} } });
  try {
    return await fn(q);
  } finally {
    box.closed = true;
    box.wake?.();
    try { q.close(); } catch { /* gone */ }
  }
}

let modelCache = null;   // { at, models }
async function models() {
  if (modelCache && Date.now() - modelCache.at < 10 * 60_000) return modelCache.models;
  const list = await withProbe(q => q.supportedModels());
  const result = list.map(m => ({
    value: m.value, displayName: m.displayName, description: m.description ?? '',
    efforts: m.supportedEffortLevels ?? (m.supportsEffort ? ['low', 'medium', 'high'] : []),
  }));
  modelCache = { at: Date.now(), models: result };
  return result;
}

async function usage() {
  const u = await withProbe(q => q.usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET({ skipBehaviors: true }));
  const names = {
    five_hour: 'Current session (5 h)', seven_day: 'This week', seven_day_opus: 'This week (Opus)',
    seven_day_sonnet: 'This week (Sonnet)', seven_day_oauth_apps: 'This week (apps)',
  };
  const limits = Object.entries(u?.rate_limits ?? {})
    .filter(([, v]) => v && v.utilization != null)
    .map(([k, v]) => ({ name: names[k] ?? k.replace(/_/g, ' '), utilization: v.utilization, resetsAt: v.resets_at }));
  return { subscription: u?.subscription_type ?? null, available: !!u?.rate_limits_available, limits };
}

/* Login: `claude auth login` prints a link, then waits for the code the sign-in page shows. */
let login = null;   // { proc, out, done }

function loginStart(consoleLogin) {
  loginCancel();
  const proc = spawn(claudeBinary(), ['auth', 'login', consoleLogin ? '--console' : '--claudeai'], {
    stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true, env: { ...process.env, BROWSER: 'none' },
  });
  const state = { proc, out: '' };
  state.done = new Promise(resolve => proc.on('close', code => resolve(code)));
  login = state;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { loginCancel(); reject(new Error('Claude Code did not show a sign-in link')); }, 20_000);
    const onData = d => {
      state.out += d;
      const m = state.out.match(/https:\/\/\S+/);
      if (m) { clearTimeout(timer); resolve({ url: m[0] }); }
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    proc.on('error', err => { clearTimeout(timer); reject(err); });
    state.done.then(code => { clearTimeout(timer); reject(new Error(`Sign-in ended early (${code}): ${state.out.trim().slice(-200)}`)); });
  });
}

async function loginCode(code) {
  if (!login) throw new Error('No sign-in in progress. Start again.');
  const state = login;
  const before = state.out.length;
  state.proc.stdin.write(`${String(code ?? '').trim()}\n`);
  const exit = await Promise.race([state.done, new Promise(r => setTimeout(() => r('timeout'), 60_000))]);
  // eslint-disable-next-line no-control-regex
  const said = state.out.slice(before).replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').trim();
  if (exit === 'timeout') { loginCancel(); throw new Error('Sign-in timed out. Start again.'); }
  login = null;
  modelCache = null;
  const acct = await account().catch(() => ({ loggedIn: false }));
  if (exit !== 0 || !acct.loggedIn) throw new Error(said.split('\n').pop() || 'Sign-in failed. Check the code and start again.');
  // Running sessions keep the old login until their process restarts.
  for (const s of open.values()) if (s.state !== 'running') s.stopProcess();
  return { ok: true, account: acct };
}

function loginCancel() {
  if (login) {
    try { login.proc.kill(); } catch { /* gone */ }
    login = null;
  }
}

/* ---------------------------------------------------------------
   Requests
   --------------------------------------------------------------- */
async function handle(ws, msg) {
  switch (msg.t) {
    case 'info':
      return { name: NAME, platform: process.platform, home: os.homedir(), sep: path.sep };
    case 'roots':
      return { roots: roots() };
    case 'ls':
      return listDirs(String(msg.path || os.homedir()));
    case 'sessions': {
      const live = [...open.values()].map(s => s.info()).sort((a, b) => b.lastActive - a.lastActive);
      const liveIds = new Set(live.map(s => s.sessionId).filter(Boolean));
      const saved = await listSessions({ limit: CLOSED_LIMIT + liveIds.size }).catch(() => []);
      // Sessions running in a terminal right now count as open, mirrored live on attach.
      const now = Date.now();
      for (const s of saved) {
        if (liveIds.has(s.sessionId) || now - s.lastModified > ACTIVE_MS || !touchedSince(s.sessionId, s.lastModified)) continue;
        live.push({
          key: `term:${s.sessionId}`, sessionId: s.sessionId, cwd: s.cwd ?? '', title: s.customTitle || s.summary || s.firstPrompt || '',
          state: now - s.lastModified < 8_000 ? 'running' : 'idle', lastActive: Math.min(now, s.lastModified),
          model: null, effort: null, costUsd: 0, contextTokens: 0, background: 0, origin: 'terminal',
        });
        liveIds.add(s.sessionId);
      }
      live.sort((a, b) => b.lastActive - a.lastActive);
      const closed = saved
        .filter(s => !liveIds.has(s.sessionId))
        .slice(0, CLOSED_LIMIT)
        .map(s => ({ sessionId: s.sessionId, cwd: s.cwd ?? '', title: s.customTitle || s.summary || s.firstPrompt || '', state: 'idle', lastActive: s.lastModified }));
      return { open: live, closed };
    }
    case 'open':
      return { key: (await openSession({ cwd: msg.cwd, resume: msg.resume })).key };
    case 'attach': {
      let s = open.get(msg.key);
      // A terminal session's key opens its live mirror on demand (or a normal session if it went quiet).
      if (!s && String(msg.key).startsWith('term:')) s = await openSession({ resume: String(msg.key).slice(5), key: msg.key });
      if (!s) throw new Error('This session is no longer open');
      s.watchers.add(ws);
      return { session: s.info(), items: s.items };
    }
    case 'detach':
      open.get(msg.key)?.watchers.delete(ws);
      return {};
    case 'send': {
      const s = open.get(msg.key);
      if (!s) throw new Error('This session is no longer open');
      const text = String(msg.text ?? '').trim();
      const images = (Array.isArray(msg.images) ? msg.images : [])
        .filter(i => i && IMAGE_TYPES.has(i.mediaType) && typeof i.data === 'string' && i.data.length <= IMAGE_LIMIT)
        .slice(0, MAX_IMAGES)
        .map(i => ({ mediaType: i.mediaType, data: i.data }));
      if (Array.isArray(msg.images) && images.length < msg.images.length) throw new Error(`Images must be JPEG, PNG, GIF or WebP under ${Math.round(IMAGE_LIMIT / 1024 / 1024 * 0.75)} MB, at most ${MAX_IMAGES}`);
      if (!text && !images.length) throw new Error('Empty message');
      await s.send(text, validModel(msg.model), validEffort(msg.effort), images);
      return {};
    }
    case 'set_model': {
      const s = open.get(msg.key);
      if (!s) throw new Error('This session is no longer open');
      await s.setModel(validModel(msg.model), validEffort(msg.effort));
      return { session: s.info() };
    }
    case 'models':
      return { models: await models() };
    case 'usage':
      return await usage();
    case 'account':
      return await account();
    case 'login_start':
      return await loginStart(!!msg.console);
    case 'login_code':
      return await loginCode(msg.code);
    case 'login_cancel':
      loginCancel();
      return {};
    case 'logout': {
      await runClaude(['auth', 'logout']);
      for (const s of open.values()) s.stopProcess();
      modelCache = null;
      return await account();
    }
    case 'interrupt':
      open.get(msg.key)?.interrupt();
      return {};
    case 'close': {
      const s = open.get(msg.key);
      if (s) closeSession(s);
      return {};
    }
    default:
      throw new Error(`Unknown request: ${msg.t}`);
  }
}

/* ---------------------------------------------------------------
   HTTP (health) + WebSocket
   --------------------------------------------------------------- */
if (!HOST) {
  console.error('No Tailscale address found on this machine. Start Tailscale and sign in, or set HOST to the address to listen on.');
  process.exit(1);
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ service: 'claude-remote', name: NAME, status: 'ok', open: open.size, uptime_s: Math.round(process.uptime()) }));
});

const wss = new WebSocketServer({ server, path: '/ws', perMessageDeflate: false });

// Drop phones that went away without closing the socket (network change, sleep).
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.alive === false) { ws.terminate(); continue; }
    ws.alive = false;
    ws.ping();
  }
}, 30_000);
wss.on('close', () => clearInterval(heartbeat));

wss.on('connection', (ws, req) => {
  log('connect', req.socket.remoteAddress);
  ws._socket.setNoDelay(true);
  ws.alive = true;
  ws.on('pong', () => { ws.alive = true; });
  const reply = frame => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(frame)); };
  reply({ t: 'ready', name: NAME });

  ws.on('message', async raw => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return reply({ t: 'error', message: 'bad frame' }); }
    try {
      reply({ t: msg.t, id: msg.id, ...(await handle(ws, msg)) });
    } catch (err) {
      reply({ t: 'error', id: msg.id, message: err.message });
    }
  });

  ws.on('close', () => {
    log('disconnect', req.socket.remoteAddress);
    for (const s of open.values()) s.watchers.delete(ws);
  });
  ws.on('error', err => log('socket error:', err.message));
});

server.on('error', err => {
  console.error(err.code === 'EADDRINUSE' ? `Port ${PORT} is already in use. Is another bridge running? Set PORT to use another.` : err.message);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  log(`claude-remote bridge "${NAME}" on ${HOST}:${PORT}`);
  log(`Add it in the app with the address ${HOST}${PORT === 8000 ? '' : `:${PORT}`}`);
  account().then(a => log(a.loggedIn ? `Claude Code signed in as ${a.email ?? 'unknown'} (${a.plan ?? a.method ?? 'unknown plan'})` : 'Claude Code is NOT signed in: sign in from the app, or run `claude` here once')).catch(() => {});
});

// One misbehaving session must not take the whole bridge (and every other session) down.
process.on('unhandledRejection', err => log('unhandled:', err?.message ?? err));
process.on('uncaughtException', err => log('uncaught:', err?.stack ?? err));

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    for (const s of open.values()) s.stopProcess();
    process.exit(0);
  });
}
