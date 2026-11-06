# Troubleshooting

Start with the bridge's log. Where it is depends on how the bridge runs; see [SERVER.md › Logs](SERVER.md#logs). The health check is a quick way to test reachability from any device in your tailnet:

```sh
curl http://100.x.y.z:8000/
```

## The app says "Can't reach …. Is Tailscale on and the bridge running?"

Work through these in order:

1. **Tailscale is connected on the phone.** Open the Tailscale app. It must say *Connected*, not paused. Some VPN apps or battery savers disconnect it. Only one VPN can be active on Android at a time.
2. **Tailscale is connected on the computer**, on the same tailnet. Run `tailscale status`. Both devices should be listed.
3. **The address is right.** Run `tailscale ip -4` on the computer. Its Tailscale IP can change if the machine was removed from the tailnet and added again. To fix it, remove the device in the app's drawer and add it again with the new IP. If you changed `PORT`, enter `100.x.y.z:<port>`.
4. **The bridge is running.** Look for `claude-remote bridge "…" on 100.x.y.z:8000` in its log. With autostart:
   - Windows: Task Scheduler → **Claude Remote bridge** → Status / Last Run Result. Start it with `Start-ScheduledTask -TaskName "Claude Remote bridge"`.
   - macOS: `launchctl print gui/$(id -u)/com.claude-remote.bridge`
   - Linux: `systemctl --user status claude-remote`
