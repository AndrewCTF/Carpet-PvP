#!/usr/bin/env bash
# Looks at the CarpetLogic web editor in a real browser: starts a dev server on ports of its own, makes a
# console link, an admin account with a set-password link and two bots, and has headless Chrome take the
# pictures a steps file asks for.
#
#   scripts/logic-ui-check.sh <minecraft-version> [output-dir] [steps.mjs]
#
# HOLD=1 takes no pictures and keeps the server up until <output-dir>/stop exists, so the steps can be run by
# hand against it, again and again: the server's address and tokens are in <output-dir>/session.env, and a
# line written to <output-dir>/stdin.fifo is typed at its console.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=${1:?usage: logic-ui-check.sh <minecraft-version> [output-dir] [steps.mjs]}
OUT=${2:-$ROOT/build/logic-ui-check/$V}
STEPS=${3:-$ROOT/scripts/logic-ui-steps.mjs}
ADMIN=${ADMIN:-steve}

free_port() {
  local port
  while :; do
    port=$(( $1 + RANDOM % 400 ))
    ss -ltn | grep -q ":$port " || { echo "$port"; return; }
  done
}
GAME_PORT=$(free_port 25700)
LOGIC_PORT=$(free_port 9900)

mkdir -p "$OUT" "$ROOT/run/$V/server"
rm -f "$OUT/stop" "$OUT/session.env"
echo "eula=true" > "$ROOT/run/$V/server/eula.txt"
printf 'level-type=minecraft\\:flat\nonline-mode=false\nserver-port=%s\nwhite-list=false\nenforce-whitelist=false\n' \
  "$GAME_PORT" > "$ROOT/run/$V/server/server.properties"
FIFO="$OUT/stdin.fifo"; rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<>"$FIFO"
LOG="$OUT/server.log"; : > "$LOG"
# carpet.logicPort wins over the carpetLogicPort rule, so this run cannot meet another server's editor.
JAVA_TOOL_OPTIONS="-Dcarpet.logicPort=$LOGIC_PORT" \
  "$ROOT/gradlew" --project-dir "$ROOT" --no-daemon ":$V:runServer" --args=nogui <&3 >"$LOG" 2>&1 &
GRADLE=$!

say() { echo "$1" >&3; }
waitfor() { local t=0; until grep -qE "$1" "$LOG"; do sleep 1; t=$((t+1)); if [ "$t" -ge "$2" ]; then return 1; fi; done; return 0; }
# Whatever still listens on the two ports this run picked is the server it started, and nothing else is touched.
holders() { ss -ltnpH "sport = :$GAME_PORT or sport = :$LOGIC_PORT" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u; }
stop_server() {
  say "stop"
  local t=0
  while kill -0 "$GRADLE" 2>/dev/null && [ "$t" -lt 90 ]; do sleep 1; t=$((t+1)); done
  for pid in $(holders); do echo "the server did not stop by itself; ending process $pid"; kill "$pid" 2>/dev/null; done
  kill "$GRADLE" 2>/dev/null
  exec 3>&-; rm -f "$FIFO"
}

if ! waitfor 'Done \([0-9.]+s\)!' 600; then
  echo "RESULT boot=FAIL"; tail -30 "$LOG"; stop_server; exit 1
fi
URL=$(grep -oE 'web editor listening on http://[^ ]+' "$LOG" | tail -1 | sed -e 's/.*listening on //' -e 's/\.$//')
[ -n "$URL" ] || { echo "RESULT editor=FAIL the web editor is not listening"; grep -n 'CarpetLogic' "$LOG" | tail -5; stop_server; exit 1; }
echo "RESULT boot=OK editor=$URL"

say "gamerule spawn_mobs false"; say "kill @e[type=!player]"
say "carpetlogic open"
waitfor '#token=[A-Za-z0-9_-]+' 20
TOKEN=$(grep -oE '#token=[A-Za-z0-9_-]+' "$LOG" | tail -1 | cut -d= -f2)
[ -n "$TOKEN" ] || { echo "RESULT token=FAIL"; stop_server; exit 1; }

# An account that may change rules, and the link that sets its web password.
say "carpet carpetLogicAdminLogin true"; say "op $ADMIN"; sleep 1
say "carpetlogic password $ADMIN"
waitfor '#setup=[A-Za-z0-9_-]+' 10
SETUP=$(grep -oE '#setup=[A-Za-z0-9_&=-]+' "$LOG" | tail -1)

api() { curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$URL$1"; }
echo "spawn: $(api /api/bots/spawn '{"name":"Striker","x":0.5,"y":-60,"z":0.5}' | cut -c1-120)"
echo "spawn: $(api /api/bots/spawn '{"name":"Dummy","x":0.5,"y":-60,"z":4.5}' | cut -c1-120)"
sleep 3

cat > "$OUT/session.env" <<EOF
LOGIC_URL=$URL
LOGIC_TOKEN=$TOKEN
LOGIC_ADMIN=$ADMIN
LOGIC_SETUP=$SETUP
EOF
echo "RESULT session=$OUT/session.env"

shots() { env $(cat "$OUT/session.env") LOGIC_PHASE="$1" node "$ROOT/scripts/logic-ui-shots.mjs" "$STEPS" "$OUT"; }
STATUS=0
if [ "${HOLD:-0}" = "1" ]; then
  echo "RESULT holding: touch $OUT/stop to end"
  until [ -e "$OUT/stop" ]; do sleep 1; done
else
  shots signin || STATUS=1
  # What a page without a session shows once the sign-in is off again.
  say "carpet carpetLogicAdminLogin false"; sleep 1
  shots closed || STATUS=1
fi
say "deop $ADMIN"; say "carpet carpetLogicAdminLogin false"
stop_server
echo "RESULT shots=$([ "$STATUS" = 0 ] && echo OK || echo FAIL)"
exit "$STATUS"
