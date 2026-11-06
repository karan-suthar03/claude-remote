#!/usr/bin/env sh
# Starts the Claude Remote bridge automatically (systemd user service).
#   sh scripts/install-linux.sh            install and start
#   sh scripts/install-linux.sh --remove   uninstall
# Optional env: WORK_DIR (default folder for Claude Code), PORT (default 8000).
# Logs: journalctl --user -u claude-remote -f
set -eu
SERVER=$(cd "$(dirname "$0")/.." && pwd)
UNIT="$HOME/.config/systemd/user/claude-remote.service"

if [ "${1:-}" = "--remove" ]; then
  systemctl --user disable --now claude-remote 2>/dev/null || true
  rm -f "$UNIT"; systemctl --user daemon-reload
  echo "Removed claude-remote."; exit 0
fi

NODE=$(command -v node) || { echo "Node.js was not found on PATH. Install Node.js 20 or newer first."; exit 1; }
[ -d "$SERVER/node_modules" ] || (cd "$SERVER" && npm install --omit=dev)

mkdir -p "$(dirname "$UNIT")"
cat > "$UNIT" <<EOF
[Unit]
Description=Claude Remote bridge
After=network-online.target tailscaled.service

[Service]
WorkingDirectory=$SERVER
Environment=PORT=${PORT:-8000}
${WORK_DIR:+Environment=WORK_DIR=$WORK_DIR}
Environment=PATH=$(dirname "$NODE"):/usr/local/bin:/usr/bin:/bin
ExecStart=$NODE index.mjs
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now claude-remote
# Keep it running when you're logged out (needs no root on most systems; ignore if it fails).
loginctl enable-linger "$USER" 2>/dev/null || true
echo "Installed claude-remote. Logs: journalctl --user -u claude-remote -f"
