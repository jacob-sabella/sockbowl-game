#!/usr/bin/env bash
# Sync the canonical GraphQL schema from the authoritative sockbowl-questions repo.
#
# sockbowl-questions is the single source of truth for the packet/question
# domain contract. This script copies its schema into this repo so DGS codegen
# can generate the packet model types from it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SRC="${SOCKBOWL_QUESTIONS_SCHEMA:-$REPO_ROOT/../sockbowl-questions/src/main/resources/graphql/schema.graphqls}"
DEST="$REPO_ROOT/src/main/resources/graphql/questions-schema.graphqls"

if [[ ! -f "$SRC" ]]; then
  echo "ERROR: canonical schema not found at: $SRC" >&2
  echo "Set SOCKBOWL_QUESTIONS_SCHEMA or place sockbowl-questions as a sibling dir." >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
{
  echo "# GENERATED COPY -- authority is sockbowl-questions."
  echo "# Run scripts/sync-schema.sh to refresh. Do not edit by hand."
  echo "#"
  cat "$SRC"
} > "$DEST"

echo "Synced schema -> $DEST"
