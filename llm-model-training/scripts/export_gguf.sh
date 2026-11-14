#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python "$script_dir/export_gguf.py" "$@"
# Text-only SmartNpc does not send images and needs no vision projector.
