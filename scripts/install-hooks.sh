#!/bin/sh
# Once per clone: git runs the hooks in .githooks, so every push runs CI on this Mac first (see .githooks/pre-push).
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
echo "Hooks installed: a push now runs scripts/ci-local.sh first."