5. **The bridge is listening on the current Tailscale IP.** The bridge picks the address once, at startup. If it started before Tailscale came up, or the IP changed, restart the bridge.
6. **Tailscale ACLs allow it.** If you restricted port 8000 (see [SECURITY.md](SECURITY.md)), check that the phone matches the rule's `src`. Run `tailscale ping 100.x.y.z` from another device to separate ACL problems from connection problems.
7. **The firewall allows it.** See [Windows firewall](#windows-firewall) below.

The app retries by itself while a chat is open, and reconnects when you bring it back to the front.

## The bridge won't start

**`No Tailscale address found on this machine.`**
Tailscale isn't running or isn't signed in, so the computer has no `100.x.y.z` address. Start Tailscale and sign in, then start the bridge again. With autostart:

- Linux: the systemd unit waits for `tailscaled.service` and restarts every 5 s until the address exists.
- macOS: launchd keeps restarting it.
- Windows: the scheduled task retries every minute. If it has used up its retries, run `Start-ScheduledTask -TaskName "Claude Remote bridge"`.

If your Tailscale address isn't in `100.64.0.0/10` (rare, custom setups), set `HOST` to it explicitly.

**`Port 8000 is already in use. Is another bridge running?`**
Another bridge is probably already running, for example the autostart one while you also ran `npm start`. Stop one of them. To see what holds the port:

- Windows: `Get-NetTCPConnection -LocalPort 8000 | Select OwningProcess`, then `Get-Process -Id <pid>`
- macOS / Linux: `lsof -i :8000`

To use another port, set `PORT` (and enter `100.x.y.z:<port>` in the app). For autostart, reinstall with `-Port` on Windows or `PORT=` on macOS and Linux.

**`Node.js was not found on PATH`** (install scripts)
Install Node.js 20 or newer and open a new terminal so `PATH` is updated. The scripts store the full path to `node`, so if you upgrade or move Node later, run the install script again.

**Errors about `@anthropic-ai/claude-agent-sdk` or `ws`**
Run `npm install` in `server/`. If you moved the folder between operating systems or CPU architectures, delete `node_modules` first. The SDK installs a platform-specific Claude Code binary.

## Sign-in problems

- **"Claude Code did not show a sign-in link."** `claude auth login` didn't print a URL within 20 seconds. Check that the computer can reach the internet and that `npm install` completed in `server/`. Then try again. If it keeps failing, sign in on the computer instead: run `claude`, then `/login`.
- **The code is rejected, or "Sign-in timed out".** Codes are single-use and short-lived. Tap **Cancel**, start again, and paste the code from the new page straight away. Copy the whole code and nothing else.
- **You signed in with the wrong option.** *Claude subscription* is for Pro, Max, Team and Enterprise plans. *Anthropic Console* is for API billing. Sign out from the device screen and sign in again with the other option.
- **Still signed out after signing in.** Sessions that were running during the sign-in keep the old login until their process restarts. Close and resume them, or wait for the idle stop.
- **"Sign-in problem: … Sign in again from the device screen."** in a chat means the login expired or was revoked. Sign in again from the device screen, or run `claude` then `/login` on the computer.
- **Sign-in works in the terminal but the app says signed out, or the other way round.** The bridge uses the Claude Code binary bundled with the SDK. Both should share `~/.claude`, but only if they run as the same OS user. Check that the autostart service runs as your user, not a system account.

## Sessions are missing from the list

- **Closed sessions** are Claude Code's saved transcripts for the user the bridge runs as. Sessions from another OS user or another machine don't appear. They're also limited to the newest `CLOSED_LIMIT` (default 50).
- **A session doesn't appear right after it starts.** A new session only gets its Claude Code session id after its first message. It's listed under *Open* until then.
- **An open session disappeared.** It was closed by the TTL (24 hours without activity by default), from another phone, or by a bridge restart. Look for it under *Closed* and tap it to resume.
- **"This session is no longer open."** Same as above. Go back to the device screen and resume it from *Closed*.
- **"This session can't be resumed."** The saved entry has no session id. Start a new session in the same folder.
- **Error messages on the device screen** mean the bridge couldn't be reached or failed. The app doesn't show stale lists. Use **Retry** after fixing the cause.

## Windows firewall

Windows Defender Firewall can block inbound connections to `node.exe` even over Tailscale.

- The Tailscale adapter is usually classed as a **Private** network, so allowing Node.js on *Private* networks is enough. If Windows asked "Allow Node.js to communicate on these networks?" the first time the bridge started and you clicked **Cancel**, it's blocked.
- To check: Windows Security → Firewall & network protection → **Allow an app through firewall** → *Node.js JavaScript Runtime* must be ticked for **Private**.
- To check the adapter's category: `Get-NetConnectionProfile`. The Tailscale interface should show `NetworkCategory : Private`.
- Or add a narrow rule (as Administrator):
  ```powershell
  New-NetFirewallRule -DisplayName "Claude Remote bridge" -Direction Inbound -Protocol TCP -LocalPort 8000 -RemoteAddress 100.64.0.0/10 -Action Allow
  ```

The bridge only listens on the Tailscale IP, so allowing the port on that interface doesn't expose it to your LAN.

## Usage isn't shown

The usage card appears only when Claude Code reports plan limits:

- **API-key or Anthropic Console logins** have no plan limits (they pay per token), so there's nothing to show. The cost figure in each chat still works.
- **`ANTHROPIC_API_KEY` set in the bridge's environment** makes Claude Code use the key instead of your subscription. Remove it from the environment the bridge starts in. For autostart, that's your user environment.
- **Usage is fetched through an experimental SDK call** that may change between SDK versions. If it fails, the card is simply hidden and nothing else is affected.
- It refreshes once a minute while the device screen is visible.

## Installing the app fails

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE` or "App not installed as package conflicts with an existing package."** Builds are signed with the debug key of the computer that built them, so an APK from one computer can't be installed over one built on another. Uninstall the existing app first (`adb uninstall me.karansuthar.clauderemote`) and install again. Your device list is lost. Sessions are not, because they live on the computers.
- **`INSTALL_FAILED_OLDER_SDK`.** The phone runs Android older than 12, which the app doesn't support.
- **"Blocked by Play Protect" or "unknown sources".** Allow installs from the source you are using (the file manager or browser), or install with `adb`.
- **`adb` shows the phone as `unauthorized` or `offline`.** Accept the prompt on the phone. Then run `adb kill-server` and connect again. Wireless debugging details are in [BUILD.md](BUILD.md).

## Other

- **A reply stops with a note like "Usage limit reached …".** Your plan's limit was hit. The note shows when it resets. Usage is shared with all other Claude Code use on the account.
- **Claude asks a question in text and waits.** That's expected: `AskUserQuestion` is disabled, so answer in the chat.
- **"Claude Code stopped: …" note.** The session's process crashed. Send another message to start a new one that resumes the session. If it keeps happening, check the bridge log and run `claude` in that folder on the computer to see the error directly.
- **A session feels slow on its first message after a while.** Its process was stopped after `IDLE_PROCESS_MS` (30 min by default) and is starting again. Raise the value if you want it to stay warm longer.
