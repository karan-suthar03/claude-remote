# Claude Code bridge

`server/index.mjs` is a small Node server that runs **Claude Code through the Claude Agent SDK**, signed in with the computer's own Claude Code login, so no API key is involved. The phone talks to it over a WebSocket, reached through Tailscale only. Setup steps are in the [README](../README.md).

## Running it

```sh
cd server
npm install
npm start
```

| Env var | Default | What |
|---|---|---|
| `PORT` | `8000` | HTTP + WebSocket port (`/ws`; `GET /` is a health check) |
| `HOST` | this machine's Tailscale IP | Address to listen on. Found automatically (100.64.0.0/10); the bridge won't start without one. Don't set it to `0.0.0.0`: anyone on your network could then run Claude Code on this computer |
| `WORK_DIR` | `$HOME` | Folder Claude Code works in |
| `DEFAULT_MODEL` / `DEFAULT_EFFORT` | `sonnet` / `medium` | Used until the phone sends its own |
| `IDLE_EVICT_MS` | 30 min | Idle conversations are closed; they resume from their session id |
| `TURN_TIMEOUT_MS` | 30 min | A turn running longer is interrupted |

Claude Code runs with its own system prompt and tools, and reads the user's and project's settings and `CLAUDE.md` (`settingSources: user, project, local`). **Permissions are bypassed for now**: nothing asks before edits or commands.

## Why it's fast

- **Warm processes:** each conversation is one long-lived `query()` in streaming-input mode, so Claude Code stays running between turns.
- **A spare process:** one is always pre-spawned, so a new conversation's first message doesn't pay for start-up.
- **The phone pre-connects:** it opens the WebSocket when the app comes to the front.

**Gotcha:** don't read the SDK stream with `for await`. Leaving the loop at the end of a turn calls the iterator's `return()`, which closes Claude Code. The server reads the stream with `iterator.next()`.

## Protocol

JSON text frames on `/ws`:

| Direction | Frame |
|---|---|
| phone → server | `{t:"send", conversation?, model?, effort?, content:[blocks]}` (Anthropic content blocks) |
| phone → server | `{t:"cancel"}` |
| phone → server | `{t:"warm", conversation}`: start or resume that session before the next send |
| server → phone | `{t:"ready"}` on connect |
| server → phone | `{t:"session", conversation}`: the Claude Code session id; the phone sends it back on later turns |
| server → phone | `{t:"thinking", text}` / `{t:"text", text}`: streamed deltas |
| server → phone | `{t:"step", name, input, label}`: a Claude Code tool is running, shown in the chat |
| server → phone | `{t:"done", stop, costUsd, ms}` / `{t:"error", message}` |

- **After a restart or idle eviction**, the server resumes a conversation from its session id, because Claude Code saves sessions to disk.
- **On the phone**, each turn is also stored locally as text, so chats show offline.

Test from any device on your tailnet: `node server/test-client.mjs ws://<bridge-tailscale-ip>:8000/ws`.

- **If the Claude login expires:** run `claude` on that computer, then `/login`.
- **Usage limits:** shared with any other Claude Code use on the account.
