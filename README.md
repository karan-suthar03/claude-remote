# Claude Remote

Use Claude Code on your own computer from your Android phone, from anywhere.

A small **bridge** runs on your computer and drives Claude Code through the Claude Agent SDK, signed in with your own Claude account. The **Android app** talks to the bridge over **Tailscale**, a private network between your devices, so nothing is exposed to the internet.

```
phone app  ──Tailscale (encrypted)──▶  bridge on your computer  ──▶  Claude Code
```

## What you need

| Where | What |
|---|---|
| Computer (Windows, macOS or Linux) | [Node.js](https://nodejs.org) 20 or newer |
| Computer | [Claude Code](https://docs.claude.com/en/docs/claude-code), signed in with a Claude plan that includes Claude Code (Pro or Max) |
| Computer **and** phone | [Tailscale](https://tailscale.com/download), signed in to the **same** account on both |
| Phone | Android 12 or newer |
| To build the app | Android Studio, or a JDK 17 plus the Android SDK (see [docs/BUILD.md](docs/BUILD.md)) |

## Setup

### 1. Tailscale on both devices

1. Install Tailscale on your computer and on your phone.
2. Sign in to the same account on both, and make sure both show as connected.
3. On the computer, note its Tailscale IP. It starts with `100.` (Tailscale app → your computer, or run `tailscale ip -4`).

### 2. Claude Code on the computer

1. Install Claude Code: `npm install -g @anthropic-ai/claude-code`
2. Run `claude` once in a terminal and sign in. The bridge uses this login.

### 3. Start the bridge on the computer

```sh
git clone <this repo>
cd claude-remote-access/server
npm install
npm start
```

It prints `claude-remote server on 100.x.y.z:8000`. Leave it running.

- **Working folder:** by default Claude Code works in your home folder. To use a different one, set `WORK_DIR` before starting, e.g. `WORK_DIR=/path/to/project npm start` (on Windows PowerShell: `$env:WORK_DIR="D:\path\to\project"; npm start`).
- **It only listens on your Tailscale address**, so devices on your Wi-Fi can't reach it. If Tailscale isn't running, it refuses to start.
- More options: [docs/SERVER.md](docs/SERVER.md).

### 4. Install the app on the phone

Build and install it as described in [docs/BUILD.md](docs/BUILD.md).

### 5. Connect the app

1. Open **Claude Remote**. On first launch it opens Settings.
2. Under **Bridge address**, enter the computer's Tailscale IP from step 1, e.g. `100.101.102.103` (port 8000 is assumed).
3. Tap **Save**, then send a message.

If it says it can't reach the bridge, check that Tailscale is on on both devices and that the bridge is still running.

## Important: what the bridge can do

- **Permissions are off for now.** Claude Code runs without asking before it edits files or runs commands, the same as `claude --dangerously-skip-permissions`. Approving actions from the phone is planned.
- **Anyone on your tailnet can use it.** The bridge has no login of its own; Tailscale is what keeps others out. Don't share your tailnet with people you wouldn't give your computer to, or restrict port 8000 with [Tailscale ACLs](https://tailscale.com/kb/1018/acls).
- **It uses your Claude plan's usage limits**, shared with any other Claude Code use on your account.

## Layout

| Path | What |
|---|---|
| `server/` | The bridge (Node, Claude Agent SDK) |
| `android/` | The Android app (Kotlin, Jetpack Compose) |
| `docs/SERVER.md` | Bridge options, protocol and internals |
| `docs/BUILD.md` | Building the app and installing it on a phone |

## Status

Works: one Claude Code session per chat, streamed to the phone, with a line per tool call; chat history; model and effort choice; stop.

Not built yet: picking the folder from the phone, more than one computer, approving permissions on the phone.
