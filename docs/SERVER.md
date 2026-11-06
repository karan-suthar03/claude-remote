# The bridge

`server/index.mjs` is a single-file Node server that lets the phone drive Claude Code on this computer. It runs Claude Code through the [Claude Agent SDK](https://docs.claude.com/en/docs/agent-sdk/overview) (`@anthropic-ai/claude-agent-sdk`), using the computer's own Claude Code login, so you don't need an API key. The phone talks to it with JSON over a WebSocket, which it reaches only through Tailscale.

Setup steps are in the [README](../README.md). Security notes are in [SECURITY.md](SECURITY.md).

## Running it

```sh
cd server
npm install
npm start          # node index.mjs
```

Node 20 or newer is required. For autostart, see the table in the README's setup section.

### Environment variables

| Variable | Default | What it does |
|---|---|---|
| `PORT` | `8000` | HTTP and WebSocket port. The WebSocket is at `/ws`, and any other HTTP `GET` returns a health check. |
| `HOST` | this machine's Tailscale IPv4 | The address to listen on. By default the bridge uses the first interface address in `100.64.0.0/10`. If there isn't one, it exits. **Don't set it to `0.0.0.0` or a LAN address**: anyone who can reach it could run Claude Code on this computer. |
| `BRIDGE_NAME` | the computer's hostname | The name shown in the app. The app renames the device to match it. |
| `DEFAULT_MODEL` | `sonnet` | Model for a new session when the phone doesn't send one. |
| `DEFAULT_EFFORT` | `medium` | Effort for a new session when the phone doesn't send one. Accepted values: `low`, `medium`, `high`, `xhigh`, `max`. |
| `IDLE_PROCESS_MS` | `1800000` (30 min) | How long an open session can go without activity before its Claude Code process is stopped. The session stays open and the process starts again on the next message. |
| `SESSION_TTL_MS` | `86400000` (24 h) | How long an open session can go without activity before it is closed. It moves to the closed list. |
| `CLOSED_LIMIT` | `50` | Maximum number of closed (saved) sessions returned by `sessions`. |
| `FOLDER_LIMIT` | `200` | Maximum number of closed sessions returned by `sessions` for one folder (`cwd`), shown in the app's folder picker. |
| `ACTIVE_MS` | `120000` | A saved session whose transcript was written this recently (and not by the bridge) is listed as open, as a session running in a terminal. |
| `PEER_PORTS` | (none) | Extra ports, comma-separated, to look for other bridges on for `peers`. Ports 8000–8010 and this bridge's own `PORT` are always checked. |
| `IMAGE_LIMIT` | `5242880` | Maximum base64 characters per image (about 3.7 MB of image). Up to 10 images per message. |
| `OUTPUT_LIMIT` | `20000` | Maximum characters kept for each step's `detail` and `output`. Longer text keeps its start and end, with a `… N characters not shown …` marker between them. |

`WORK_DIR` (optional) adds a **Default folder** shortcut at the top of the app's folder picker. You still choose the folder for each session in the app. The install scripts accept it as `-WorkDir` (Windows) or `WORK_DIR=` (Linux/macOS).

Examples:

```sh
PORT=8001 BRIDGE_NAME=workstation npm start                 # macOS / Linux
$env:PORT=8001; $env:BRIDGE_NAME="workstation"; npm start   # Windows PowerShell
```

When it starts, the bridge logs the address to enter in the app and whether Claude Code is signed in.

### How Claude Code is run

Each session's process is started with `query()` from the Agent SDK, with these options:

| Option | Value | Why |
|---|---|---|
| `systemPrompt` | preset `claude_code` | Claude Code's own system prompt and tools |
| `settingSources` | `user`, `project`, `local` | Reads `~/.claude/settings.json`, the project's `.claude/` settings, `CLAUDE.md` files and MCP servers, just like running `claude` in that folder |
| `cwd` | the folder picked on the phone | |
| `permissionMode` | `bypassPermissions` | Nothing asks before edits or commands (see [SECURITY.md](SECURITY.md)) |
| `disallowedTools` | `AskUserQuestion` | The app can't answer multiple-choice prompts yet, so Claude asks in plain text instead |
| `thinking` | adaptive, summarized | Shown as the expandable "Thinking" block |
| `includePartialMessages` | `true` | Streams text and thinking as they are written |
| `forwardSubagentText` | `true` | Streams subagents' own steps, shown nested under their Agent step |
| `resume` | the session id | Used when reopening a saved session |

Prompts are fed in streaming-input mode, so one process serves many turns and keeps running between them. That is what lets background tasks and subagents finish while no phone is connected.

The bridge reads the SDK stream with `iterator.next()`, not `for await`. Leaving a `for await` loop at the end of a turn would call the iterator's `return()` and close Claude Code.

## Protocol

All frames are JSON text on `ws://<tailscale-ip>:<PORT>/ws`.

### Connection

- When the phone connects, the bridge sends `{ t:"ready", name }`.
- The bridge pings every client every 30 seconds. A client that hasn't answered the previous ping is dropped, which cleans up phones that vanished without closing the socket (network change, sleep). The app also pings every 20 seconds.
- A client that disconnects is removed from every session it was attached to. The sessions themselves keep running.

### Requests and replies

Every request carries a client-chosen `id`. The reply has the same `t` and `id` plus the fields shown. Requests marked "→ `{}`" reply with just `{ t, id }`.

On failure, the reply is `{ t:"error", id, message }`. A frame that isn't valid JSON gets `{ t:"error", message:"bad frame" }`, with no `id`.

| Request | Reply | Notes |
|---|---|---|
| `{ t:"info" }` | `{ name, platform, home, sep }` | `platform` is Node's `process.platform`. `sep` is the path separator. |
| `{ t:"roots" }` | `{ roots:[{ name, path }] }` | `Home` first, then every drive letter that exists (Windows) or `/` (others). |
| `{ t:"ls", path }` | `{ path, parent, dirs:[{ name, path }] }` | Subfolders only, including symlinks to folders. Normal folders come first, then hidden ones, each sorted alphabetically. `parent` is `null` at a root. Defaults to home. |
| `{ t:"sessions", cwd? }` | `{ open:[Session], closed:[Session] }` | `open`: sessions held by this bridge, plus sessions running in a terminal right now (`origin:"terminal"`, key `term:<sessionId>`), newest first. `closed`: sessions Claude Code saved on this computer (from any source, including the terminal) that aren't open, up to `CLOSED_LIMIT`. This includes project directories under `~/.claude/projects` that are symlinks or junctions (for example to another machine's transcripts), which the Agent SDK's own listing skips: the bridge works out each one's folder from its name and lists it there, re-reading them at most every 15 seconds. With `cwd`, only that folder's sessions, up to `FOLDER_LIMIT` closed: the ones in its Claude Code project directory, like `/resume` shows there, including sessions recorded before the folder was moved. Paths are compared case-insensitively on Windows and macOS, and each session's `cwd` is the folder asked about, which is where it resumes. |
| `{ t:"open", cwd }` | `{ key }` | Opens a new session in `cwd`, which must be an existing folder. No process starts until the first `send`. |
| `{ t:"open", resume, cwd? }` | `{ key }` | Reopens saved session `resume`, loading its transcript as items. If the session is already open, returns its existing key. `cwd` is looked up if not given. |
| `{ t:"attach", key }` | `{ session:Session, items:[Item] }` | A snapshot of the chat. After the reply, this socket gets the session's live `ev` frames. Attaching again (for example after a reconnect) replaces the snapshot. |
| `{ t:"detach", key }` | `{}` | Stops this socket's live events for that session. |
| `{ t:"send", key, text, images?, model?, effort? }` | `{}` | Sends a user message. `images`: up to 10 `{ mediaType, data }` (base64 JPEG, PNG, GIF or WebP); `text` may be empty when images are sent. Sending to a terminal session continues it in the bridge. Starts the process if needed. A new `model` or `effort` is applied first. The reply comes as soon as the message is queued, and the chat arrives as events. |
| `{ t:"interrupt", key }` | `{}` | Interrupts the current turn, like Esc in the terminal. |
| `{ t:"task_output", key, taskId }` | `{ output, totalBytes, truncated }` | The end (last 8 KB) of a background shell task's output, like `/tasks` in the terminal. `taskId` from the session's `tasks`. |
| `{ t:"stop_task", key, taskId }` | `{}` | Stops one background task. The bridge declares per-task stop, so the stop button (`interrupt`) ends only the turn and leaves background tasks running. |
| `{ t:"background", key, toolUseId? }` | `{ ok }` | Like Ctrl+B in the terminal: running Bash commands and subagents move to the background and the turn carries on; each one reports back when it finishes. With `toolUseId`, only the task that tool call started. `ok` is false when nothing was running in the foreground. |
| `{ t:"close", key }` | `{}` | Ends the Claude Code process and removes the session from `open`. Attached sockets get `{ t:"closed", key }`. The transcript stays on disk, so the session shows in `closed` and can be resumed. |
| `{ t:"set_model", key, model?, effort? }` | `{ session:Session }` | Changes the model and/or effort. If the process is running, the change applies to it right away. |
| `{ t:"models" }` | `{ models:[{ value, displayName, description, efforts }] }` | The models this login can use, with their supported effort levels. Cached for 10 minutes. |
| `{ t:"usage" }` | `{ subscription, available, limits:[{ name, utilization, resetsAt }] }` | Plan usage. `utilization` is a percentage and `resetsAt` is epoch ms. `available` is false for logins without plan limits, such as Console/API. |
| `{ t:"account" }` | `{ loggedIn, email, org, plan, method }` | From `claude auth status --json`. |
| `{ t:"login_start", console? }` | `{ url }` | Starts a sign-in (see [Login](#login)). `console:true` signs in with an Anthropic Console account instead of a Claude subscription. |
| `{ t:"login_code", code }` | `{ ok, account }` | Finishes the sign-in with the code shown on the sign-in page. Can take up to 60 seconds. |
| `{ t:"login_cancel" }` | `{}` | Abandons a sign-in in progress. |
| `{ t:"peers" }` | `{ peers:[{ name, host, address, url }] }` | Other computers on this tailnet running the bridge, so the app can offer them under **Available to add**. Asks `tailscale status --json` for online peers (phones skipped), then checks each one's ports 8000–8010, `PORT` and `PEER_PORTS` for the bridge's health reply. `name`: that bridge's `BRIDGE_NAME`; `host`: its Tailscale hostname; `url`: `ws://ip:port/ws`. Cached for 30 seconds. |
| `{ t:"logout" }` | `{ loggedIn, email, org, plan, method }` | Runs `claude auth logout` and stops every session's process. |

`model` must look like a model id or alias (`[\w.:\[\]-]`, at most 80 characters). `effort` must be one of `low`, `medium`, `high`, `xhigh`, `max`. Invalid values are ignored.

### Pushed frames

| Frame | When |
|---|---|
| `{ t:"ev", key, e:Event }` | Something happened in a session this socket is attached to |
| `{ t:"closed", key }` | The session was closed: by a `close` request, or by the TTL |

### Shapes

**Session**

```js
{
  key,            // handle for open sessions; absent for closed ones
  sessionId,      // Claude Code's session id (null until the first message of a new session)
  cwd,            // working folder
  title,          // saved title, summary or first prompt; else the folder name
  state,          // "running" | "idle"
  lastActive,     // epoch ms
  model, effort,  // open sessions only
  costUsd,        // total cost of this process so far (open sessions only)
  contextTokens,  // input + cache tokens of the last turn, i.e. current context size
  background,     // background tasks running right now
  tasks,          // and which: [{ id, type, description, agent }] (type e.g. "local_bash", "local_agent")
  origin,         // "bridge" | "terminal" (running in a terminal, mirrored live)
  turnMs,         // how long the current turn has been running (0 when idle)
  turnTokens,     // output tokens of the current turn so far (estimated while a message streams)
}
```

**Item** (one entry in the chat)

```js
{
  role,           // "user" | "assistant" | "note"   (note: status lines such as limits, errors, compaction)
  text,           // Markdown for assistant items
  images?,        // user only: [{ mediaType, data }] attached from the phone
  thinking?,      // assistant only
  steps?: [Step], // assistant only, in call order
}
```

**Step** (one tool call)

```js
{
  id,             // tool_use id
  name,           // tool name, e.g. "Bash", "Edit", "mcp__server__tool"
  label,          // one-line summary: "$ npm test", "Edit src/app.ts", "Agent (Explore): …"
  detail,         // the call in full: command, diff of the edit, written file, prompt, or JSON input
  detailLang,     // "bash" | "diff" | "json" | a file extension | ""
  sub,            // true if a subagent made the call
  done,           // the result has arrived
  error,          // the tool reported an error
  output,         // the result as text (images become "[image]"), trimmed to OUTPUT_LIMIT
  images?,        // images in the result, e.g. Read on a PNG: [{ mediaType, data }]
}
```

**Event** `e`, applied to the items by `applyEvent()`. The app's `ChatModel.kt` implements the same rules, so a snapshot plus later events always matches the bridge's state.

| `k` | Fields | Effect |
|---|---|---|
| `user` | `text, images?` | Appends a user item |
| `text` | `text` | Appends to the last assistant item's text, creating one if needed |
| `thinking` | `text` | Appends to the last assistant item's thinking |
| `sep` | – | A new model message started. Adds a paragraph break if the assistant text doesn't end with one. |
| `step` | `id, name, label, detail, detailLang, sub` | Adds a step to the last assistant item |
| `stepDone` | `id, error, output, images?` | Marks the matching step done (searching backwards) |
| `note` | `text` | Appends a note item |
| `state` | `state` | The session's state changed (`running` / `idle`) |
| `meta` | `session:Session` | Session fields changed: model, effort, cost, context, background tasks, session id |

Notes come from: usage limit warnings and rejections (with reset time), sign-in problems, non-success turn results, context compaction, background task updates, and a Claude Code process that crashed.

## Terminal sessions

Claude Code sessions you run in a terminal on the computer show up in the app too:

- **Detected** when their transcript (`~/.claude/projects/<folder>/<sessionId>.jsonl`) was written in the last `ACTIVE_MS`, and not by the bridge itself. They are listed under **Open** with `origin:"terminal"`, `running` while the transcript changed in the last 8 seconds.
- **Mirrored live.** Attaching loads the transcript, then the bridge follows the file every second and turns each new entry into the same events as a bridge session. The phone watches the terminal work as it happens.
- **Continued from the phone.** Sending a message stops the mirror and resumes the session in the bridge (same session id), with a note in the chat. The terminal's own copy doesn't see those messages; quit it there, or continue with `claude --resume` later to pick up everything. **Exit Claude Code in the terminal before sending from the phone**: if both keep going they write to the same session and the conversation gets mixed up. The app says so on a terminal session and asks before its first message.
- A mirror nobody watches is dropped once the terminal has been quiet for `ACTIVE_MS`; the session then appears under **closed**.

## Session lifecycle

```
             open(cwd) / open(resume)
                      |
                      v
   +-----------> OPEN, no process ----- send ------> OPEN, process running
   |                  ^                                   |        |
   |                  |  idle > IDLE_PROCESS_MS           |        | turn ends: state "idle",
   |                  +-------- (process stopped) --------+        | process stays warm
   |                                                               |
   |   close  /  no activity for SESSION_TTL_MS                    |
   +-------------------------------> CLOSED <----------------------+
                                     (transcript in ~/.claude; resume with open(resume))
```

- **Open.** The bridge holds the session in memory. Its Claude Code process starts with the first `send`. For a resumed session, the process is started with `resume`, and the transcript was loaded when it was opened.
- **Running and idle.** A session is `running` from a `send` until Claude Code reports it is idle. Background tasks and subagents keep their process alive and keep producing events, whether or not a phone is attached. Every message from Claude Code updates `lastActive`.
- **Idle process stop.** A check runs every minute. A session that isn't `running` and has had no activity for `IDLE_PROCESS_MS` has its process stopped to free memory. It stays in `open`, and the next `send` resumes it from its session id.
- **Close.** A `close` request, or `SESSION_TTL_MS` without activity (never while `running`), ends the process and removes the session from `open`. Claude Code has already saved the transcript under `~/.claude/projects/`, so the session shows up in `closed`.
- **Bridge restart.** On SIGINT or SIGTERM, every process is stopped. Open sessions don't survive a restart, but they appear in `closed` and can be resumed.
- **Crashes.** If a session's Claude Code process fails, a note is added and the session goes back to idle. The next message starts a new process. Unhandled errors are logged rather than taking down the bridge.

## Login

The bridge signs in with the same Claude Code executable the SDK runs. That is the platform binary bundled with `@anthropic-ai/claude-agent-sdk`, or `claude` on `PATH` if that can't be found. Credentials end up wherever Claude Code keeps them for that user, so a login from the phone also applies to `claude` in the terminal, and the other way round.

1. `login_start` runs `claude auth login --claudeai` (or `--console`) with `BROWSER=none`, so nothing opens on the computer. The bridge waits up to 20 seconds for the sign-in URL and returns it.
2. The user opens the URL on the phone, signs in, and copies the code the page shows.
3. `login_code` writes the code to the waiting process and waits up to 60 seconds for it to exit. Then it checks `claude auth status`. On success it clears the model cache and stops idle sessions' processes so they pick up the new login. Running sessions keep the old login until their process restarts. On failure, the error message is Claude Code's last line of output.

Only one sign-in can be in progress. A new `login_start` cancels the previous one.

## Logs

Everything goes to stdout and stderr, each line prefixed with an ISO timestamp:

- Startup: the address it listens on, the address to enter in the app, and the signed-in account and plan (or a warning that Claude Code is not signed in).
- `connect <ip>` and `disconnect <ip>` for each phone.
- `session <key> failed: …` when a Claude Code process ends with an error.
- `unhandled:` and `uncaught:` for unexpected errors.

Where to find them:

| How it runs | Logs |
|---|---|
| `npm start` | the terminal |
| Windows scheduled task | `server\bridge.log` |
| macOS launchd agent | `server/bridge.log` |
| Linux systemd user service | `journalctl --user -u claude-remote -f` |

Chat content isn't logged by the bridge. Claude Code's own transcripts are in `~/.claude/projects/`.

## Health check

```sh
curl http://100.x.y.z:8000/
# {"service":"claude-remote","name":"my-laptop","status":"ok","open":2,"uptime_s":5123}
```
