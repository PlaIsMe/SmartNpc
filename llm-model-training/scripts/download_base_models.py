"""Download original training weights, never inference GGUFs; no training or upload."""
import argparse
import json
from model_paths import TRAINING_ROOT, MODEL_ROOT, MODEL_IDS


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--model", choices=MODEL_IDS, default=None)
    group.add_argument("--all", action="store_true")
    parser.add_argument("--list", action="store_true", help="Print official sources without downloading")
    args = parser.parse_args()
    selected = MODEL_IDS if args.all or args.list else [args.model or MODEL_IDS[0]]
    for key in selected:
        config = json.loads((TRAINING_ROOT / f"configs/{key}.json").read_text(encoding="utf-8"))
        repository = config["base_model"]
        destination = MODEL_ROOT / "import" / key
        print(f"{key}: https://huggingface.co/{repository}/tree/{config.get('revision') or 'main'} -> {destination}", flush=True)
        if args.list:
            continue
        from huggingface_hub import HfApi, snapshot_download
        receipt_path = destination / "smartnpc-base-model.json"
        if receipt_path.exists():
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            if receipt["repository"] != repository:
                raise ValueError("Existing model directory belongs to another repository")
            revision = config.get("revision") or receipt["revision"]
        else:
            revision = config.get("revision") or HfApi().model_info(repository).sha
        snapshot_download(repo_id=repository, revision=revision, local_dir=str(destination),
                          allow_patterns=["*.safetensors", "*.json", "*.model", "*.txt", "*.tiktoken", "*.jinja", "tokenizer*", "vocab.*", "LICENSE*", "README.md"] + (["*.py"] if config.get("trust_remote_code") else []),
                          ignore_patterns=["onnx/**", "runs/**"])
        if not any(destination.glob("*.safetensors")):
            raise ValueError("No safetensors weights downloaded; check the selected repository")
        import hashlib
        code_hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in destination.glob("*.py")} if config.get("trust_remote_code") else {}
        receipt_path.write_text(json.dumps({"repository": repository, "revision": revision, "model_id": key, "code_sha256": code_hashes}, indent=2) + "\n", encoding="utf-8")
        print(f"Ready: {key} at pinned revision {revision}", flush=True)


if __name__ == "__main__":
    main()
