#!/bin/sh
# ./mailscan.sh <command>: runs `python -m mailscan <command>` in a fresh container that exits when done.
# The image is rebuilt first if the code changed; the running scanner, if any, is not touched.
#   ./mailscan.sh check -n 20
#   ./mailscan.sh status
set -e
cd "$(dirname "$0")"
case "$1" in
    check|scan|run) deps="" ;;
    *) deps="--no-deps" ;;  # status and the Telegram commands don't need Ollama
esac
exec docker compose run --rm --build $deps mailscan python -m mailscan "$@"
