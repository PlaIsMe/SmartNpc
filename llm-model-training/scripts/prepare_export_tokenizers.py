"""Adapt saved Transformers 5 special-token metadata for the pinned Transformers 4 converter."""
import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path


def prepare(directory):
    path = directory / "tokenizer_config.json"
    original = path.read_bytes()
    config = json.loads(original)
    if config.get("tokenizer_class") == "TokenizersBackend":
        # TF5's generic Rust-backed tokenizer was called PreTrainedTokenizerFast
        # in TF4. Keep the serialized tokenizer graph, token IDs and template.
        tokenizer = json.loads((directory / "tokenizer.json").read_text(encoding="utf-8"))
        if not isinstance(tokenizer.get("model"), dict):
            raise ValueError(f"Missing serialized tokenizer model: {directory}")
        config["tokenizer_class"] = "PreTrainedTokenizerFast"
    extra = config.get("extra_special_tokens")
    if isinstance(extra, list):
        additional = config.get("additional_special_tokens", [])
        if not isinstance(additional, list):
            raise ValueError(f"Unexpected additional_special_tokens format: {path}")
        tokenizer = json.loads((directory / "tokenizer.json").read_text(encoding="utf-8"))
        saved_tokens = {token["content"] for token in tokenizer.get("added_tokens", []) if token.get("special")}
        combined = list(additional)
        for token in extra:
            content = token if isinstance(token, str) else token.get("content") if isinstance(token, dict) else None
            if content not in saved_tokens:
                raise ValueError(f"Special token is not already registered in tokenizer.json: {content!r}")
            if token not in combined:
                combined.append(token)
        config["additional_special_tokens"] = combined
        del config["extra_special_tokens"]
    if config == json.loads(original):
        print(f"Already compatible: {path}")
        return
    backup = path.with_name("tokenizer_config.json.before-export-compat.bak")
    if backup.exists() and backup.read_bytes() != original:
        # Retain the first backup when upgrading an already adapted run.
        digest = hashlib.sha256(original).hexdigest()
        backup = path.with_name(f"tokenizer_config.json.before-export-compat.{digest}.bak")
    if not backup.exists():
        with backup.open("xb") as stream:
            stream.write(original)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()
    print(f"Prepared: {path} (original saved in {backup.name})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("merged_dirs", type=Path, nargs="+", help="One or more completed runs' merged directories")
    args = parser.parse_args()
    for directory in args.merged_dirs:
        prepare(directory)


if __name__ == "__main__":
    main()
