"""Generate the mod's tier presentation from the training registry; no model downloads."""
import argparse
import json
from model_paths import REGISTRY, REPOSITORY_ROOT, TRAINING_ROOT

DESTINATION = REPOSITORY_ROOT / "common/src/main/resources/data/smart_npc/ai/tiers.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rows = []
    for model in REGISTRY["tiers"]:
        config = json.loads((TRAINING_ROOT / f"configs/{model['model_id']}.json").read_text(encoding="utf-8"))
        for field in ("base_model", "architecture"):
            if config[field] != model[field]:
                raise ValueError(f"Registry/config mismatch: {model['model_id']} {field}")
        rows.append({"family": model["model_id"], "name": model["name"], "tier": model["tier"], "icon": model["icon"], "ram": model["ram"]})
    text = json.dumps(rows, indent=2) + "\n"
    if args.check:
        if not DESTINATION.is_file() or DESTINATION.read_text(encoding="utf-8") != text:
            raise ValueError("Run sync_model_registry.py to update the mod tier presentation")
    else:
        DESTINATION.write_text(text, encoding="utf-8")
    print("Two Qwen model tiers verified")


if __name__ == "__main__":
    main()
