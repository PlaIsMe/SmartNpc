"""Create exact Java-consumable model metadata from a locally tested GGUF. Does not publish."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlparse
from model_paths import MODEL_IDS, model_metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("gguf", type=Path)
    parser.add_argument("--id", required=True)
    parser.add_argument("--url", required=True, help="Immutable https://github.com/OWNER/REPO/releases/download/TAG/FILE.gguf")
    parser.add_argument("--engine", required=True, help="llama.cpp build actually used to test this file")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--language", choices=("en_us", "vi_vn", "zh_cn"), default="en_us")
    parser.add_argument("--family", choices=MODEL_IDS, required=True, help="Which Qwen model tier this release belongs to")
    args = parser.parse_args()
    if not args.gguf.name.startswith(args.language + "_"):
        raise ValueError("GGUF filename must start with its language prefix, for example en_us_")
    url = urlparse(args.url)
    if url.scheme != "https" or url.hostname != "github.com" or "/releases/download/" not in url.path or not url.path.endswith("/" + args.gguf.name):
        raise ValueError("Provide the exact GitHub release asset URL matching the local filename")
    digest = hashlib.sha256()
    with args.gguf.open("rb") as stream:
        if stream.read(4) != b"GGUF":
            raise ValueError("Input is not GGUF")
        stream.seek(0)
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    model = {"id": args.id, "name": "SmartNpc custom " + args.id + " (" + args.language + ")", "file": args.gguf.name, "language": args.language, "family": args.family,
             "url": args.url, "downloadBytes": args.gguf.stat().st_size, "sha256": digest.hexdigest(),
             "license": model_metadata(args.family)["license"], "engineRequirement": args.engine}
    args.output.write_text(json.dumps({"schema": 1, "models": [model]}, indent=2) + "\n", encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
