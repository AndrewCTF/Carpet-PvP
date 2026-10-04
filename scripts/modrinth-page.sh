#!/usr/bin/env bash
# Puts docs/modrinth.md on the project's Modrinth page and adds the pictures listed in
# docs/modrinth-gallery.txt to its gallery. Run it by hand, on your own machine, after changing either.
#
#   MODRINTH_TOKEN=... scripts/modrinth-page.sh [project id or slug]
#
# The token needs the "Write projects" scope, which is why this is a local script and not a workflow:
# a token that can rewrite the project page does not belong in the repository's secrets. Keep it in a
# file only you can read and source that, rather than typing it on a command line.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROJECT=${1:-carpet-pvp-practice}
: "${MODRINTH_TOKEN:?set MODRINTH_TOKEN to a Modrinth token with the Write projects scope}"
api="https://api.modrinth.com/v2/project/$PROJECT"
agent="AndrewCTF/Carpet-PvP (https://github.com/AndrewCTF/Carpet-PvP)"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

jq -n --rawfile body "$ROOT/docs/modrinth.md" '{body: $body}' > "$work/body.json"
code=$(curl -sS -o "$work/response.txt" -w '%{http_code}' -X PATCH "$api" \
  -H "Authorization: $MODRINTH_TOKEN" -H "User-Agent: $agent" \
  -H 'Content-Type: application/json' --data @"$work/body.json")
if [ "$code" != 204 ]; then
  echo "Modrinth refused the description ($code): $(cat "$work/response.txt")" >&2
  exit 1
fi
echo "description updated"

# A picture is told apart by its title, so running this twice adds nothing twice.
have=$(curl -sS "$api" -H "Authorization: $MODRINTH_TOKEN" -H "User-Agent: $agent" | jq -r '.gallery[].title // empty')
order=0
while IFS='|' read -r file title featured; do
  case "$file" in ''|'#'*) continue ;; esac
  order=$((order + 1))
  if printf '%s\n' "$have" | grep -qxF "$title"; then
    echo "already there: $title"
    continue
  fi
  if [ ! -f "$ROOT/docs/images/$file" ]; then
    echo "docs/images/$file is listed in docs/modrinth-gallery.txt and does not exist" >&2
    exit 1
  fi
  encoded=$(jq -rn --arg t "$title" '$t | @uri')
  code=$(curl -sS -o "$work/response.txt" -w '%{http_code}' -X POST \
    "$api/gallery?ext=png&featured=$featured&ordering=$order&title=$encoded" \
    -H "Authorization: $MODRINTH_TOKEN" -H "User-Agent: $agent" \
    -H 'Content-Type: image/png' --data-binary "@$ROOT/docs/images/$file")
  if [ "$code" != 204 ]; then
    echo "Modrinth refused $file ($code): $(cat "$work/response.txt")" >&2
    exit 1
  fi
  echo "added: $title"
done < "$ROOT/docs/modrinth-gallery.txt"
