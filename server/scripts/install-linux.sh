#!/usr/bin/env sh
# Starts the Claude Remote bridge automatically on Linux, including headless servers (VPS, cloud).
#   sh scripts/install-linux.sh            install and start
#   sh scripts/install-linux.sh --remove   uninstall
# Optional env: WORK_DIR (default folder for Claude Code), PORT (default 8000).
#
# Picks the first that works:
#   1. systemd user service (desktop or SSH login with a user session), kept running after
#      logout with lingering.           Logs: journalctl --user -u claude-remote -f
#   2. systemd system service running as you (root, or a server without a user session).
#                                       Logs: journalctl -u claude-remote -f
#   3. a background process (no systemd, e.g. containers); not restarted after a reboot.
#                                       Logs: server/bridge.log
set -eu
SERVER=$(cd "$(dirname "$0")/.." && pwd)
USER_UNIT="$HOME/.config/systemd/user/claude-remote.service"
SYS_UNIT=/etc/systemd/system/claude-remote.service
ME=$(id -un)
SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO=sudo

user_systemd() { command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; }
system_systemd() { command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ] && { [ "$(id -u)" -eq 0 ] || [ -n "$SUDO" ]; }; }

if [ "${1:-}" = "--remove" ]; then
  if [ -f "$USER_UNIT" ]; then systemctl --user disable --now claude-remote 2>/dev/null || true; rm -f "$USER_UNIT"; systemctl --user daemon-reload 2>/dev/null || true; fi
  if [ -f "$SYS_UNIT" ]; then $SUDO systemctl disable --now claude-remote 2>/dev/null || true; $SUDO rm -f "$SYS_UNIT"; $SUDO systemctl daemon-reload; fi
  pkill -f "$SERVER/index.mjs" 2>/dev/null || true
  echo "Removed claude-remote."; exit 0
fi

NODE=$(command -v node) || { echo "Node.js was not found on PATH. Install Node.js 20 or newer first."; exit 1; }
[ -d "$SERVER/node_modules" ] || (cd "$SERVER" && npm install --omit=dev)

unit_body() {
  cat <<EOF
[Unit]
Description=Claude Remote bridge
After=network-online.target tailscaled.service
Wants=network-online.target

[Service]
WorkingDirectory=$SERVER
Environment=HOME=$HOME
Environment=PORT=${PORT:-8000}
${WORK_DIR:+Environment=WORK_DIR=$WORK_DIR}
Environment=PATH=$(dirname "$NODE"):/usr/local/bin:/usr/bin:/bin
ExecStart=$NODE index.mjs
Restart=always
RestartSec=5
EOF
}

if user_systemd; then
  mkdir -p "$(dirname "$USER_UNIT")"
  { unit_body; printf '\n[Install]\nWantedBy=default.target\n'; } > "$USER_UNIT"
  systemctl --user daemon-reload
  systemctl --user enable claude-remote >/dev/null
  systemctl --user restart claude-remote
  # Keep it running when you're logged out, and start it at boot.
  loginctl enable-linger "$ME" 2>/dev/null || $SUDO loginctl enable-linger "$ME" 2>/dev/null || true
  echo "Installed claude-remote (systemd user service). Logs: journalctl --user -u claude-remote -f"
elif system_systemd; then
  { unit_body | sed "s|^\[Service\]|[Service]\nUser=$ME|"; printf '\n[Install]\nWantedBy=multi-user.target\n'; } | $SUDO tee "$SYS_UNIT" >/dev/null
  $SUDO systemctl daemon-reload
  $SUDO systemctl enable claude-remote >/dev/null
  $SUDO systemctl restart claude-remote
  echo "Installed claude-remote (systemd service, runs as $ME). Logs: journalctl -u claude-remote -f"
else
  pkill -f "$SERVER/index.mjs" 2>/dev/null || true
  (cd "$SERVER" && PORT=${PORT:-8000} ${WORK_DIR:+WORK_DIR="$WORK_DIR"} nohup "$NODE" "$SERVER/index.mjs" >> "$SERVER/bridge.log" 2>&1 &)
  echo "Started claude-remote in the background (no systemd here, so it won't restart after a reboot)."
  echo "Logs: $SERVER/bridge.log   Start again: sh $0"
fi
