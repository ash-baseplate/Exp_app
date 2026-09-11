#!/usr/bin/env bash
set -euo pipefail

# Run script for Linux/Unix environments
# Usage:
#   ./run.sh [MainClass] [args...]
# Examples:
#   ./run.sh BudgetCliApp          -> run interactive CLI
#   ./run.sh AppSmokeTest          -> run smoke tests

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

if [ ! -d out ]; then
  echo "out/ directory not found. Run ./build.sh first."
  exit 1
fi

if [ $# -eq 0 ]; then
  MAIN_CLASS="BudgetCliApp"
else
  MAIN_CLASS="$1"
  shift
fi

java -cp out "$MAIN_CLASS" "$@"
