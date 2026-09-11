#!/usr/bin/env bash
set -euo pipefail

# Build script for Linux/Unix environments
# Compiles all Java sources under src/ into the out/ directory

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

mkdir -p out

# Find all .java files and compile them into out/
JAVA_FILES=$(find src -name "*.java")
if [ -z "$JAVA_FILES" ]; then
  echo "No Java source files found in src/. Nothing to compile."
  exit 0
fi

javac -d out $JAVA_FILES

echo "Build complete. Output written to out/"
