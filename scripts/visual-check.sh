#!/usr/bin/env bash
# Visual check with a real client: starts a dev server and a dev client that joins it on a private
# X display, runs console commands, and saves screenshots.
#
#   scripts/visual-check.sh <minecraft-version> <commands-file> [output-dir]
#
# A commands file has one entry per line: a console command, "sleep <seconds>", or "shot <name>".
# The client joins as "Viewer". Needs Xvfb and ImageMagick's import.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=$1; CMDS=$2; OUT=${3:-$ROOT/build/visual-check/$V}
DISP=${DISP:-:97}
PORT=$((25700 + RANDOM % 200))
mkdir -p "$OUT" "$ROOT/run/$V/server" "$ROOT/run/$V/client"
echo "eula=true" > "$ROOT/run/$V/server/eula.txt"
# Every check starts from a new world, so nothing an earlier check left behind is in the pictures.
rm -rf "$ROOT/run/$V/server/world"
# 26.3 servers default to a whitelist; turn it off so the test client can join.
printf 'level-type=minecraft\\:flat\nonline-mode=false\ngamemode=creative\nspawn-protection=0\nserver-port=%s\nview-distance=8\nwhite-list=false\nenforce-whitelist=false\n' "$PORT" > "$ROOT/run/$V/server/server.properties"
printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\njoinedFirstServer:true\ntutorialStep:none\npauseOnLostFocus:false\nnarrator:0\nrenderDistance:6\nmaxFps:30\nguiScale:2\nfullscreen:false\nsoundCategory_master:0.0\n' > "$ROOT/run/$V/client/options.txt"
Xvfb "$DISP" -screen 0 1280x720x24 >"$OUT/xvfb.log" 2>&1 &
XP=$!
FIFO="$OUT/stdin.fifo"; rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<>"$FIFO"
SLOG="$OUT/server.log"; CLOG="$OUT/client.log"; : > "$SLOG"; : > "$CLOG"
"$ROOT/gradlew" --project-dir "$ROOT" --no-daemon ":$V:runServer" -PmixinAudit --args="nogui" <&3 >"$SLOG" 2>&1 &
SP=$!; CP=
say() { echo "$1" >&3; }
waitfor() { local t=0; until grep -qE "$2" "$1"; do sleep 1; t=$((t+1)); if [ "$t" -ge "$3" ]; then return 1; fi; done; return 0; }
cleanup() { say "stop"; sleep 5; kill $CP "$SP" 2>/dev/null; kill "$XP" 2>/dev/null; }
if ! waitfor "$SLOG" 'Done \([0-9.]+s\)!' 300; then echo "RESULT server_boot=FAIL"; tail -20 "$SLOG"; kill "$SP" "$XP"; exit 1; fi
echo "RESULT server_boot=OK port=$PORT"
# A flat world breeds slimes, and one of them kills a player who joins in survival before the first
# command runs. Mobs are removed and kept out before the client joins.
say "gamerule spawn_mobs false"; say "kill @e[type=!player]"; say "gamerule immediate_respawn true"
# The private display has no GPU: use Mesa's software renderer. From 26.3 the client's window comes
# from SDL, which prefers a Wayland session if there is one, so X11 is forced.
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 SDL_VIDEODRIVER=x11 DISPLAY="$DISP" LIBGL_ALWAYS_SOFTWARE=1 \
  __GLX_VENDOR_LIBRARY_NAME=mesa __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json \
  "$ROOT/gradlew" --project-dir "$ROOT" --no-daemon ":$V:runClient" --args="--quickPlayMultiplayer localhost:$PORT --username Viewer" >"$CLOG" 2>&1 &
CP=$!
if ! waitfor "$SLOG" 'Viewer joined the game' 420; then
  echo "RESULT client_join=FAIL"; grep -nE 'rror|xception|FAILED' "$CLOG" | head -20
  DISPLAY="$DISP" import -window root "$OUT/fail.png" 2>/dev/null; cleanup; exit 1
fi
echo "RESULT client_join=OK"
sleep 8
while IFS= read -r line; do
  case "$line" in
    "sleep "*) sleep "${line#sleep }" ;;
    "shot "*) DISPLAY="$DISP" import -window root -crop 854x480+213+120 +repage "$OUT/${line#shot }.png" && echo "SHOT $OUT/${line#shot }.png" ;;
    ""|"#"*) ;;
    *) say "$line" ;;
  esac
done < "$CMDS"
echo "--- client errors ---"; grep -nE 'MixinApplyError|InvalidInjection|Critical injection|Exception in|Crash' "$CLOG" | head -10
cleanup
