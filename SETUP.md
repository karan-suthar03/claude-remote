# Setting up Claude Remote (guide for Claude Code and other agents)

This file is written for an AI coding agent (such as Claude Code) that a user has asked to set up
Claude Remote on their computer. A person can follow it too. Do the steps in order, check each
one, and stop and explain clearly if a check fails. Ask the user only for things you can't do
yourself (signing in to Tailscale, tapping things on their phone).

**What gets set up:** a small "bridge" on this computer that runs Claude Code sessions, reached by
the Claude Remote Android app over Tailscale. Nothing is exposed to the internet.

Repository: https://github.com/karan-suthar03/claude-remote

---

## 1. Check the prerequisites

| Check | Command | Expected |
|---|---|---|
| Node.js 20+ | `node --version` | `v20` or newer |
| Tailscale installed and connected | `tailscale ip -4` (Windows: `"C:\Program Files\Tailscale\tailscale.exe" ip -4`; macOS app: `/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4`) | an address starting with `100.` |

- **Node missing or old:** install Node.js LTS (Windows: `winget install OpenJS.NodeJS.LTS`; macOS: `brew install node`; Linux: the distro's package or https://nodejs.org). Then re-check.
- **Tailscale missing or not connected:** the user must install it from https://tailscale.com/download and sign in (this needs them; you can open the page for them). The **same Tailscale account** must be signed in on their phone.
- Claude Code itself does **not** need to be installed separately: the bridge's dependencies include it.

## 2. Install the bridge

The same steps work on a desktop, a laptop or a **remote Linux server** (VPS, cloud VM) reached
over SSH. On a server without Tailscale: `curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up`
(the user must approve the login link it prints). Each machine becomes its own device in the app.

Run the installer for the OS. It downloads the bridge, installs its dependencies, starts it, and
makes it start automatically at login. Running it again updates it.

- **Windows (PowerShell):**
  ```powershell
  irm https://raw.githubusercontent.com/karan-suthar03/claude-remote/main/install.ps1 | iex
  ```
- **macOS / Linux:**
  ```sh
  curl -fsSL https://raw.githubusercontent.com/karan-suthar03/claude-remote/main/install.sh | sh
  ```

If you're working inside a clone of this repository, you can instead run `npm install` in `server/`
and then the autostart script for the OS in `server/scripts/` (see the README).

## 3. Verify the bridge

```sh
curl http://<tailscale-ip>:8000/
```

Expected: JSON like `{"service":"claude-remote","name":"<computer name>","status":"ok",...}`.

- Windows log: `%LOCALAPPDATA%\claude-remote\server\bridge.log`
- macOS log: `~/.claude-remote/server/bridge.log`
- Linux log: `journalctl --user -u claude-remote -n 50`

The log's first lines say the address and whether Claude Code is signed in. If it says
**NOT signed in**, that's fine: the user can sign in from the app (step 5). Alternatively run
`claude` once in a terminal (if installed) and use `/login`.

Common failures (more in [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)):
- `No Tailscale address found` → Tailscale isn't connected on this computer.
- `Port 8000 is already in use` → another bridge or program uses it; stop it, or set `PORT` and use `<ip>:<port>` in the app.
- `curl` from this computer works but the phone can't connect → Tailscale is off on the phone, or the phone is signed in to a different Tailscale account, or a firewall blocks the Tailscale interface.

## 4. Install the app on the phone

The user opens this link **on their Android phone** (Android 8+) and installs the APK, allowing
installs from the browser when asked:

https://github.com/karan-suthar03/claude-remote/releases/latest/download/claude-remote.apk

Don't try to install the app on the phone yourself; the user installs it from that link.

## 5. Connect

Tell the user, in plain words:

1. Turn Tailscale on on the phone (same account as the computer).
2. Open **Claude Remote** → **Add device** → enter `<tailscale-ip>` (the 100.x.y.z address from step 1).
3. If the device screen says Claude Code isn't signed in, tap **Sign in**, open the link, sign in,
   and paste the code shown back into the app.
4. Tap **New session**, pick a folder, and send a message.

## 6. Tell the user what matters

- Claude Code started from the phone runs **without permission prompts** (like
  `--dangerously-skip-permissions`). Anyone on their tailnet who can reach port 8000 can use it,
  so their tailnet should contain only their own devices, or they should restrict port 8000 with
  Tailscale ACLs ([docs/SECURITY.md](docs/SECURITY.md)).
- Sessions keep running on the computer when the phone disconnects, and sessions they run in a
  terminal on the computer show up in the app live.
- Uninstall: Windows `powershell -File "$env:LOCALAPPDATA\claude-remote\server\scripts\install-windows.ps1" -Remove`;
  macOS/Linux `sh ~/.claude-remote/server/scripts/install-macos.sh --remove` (or `install-linux.sh`).
