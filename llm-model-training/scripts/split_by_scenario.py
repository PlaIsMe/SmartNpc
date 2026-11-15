"""Stable scenario split. Test membership is frozen once created; never overwrite silently."""
import argparse
import hashlib
import json
from pathlib import Path
from validate_dataset import ROOT, LANGUAGES, rows, validate, register_example


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=ROOT / "data/clean/curated.jsonl")
    parser.add_argument("--extra", type=Path, nargs="*", default=[], help="Authored additions; keep existing frozen test scenarios untouched")
    parser.add_argument("--unified", action="store_true", help="Use explicit splits from the single readable source")
    parser.add_argument("--language", choices=LANGUAGES, default="en_us")
    args = parser.parse_args()
    system_prompt = (ROOT / "prompts/system_v1.txt").read_text(encoding="utf-8").strip()
    groups = {"train": [], "validation": [], "test": []}
    for row in rows(args.input):
        validate(row)
        bucket = int(hashlib.sha256(row["scenario_id"].encode()).hexdigest()[:8], 16) % 10
        split = row.get("split", "test" if bucket == 0 else "validation" if bucket == 1 else "train") if args.unified else "test" if bucket == 0 else "validation" if bucket == 1 else "train"
        if split not in groups:
            raise ValueError("Unknown split: " + str(split))
        if split != "test" and not args.unified:
            # Starter sources and frozen evaluations remain immutable; training uses today's runtime policy.
            row["messages"][0]["content"] = system_prompt
        groups[split].append(row)
    membership = {row["scenario_id"]: name for name, data in groups.items() for row in data}
    for name, data in groups.items():
        for row in data:
            if membership[row["scenario_id"]] != name:
                raise ValueError("Scenario split leakage: " + row["scenario_id"])
    for path in args.extra:
        for row in rows(path):
            validate(row)
            scenario = row["scenario_id"]
            if membership.get(scenario) == "test":
                raise ValueError(f"Scenario {scenario} belongs to frozen test data; do not add its paraphrases to training")
            if scenario not in membership:
                # New editable examples join train/validation only. Their translations stay together.
                bucket = int(hashlib.sha256(scenario.encode()).hexdigest()[:8], 16) % 5
                membership[scenario] = "validation" if bucket == 0 else "train"
            groups[membership[scenario]].append(row)
    payloads = {}
    contexts, examples = {}, set()
    for name, data in groups.items():
        for row in data:
            register_example(row, name, contexts, examples)
    for name, data in groups.items():
        if not data:
            raise ValueError(f"No {name} scenarios; add more varied scenarios before splitting")
        payload = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in data)
        target = ROOT / f"data/{name}_{args.language}.jsonl"
        if name == "test" and target.exists():
            if args.unified:
                original = rows(target)
                if sorted(json.dumps(row, sort_keys=True) for row in data) != sorted(json.dumps(row, sort_keys=True) for row in original):
                    raise ValueError("Frozen test set would change. Version a new dataset/evaluation directory explicitly.")
                payload = target.read_text(encoding="utf-8")
            elif target.read_text(encoding="utf-8") != payload:
                raise ValueError("Frozen test set would change. Version a new dataset/evaluation directory explicitly.")
        payloads[target] = payload
    # Validate every split before changing any file.
    for target, payload in payloads.items():
        if target.name.startswith("test_") and target.exists():
            continue
        target.write_text(payload, encoding="utf-8")
    print({name: len(data) for name, data in groups.items()})


if __name__ == "__main__":
    main()
