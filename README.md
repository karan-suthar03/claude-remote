# Claude Remote

Claude Remote lets you use Claude Code on your own computers from your Android phone, from anywhere. A small bridge runs on each computer and drives Claude Code through the Claude Agent SDK, using that computer's Claude Code login, settings, `CLAUDE.md` files and tools. The phone app connects to the bridge over [Tailscale](https://tailscale.com), so nothing is exposed to the internet. Sessions live on the computer: start one from the phone, put the phone away, and Claude Code keeps working. Its background tasks and subagents keep running too. When you open the app again, the chat is where it left off.

## Quick start

You need [Tailscale](https://tailscale.com/download) on your computer **and** your phone (same account), and [Node.js](https://nodejs.org) 20+ on the computer.

**1. On the computer**, install the bridge. It starts now and at every login:

```powershell
# Windows (PowerShell)
irm https://raw.githubusercontent.com/karan-suthar03/claude-remote/main/install.ps1 | iex
```
```sh
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/karan-suthar03/claude-remote/main/install.sh | sh
```

It ends by printing the address to use, like `100.101.102.103`.

**2. On the phone**, install the app: **[download claude-remote.apk](https://github.com/karan-suthar03/claude-remote/releases/latest/download/claude-remote.apk)** (Android 8+; allow installs from your browser when asked). Open it, tap **Add device**, and enter that address. If Claude Code on the computer isn't signed in yet, tap **Sign in** on the device screen.

That's it: tap **New session**, pick a folder, and talk to Claude Code.

### Add more computers, including remote servers

Run the same installer on every machine you want to reach: your desktop, a laptop, or a **remote Linux server** (a VPS, a cloud VM, a home server). Each one becomes a device in the app's sidebar, with its own sessions, folders, account and usage. Switch between them from the sidebar.

On a remote server, over SSH:

```sh
# 1. Tailscale on the server (once), signed in to the same account as your phone
curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up

# 2. The bridge
curl -fsSL https://raw.githubusercontent.com/karan-suthar03/claude-remote/main/install.sh | sh
```

- It runs as a service and keeps running after you log out (systemd user service with lingering; on servers without a user session it installs a system service that runs as your user).
- No browser is needed on the server: sign Claude Code in from the app (device screen → **Sign in**), which shows the sign-in link on your phone.
- Sessions you start in a terminal on the server (for example over SSH with `claude`) show up in the app live, marked **Terminal**.
- If port 8000 is taken, install with `| PORT=8001 sh` instead of `| sh`, and add the server in the app as `100.x.y.z:8001`.

### Or let Claude Code set it up

If you use Claude Code, paste this into it on the computer:

> Set up Claude Remote on this computer by following https://github.com/karan-suthar03/claude-remote/blob/main/SETUP.md

[SETUP.md](SETUP.md) walks it through checking Node and Tailscale, installing and verifying the bridge, and telling you what to enter in the app.

## Features

- **Several computers.** Every computer running the bridge is a device in the app's side drawer, with a dot that shows whether it's connected.
- **Device screen.** Shows the account Claude Code is signed in with, your plan's usage (5-hour and weekly limits, with reset times), and the open and closed sessions on that computer.
- **New sessions in any folder.** A folder picker browses the computer's home folder and every drive (Windows) or `/` (macOS, Linux), with a filter box.
  Inside a folder it also lists that folder's earlier sessions (from the phone or the terminal), so you can pick one up where you left off.
- **Resume anything.** Closed sessions include ones started in a terminal with `claude`, and any of them can be reopened from the phone.
- **Live chat.** Replies stream in as Markdown. Thinking can be expanded. Every tool call shows as a step: the command, diff, file contents or JSON input, and the tool's output, the way `claude --verbose` shows them. Subagent steps are indented.
- **Model and effort picker** in each session. Changes apply right away, even mid-session, and you can save a choice as the default for new sessions.
- **Session stats.** Cost so far, context size, and how many background tasks are running.
- **Stop and close.** Interrupt the current turn, or close the session to end its Claude Code process.
- **Sign in from the phone.** If Claude Code on the computer isn't signed in, the app shows a sign-in link. You open it, sign in, and paste the code back into the app. Signing out works from the phone too.
- **Survives bad networks.** The app reconnects by itself and fetches a fresh copy of the chat, so nothing is lost while the phone was offline or asleep.

## How it works

```
 Android phone                                   Your computer (Windows / macOS / Linux)
+--------------------+                          +-------------------------------------------+
| Claude Remote app  |   WebSocket over         |  bridge  (server/index.mjs, Node 20+)      |
|  - device list     |   Tailscale (WireGuard)  |  listens on 100.x.y.z:8000 only            |
|  - chat UI         | <======================> |                                           |
+--------------------+   ws://100.x.y.z:8000/ws |   one Claude Code process per open session |
                                                |   (Claude Agent SDK, streaming input)      |
                                                |        |                                   |
                                                |        v                                   |
                                                |   Claude Code: tools, MCP, CLAUDE.md,      |
                                                |   ~/.claude sessions and login             |
                                                +-------------------------------------------+
```

One bridge runs on each computer. The phone keeps no chat history of its own. It asks the bridge for the session list and, when you open a chat, for a snapshot of it followed by live events. The protocol is described in [docs/SERVER.md](docs/SERVER.md).

## Requirements

| Where | What |
|---|---|
| Computer | Windows, macOS or Linux |
| Computer | [Node.js](https://nodejs.org) 20 or newer |
| Computer | A Claude plan that includes Claude Code (Pro, Max, Team or Enterprise) or an Anthropic Console account. Claude Code itself comes with the bridge; sign in from the app or by running `claude` once. |
| Computer **and** phone | [Tailscale](https://tailscale.com/download), signed in to the **same** tailnet |
| Phone | Android 8.0 (API 26) or newer |
| To build the app | Android Studio, or JDK 17 plus the Android SDK 36 (see [docs/BUILD.md](docs/BUILD.md)) |

## Manual setup

The installers above do all of this. To set it up by hand, or to run it from a clone:


1. **Install Tailscale on the computer and on the phone.** Sign in to the same account on both and check that both show as connected. Find the computer's Tailscale IP: run `tailscale ip -4`, or look in the Tailscale app. It looks like `100.x.y.z`.

2. **Install Claude Code on the computer.**
   ```sh
   npm install -g @anthropic-ai/claude-code
   ```
   You can sign in now by running `claude` once. You can also skip this and sign in from the app in step 6.

3. **Start the bridge on the computer.**
   ```sh
   git clone https://github.com/karan-suthar03/claude-remote.git
   cd claude-remote/server
   npm install
   npm start
   ```
   It prints something like:
   ```
   claude-remote bridge "my-laptop" on 100.x.y.z:8000
   Add it in the app with the address 100.x.y.z
   Claude Code signed in as you@example.com (max)
   ```
   To keep it running whenever you're signed in to the computer, install it as a background service. Run these commands in `server/`:

   | OS | Install | Uninstall | Runs as | Logs |
   |---|---|---|---|---|
   | Windows | `powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1` | add `-Remove` | Scheduled task "Claude Remote bridge", at sign-in, restarted if it stops | `server\bridge.log` |
   | macOS | `sh scripts/install-macos.sh` | add `--remove` | launchd agent `com.claude-remote.bridge`, at login, kept alive | `server/bridge.log` |
   | Linux | `sh scripts/install-linux.sh` | add `--remove` | systemd user service `claude-remote`, with lingering so it keeps running after you log out | `journalctl --user -u claude-remote -f` |

   The scripts run `npm install` if needed and use port 8000. To use another port, pass `-Port 8001` on Windows or set `PORT=8001` before the command on macOS and Linux. All options are in [docs/SERVER.md](docs/SERVER.md).

4. **Install the app on the phone.** Build it and install it as described in [docs/BUILD.md](docs/BUILD.md).

5. **Add the computer.** Open **Claude Remote** and tap **Add device**. Enter the computer's Tailscale IP, such as `100.x.y.z`; port 8000 is assumed. You can also enter `100.x.y.z:8001` or a full `ws://` URL. The name is optional. The app uses the computer's name if you leave it blank.

6. **Sign in, if needed.** If Claude Code on that computer isn't signed in, the device screen shows **Sign in**:
   1. Choose **Claude subscription** (Pro, Max or Team) or **Anthropic Console** (API billing).
   2. Tap **Open**, or copy the link into a browser, and sign in.
   3. Copy the code the page shows, paste it into the app, and tap **Finish sign-in**.

   This signs in Claude Code on the computer, the same as running `claude auth login` there.

Repeat steps 2–6 for each computer you want to use.

## Using it

- **Start a session.** On a device's screen, tap **New session**, browse to a folder, and tap **Start Claude Code here**. Claude Code runs in that folder with the computer's user and project settings, exactly like running `claude` there.
- **Open and closed sessions.** *Open* sessions are running on the bridge right now, and a running one is marked **Running**. *Closed* sessions are ones Claude Code saved on the computer: earlier bridge sessions and sessions started in a terminal. Tap a closed session to resume it with its full history. The device screen refreshes every few seconds.
- **Model and effort.** Use the bar above the message box. Changes apply to that session right away. **Use for new sessions** saves the choice as this phone's default. You can reset it under **Settings** in the drawer.
- **Tool steps.** Tap a step to show or hide what it ran and what it returned. Outputs are shown expanded by default. To start them collapsed, turn off **Always expand outputs** in **Settings**.
- **Background a task.** While a long command or subagent runs, **Move to background** in the chat menu does what Ctrl+B does in the terminal: it keeps running in the background and Claude carries on. Messages sent while Claude is working are queued and read after the current step.
- **Stop or close.** The stop button interrupts the current turn. **Close session** in the chat menu ends the Claude Code process, and the session moves to the closed list, where you can resume it later.
- **Multiple computers.** Open the drawer to switch devices, add one, or remove one. Removing a device from the phone leaves its sessions on the computer. Once one computer is added, the others on your tailnet that run the bridge show up under **Available to add** in the drawer (and in **Add device**): tap one to add it. The first one is added by address.
- **Terminal sessions.** A session still open in a terminal on the computer is shown live, marked **Terminal**. Exit Claude Code in the terminal before sending from the phone; using both at once mixes up the session. The app asks before the first message.
- **More than one phone.** Every phone keeps its own device list and finds the same computers. Two phones can watch the same session; send from one at a time.
- **Leaving the app.** Closing the app or losing signal does not stop anything. Turns, background tasks (such as `run_in_background` commands) and subagents keep running on the computer. When you come back, the app reconnects and shows everything that happened.
- **The computer is the source of truth.** The phone stores only its device list and default model and effort. Chats, titles, costs and usage are always fetched from the bridge, so a session looks the same from any phone. A session started on the phone can also be resumed in a terminal with `claude --resume`.

## Security

The bridge has **no login of its own**. Anyone who can reach its port can run Claude Code on your computer as you, and Claude Code can run any command there. Tailscale is the security boundary:

- The bridge listens only on the computer's Tailscale address, so other devices on your Wi-Fi or LAN can't reach it. It refuses to start if Tailscale isn't up.
- Only devices in your tailnet can reach that address. Use [Tailscale ACLs](https://tailscale.com/kb/1018/acls) to limit port 8000 to your own devices, especially if your tailnet has other users or shared nodes.
- Claude Code runs with permission prompts bypassed (see Limitations), so be careful what you point it at.

Read [docs/SECURITY.md](docs/SECURITY.md) for the full threat model, an example ACL, and what is stored where.

## Limitations

- **Permissions are bypassed.** Claude Code runs as with `claude --dangerously-skip-permissions`: it edits files and runs commands without asking. Approving actions from the phone is planned.
- **No multiple-choice questions.** Claude Code's `AskUserQuestion` tool is disabled because the app can't answer it yet. Claude asks in plain text instead, and you reply in the chat.
- **Android only.** There is no iOS app.
- **Shared usage limits.** Sessions use the signed-in account's plan limits, shared with every other Claude Code use on that account. Plan usage isn't shown for Console (API-key) sign-ins.
- **Text only.** You can't attach images or files from the phone yet.

## Troubleshooting

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Project layout

| Path | What |
|---|---|
| `server/index.mjs` | The bridge: WebSocket server, session registry, Claude Agent SDK driver, login |
| `server/scripts/` | Autostart installers for Windows (`install-windows.ps1`), macOS (`install-macos.sh`) and Linux (`install-linux.sh`) |
| `server/package.json` | Bridge dependencies (`@anthropic-ai/claude-agent-sdk`, `ws`) |
| `android/` | The Android app (Kotlin, Jetpack Compose, OkHttp) |
| `android/app/src/main/java/.../bridge/` | `BridgeClient`: one WebSocket per computer, request/reply matching, reconnect and re-attach |
| `android/app/src/main/java/.../data/` | Chat model (mirrors the bridge's event rules), device list, settings |
| `android/app/src/main/java/.../ui/` | Device drawer and screen, account and usage cards, sign-in dialog, folder picker, chat |
| `docs/SERVER.md` | Bridge configuration, protocol reference, session lifecycle, login, logs |
| `docs/SECURITY.md` | Threat model and hardening |
| `docs/TROUBLESHOOTING.md` | Common problems and fixes |
| `docs/BUILD.md` | Building the app and installing it on a phone |
