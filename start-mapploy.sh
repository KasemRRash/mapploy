#!/usr/bin/env bash
set -euo pipefail

# Git Bash is the entry point; PowerShell starts detached Windows processes.
MAPPLOY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
launcher_args=()

for argument in "$@"; do
  case "$argument" in
    --no-browser) launcher_args+=(-NoBrowser) ;;
    --help|-h)
      printf '%s\n' \
        'Usage: bash start-mapploy.sh [--no-browser]' \
        'Start Mapploy (frontend, backend and local Ollama) from Git Bash on Windows.' \
        'Services continue running after this script exits. Re-running reuses running services.'
      exit 0
      ;;
    *) printf 'Unknown option: %s\n' "$argument" >&2; exit 2 ;;
  esac
done

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *) printf '%s\n' 'Use Git Bash on Windows to run this launcher (not WSL).' >&2; exit 1 ;;
esac

command -v powershell.exe >/dev/null 2>&1 || {
  printf '%s\n' 'Windows PowerShell was not found in PATH.' >&2
  exit 1
}

if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$(cygpath -w "$JAVA_HOME")"
fi

exec powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass \
  -File "$(cygpath -w "$MAPPLOY_ROOT/scripts/start-mapploy.ps1")" "${launcher_args[@]}"
