#!/usr/bin/env sh
# Starts the Claude Remote bridge automatically at login (launchd agent).
#   sh scripts/install-macos.sh            install and start
#   sh scripts/install-macos.sh --remove   uninstall
# Optional env: WORK_DIR (default folder for Claude Code), PORT (default 8000).
# Logs: server/bridge.log
set -eu
SERVER=$(cd "$(dirname "$0")/.." && pwd)
LABEL=com.claude-remote.bridge
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

if [ "${1:-}" = "--remove" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"; echo "Removed $LABEL."; exit 0
fi

NODE=$(command -v node) || { echo "Node.js was not found on PATH. Install Node.js 20 or newer first."; exit 1; }
[ -d "$SERVER/node_modules" ] || (cd "$SERVER" && npm install --omit=dev)

mkdir -p "$(dirname "$PLIST")"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$NODE</string><string>index.mjs</string></array>
  <key>WorkingDirectory</key><string>$SERVER</string>
  <key>EnvironmentVariables</key><dict>
    <key>PORT</key><string>${PORT:-8000}</string>
    <key>WORK_DIR</key><string>${WORK_DIR:-$HOME}</string>
    <key>PATH</key><string>$(dirname "$NODE"):/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$SERVER/bridge.log</string>
  <key>StandardErrorPath</key><string>$SERVER/bridge.log</string>
</dict></plist>
EOF
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Installed $LABEL. Logs: $SERVER/bridge.log"
