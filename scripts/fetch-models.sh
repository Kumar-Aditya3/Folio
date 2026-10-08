#!/usr/bin/env bash
# Downloads the Phase 0 measurement fixtures into .models/ (git-ignored).
#
#   ./scripts/fetch-models.sh            # English model only (~23 MB)
#   ./scripts/fetch-models.sh --all      # + multilingual-e5-small (~113 MB)
#
# These are NOT test fixtures in the usual sense: they are the artifacts the Phase 0a/0b
# measurements were taken on, kept out of the repo because they total ~135 MB. Tests that
# need them skip (via a JUnit assumption) when they are absent, so a clean checkout still
# runs the full suite.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/.models"
mkdir -p "$DEST"

HF="https://huggingface.co"

fetch() {
    local name="$1" url="$2"
    if [ -s "$DEST/$name" ]; then
        echo "  = $name (already present)"
        return
    fi
    echo "  + $name"
    curl -fL --retry 3 --progress-bar -o "$DEST/$name" "$url"
}

echo "Fetching MiniLM-L6-v2 (English, int8)..."
fetch "minilm-int8.onnx"       "$HF/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"
fetch "minilm-vocab.txt"       "$HF/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt"
fetch "minilm-tokenizer.json"  "$HF/Xenova/all-MiniLM-L6-v2/resolve/main/tokenizer.json"
fetch "minilm-config.json"     "$HF/Xenova/all-MiniLM-L6-v2/resolve/main/config.json"

if [ "${1:-}" = "--all" ]; then
    echo "Fetching multilingual-e5-small (int8)..."
    fetch "e5small-int8.onnx"       "$HF/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx"
    fetch "e5small-tokenizer.json"  "$HF/Xenova/multilingual-e5-small/resolve/main/tokenizer.json"
    fetch "e5small-config.json"     "$HF/Xenova/multilingual-e5-small/resolve/main/config.json"
fi

echo
echo "Verifying digests..."
cd "$DEST"
sha256sum -c <<'EOF'
afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1  minilm-int8.onnx
07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3  minilm-vocab.txt
EOF

echo "Done. Fixtures in $DEST"
