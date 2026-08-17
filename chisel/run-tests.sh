#!/usr/bin/env bash
# Wrapper around sbt that puts the system `ar` ahead of Homebrew binutils'
# GNU `ar` on PATH. GNU ar writes archives in a format macOS's linker can't
# parse, which breaks Verilator's build step (see notes.md, "Phase 2 tooling
# setup"). Usage: ./run-tests.sh "testOnly epilogue.PassthroughSpec"
set -euo pipefail
export PATH="/opt/homebrew/opt/openjdk/bin:/usr/bin:/bin:/opt/homebrew/bin:/opt/homebrew/sbin"
cd "$(dirname "$0")"
sbt "${@:-test}"
