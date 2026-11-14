"""Prepare the separate English Unfiltered source without overwriting standard language datasets."""
import argparse
import json
import tomllib
from collections import Counter
from import_my_chats import convert
from model_paths import TRAINING_ROOT as ROOT
from release_workflow import write_json, sha256
from unfiltered_profile import SOURCE, DATA, JAVA_STYLE, RUDE, style
from validate_dataset import rows, register_example


def prepare():
    document = tomllib.loads(SOURCE.read_text(encoding="utf-8"))
    if document.get("format_version") != 2 or document.get("model_language") != "en_us":
        raise ValueError("English Unfiltered source must use format_version=2 and model_language=en_us")
    editable = [row for row in document["chat"] if row.get("split") != "test"]
    if any(not isinstance(row.get("npc"), list) or len(set(row["npc"])) < 10 for row in editable):
        raise ValueError("Each editable block needs at least ten distinct NPC replies")
    frozen_path = ROOT / "data/test_en_us.jsonl"
    frozen = rows(frozen_path)
    compiled = convert(document, (ROOT / "prompts/system_v1.txt").read_text(encoding="utf-8").strip(), frozen)
    groups = {name: [] for name in ("train", "validation", "test")}
    contexts, examples, scenarios = {}, set(), {}
    for row in compiled:
        split = row.get("split", "test")
        if split != "test":
            row["messages"][0]["content"] = style(row["messages"][0]["content"])
        if scenarios.setdefault(row["scenario_id"], split) != split:
            raise ValueError("Scenario leakage: " + row["scenario_id"])
        register_example(row, split, contexts, examples)
        groups[split].append(row)
    if sorted(json.dumps(row, sort_keys=True) for row in groups["test"]) != sorted(json.dumps(row, sort_keys=True) for row in frozen):
        raise ValueError("Unfiltered test mirrors must preserve the original frozen English evaluation")
    groups["test"] = frozen
    if any(not group for group in groups.values()):
        raise ValueError("All three splits are required")
    DATA.mkdir(parents=True, exist_ok=True)
    receipt = {"language": "en_us", "profile": "unfiltered", "source": SOURCE.relative_to(ROOT).as_posix(),
        "source_sha256": sha256(SOURCE), "system_prompt_sha256": sha256(ROOT / "prompts/system_v1.txt"),
        "counts": {}, "sha256": {}, "model_training_performed_by_preparation": False}
    report = {"profile": "en_us_unfiltered", "scenarios": len(scenarios), "blocks": len(editable), "splits": {}}
    for split, group in groups.items():
        path = DATA / f"{split}.jsonl"
        path.write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in group), encoding="utf-8")
        receipt["counts"][split] = len(group)
        receipt["sha256"][path.relative_to(ROOT).as_posix()] = sha256(path)
        speech = [json.loads(row["messages"][2]["content"])["say"] for row in group]
        rude = sum(bool(RUDE.search(text)) for text in speech)
        report["splits"][split] = {"examples": len(group), "rude_or_profane": rude,
            "rude_fraction": round(rude / len(group), 4), "actions": dict(Counter(json.loads(row["messages"][2]["content"])["action"]["type"] for row in group))}
    for path in (JAVA_STYLE, ROOT / "scripts/event_prompt.py", ROOT / "scripts/unfiltered_profile.py", ROOT / "../common/src/main/java/com/pla/smart_npc/ai/AiEventPrompts.java"):
        receipt["sha256"][path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else "../" + path.relative_to(ROOT.parent).as_posix()] = sha256(path)
    write_json(DATA / "preparation_receipt.json", receipt)
    write_json(DATA / "coverage.json", report)
    print(json.dumps(report, indent=2))
    print(f"Prepared {DATA}; no model training started.")


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    prepare()


if __name__ == "__main__":
    main()
