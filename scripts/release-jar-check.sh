#!/usr/bin/env bash
# Spawns a bot on the server a player runs: the built mod jar on a stock Fabric server, not a dev run.
# A dev run has Minecraft under its readable names, the released 1.21.11 jar does not, so a lookup by
# name passes every dev test and fails for every player. Both online modes are booted: with
# online-mode=false the bot is placed at once, with online-mode=true (the default, and singleplayer)
# after its profile has been looked up.
#
#   scripts/release-jar-check.sh <minecraft-version> [mod-jar]
#
# Build the jar first: ./gradlew :<minecraft-version>:build
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=${1:?usage: release-jar-check.sh <minecraft-version> [mod-jar]}
prop() { grep -E "^\s*$1\s*=" "$2" | sed 's/.*=\s*//' | tr -d '[:space:]'; }
JAR=${2:-$ROOT/versions/$V/build/libs/carpet-pvp-$V-$(prop mod_version "$ROOT/gradle.properties").jar}
[ -f "$JAR" ] || { echo "RESULT $V FAIL no mod jar at $JAR"; exit 1; }
LOADER=$(prop loader_version "$ROOT/gradle.properties")
API=$(prop fabric_version "$ROOT/versions/$V/gradle.properties")
WORK=$ROOT/build/release-jar-check/$V
mkdir -p "$WORK"
fetch() { [ -s "$2" ] || curl -sfL --retry 3 -o "$2" "$1" || { echo "RESULT $V FAIL could not download $1"; exit 1; }; }
fetch "https://meta.fabricmc.net/v2/versions/loader/$V/$LOADER/1.1.0/server/jar" "$WORK/launch.jar"
fetch "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$API/fabric-api-$API.jar" "$WORK/fabric-api.jar"

STATUS=0
for ONLINE in false true; do
  DIR=$WORK/online-$ONLINE
  rm -rf "$DIR"; mkdir -p "$DIR/mods"
  cp "$JAR" "$WORK/fabric-api.jar" "$DIR/mods/"
  echo "eula=true" > "$DIR/eula.txt"
  printf 'server-port=0\nonline-mode=%s\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nwhite-list=false\n' "$ONLINE" > "$DIR/server.properties"
  FIFO=$DIR/stdin.fifo; mkfifo "$FIFO"; exec 3<>"$FIFO"
  LOG=$DIR/console.log; : > "$LOG"
  # carpet.logicPort=0: the web editor takes a free port, so this cannot meet a server that is already up.
  ( cd "$DIR" && exec java -Xmx2G -Dcarpet.logicPort=0 -jar "$WORK/launch.jar" nogui ) <&3 >"$LOG" 2>&1 &
  SERVER=$!
  seen() { local t=0; until grep -qE "$1" "$LOG"; do sleep 1; t=$((t+1)); if [ "$t" -ge "$2" ] || ! kill -0 "$SERVER" 2>/dev/null; then return 1; fi; done; }
  if seen 'Done \([0-9.]+s\)!' 300; then
    echo "bot spawn" >&3
    if seen 'Bot1 joined the game' 30; then
      echo "list" >&3; seen 'players online' 10
      echo "RESULT $V online-mode=$ONLINE OK: $(grep -oE 'There are .*' "$LOG" | tail -1)"
    else
      echo "RESULT $V online-mode=$ONLINE FAIL: /bot spawn brought no bot"; grep -nE 'ERROR|Exception|Caused by' "$LOG" | head -10; STATUS=1
    fi
  else
    echo "RESULT $V online-mode=$ONLINE FAIL: the server did not start"; tail -20 "$LOG"; STATUS=1
  fi
  echo "stop" >&3
  t=0; while kill -0 "$SERVER" 2>/dev/null && [ "$t" -lt 60 ]; do sleep 1; t=$((t+1)); done
  kill "$SERVER" 2>/dev/null
  exec 3>&-; rm -f "$FIFO"
done
exit "$STATUS"
