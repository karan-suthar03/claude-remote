# Claude Remote

Use Claude Code on your own computers from an Android phone, over Tailscale.

- `server/` — the bridge (Node, Claude Agent SDK). Protocol and options: `docs/SERVER.md`.
- `android/` — the app (Kotlin, Jetpack Compose). Build: `docs/BUILD.md`.
- `install.ps1`, `install.sh` — one-line installers for the bridge.

## If the user asks you to set it up

Follow [SETUP.md](SETUP.md) step by step: check Node 20+ and Tailscale, run the installer, verify
`http://<tailscale-ip>:8000/`, help them install the app, and tell them the address to enter.

## Working on the code

- The bridge's header comment in `server/index.mjs` is the protocol reference; keep it, the app's
  `data/ChatModel.kt` reducer and `docs/SERVER.md` in sync when changing events or requests.
- The phone stores no session state: the bridge is the source of truth. Don't add caching of
  sessions or chats on the phone.
- Nothing personal may be hardcoded (IPs, names, accounts); users enter their own.
- Test the bridge end to end: `node server/test-client.mjs ws://<tailscale-ip>:8000/ws`.
- Build the app: `cd android && ./gradlew assembleRelease` (JDK 17, Android SDK 36).
