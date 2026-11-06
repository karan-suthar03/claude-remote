#!/usr/bin/env sh
# Claude Remote bridge installer for macOS and Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/karan-suthar03/claude-remote/main/install.sh | sh
#
# Installs the bridge into ~/.claude-remote, starts it now and at login, and prints the
# address to enter in the Claude Remote app. Run it again to update.
# Uninstall: sh ~/.claude-remote/server/scripts/install-linux.sh --remove   (or install-macos.sh)
set -eu

REPO=karan-suthar03/claude-remote
BRANCH=main
DIR="$HOME/.claude-remote"

step() { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$1"; exit 1; }

echo "Claude Remote: use Claude Code on this computer from your phone"

step "Checking Node.js"
command -v node >/dev/null 2>&1 || fail "Node.js 20 or newer is required. Install it from https://nodejs.org (or your package manager) and run this again."
MAJOR=$(node -p 'process.versions.node.split(".")[0]')
[ "$MAJOR" -ge 20 ] || fail "Node.js $(node --version) is too old; 20 or newer is required."
echo "Node.js $(node --version)"

step "Checking Tailscale"
TS=$(command -v tailscale || true)
[ -z "$TS" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ] && TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
[ -n "$TS" ] || fail "Tailscale is required (it's how your phone reaches this computer privately).
Install it from https://tailscale.com/download, sign in, then run this again."
IP=$("$TS" ip -4 2>/dev/null | head -n1 || true)
[ -n "$IP" ] || fail "Tailscale is installed but not connected. Sign in to Tailscale, then run this again."
echo "Tailscale IP $IP"

step "Downloading Claude Remote ($REPO@$BRANCH)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "https://github.com/$REPO/archive/refs/heads/$BRANCH.tar.gz" | tar -xz -C "$TMP"
SRC=$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -n1)
mkdir -p "$DIR"
rm -rf "$DIR/server"
cp -R "$SRC/server" "$DIR/server"

step "Installing dependencies (this includes Claude Code itself)"
(cd "$DIR/server" && npm install --omit=dev --no-audit --no-fund --loglevel=error) || fail "npm install failed. See the messages above."

step "Starting the bridge (and at every login)"
case "$(uname -s)" in
  Darwin) sh "$DIR/server/scripts/install-macos.sh" ;;
  *)      sh "$DIR/server/scripts/install-linux.sh" ;;
esac

OK=""
i=0
while [ $i -lt 20 ]; do
  if curl -fs -m 2 "http://$IP:8000/" >/dev/null 2>&1; then OK=1; break; fi
  sleep 1; i=$((i + 1))
done
echo
if [ -n "$OK" ]; then printf '  \033[32mClaude Remote is running.\033[0m\n'; else printf "  \033[33mThe bridge didn't answer yet; check its log.\033[0m\n"; fi
echo
echo '  In the Claude Remote app, tap "Add device" and enter:'
echo
printf '        \033[33m%s\033[0m\n' "$IP"
echo
echo "  Get the app: https://github.com/$REPO/releases/latest/download/claude-remote.apk"
echo "  Not signed in to Claude yet? Sign in from the app (device screen > Sign in)."
echo
