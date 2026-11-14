"""Import existing datapack event lines as grounded speech examples, not player commands."""
import json
import re
from pathlib import Path
from validate_dataset import ROOT, validate

SOURCE = ROOT.parents[1] / "common/src/main/resources/data/smart_npc/chat"
TARGET_NAMES = ["Steve", "Alex", "Sam", "Robin", "Casey", "Taylor", "Morgan", "Jordan", "Jamie", "Riley",
                "Avery", "Blake", "Drew", "Cameron", "Charlie", "Dakota", "Emery", "Finley", "Hayden", "Jesse"]


def build_rows():
    system = (ROOT / "prompts/system_v1.txt").read_text(encoding="utf-8").strip()
    result, skipped = [], []
    for file in sorted(SOURCE.glob("*/*.json")):
        event = file.stem
        for index, entry in enumerate(json.loads(file.read_text(encoding="utf-8"))):
            parts = entry.split("|")
            template = parts[0]
            if event == "teamup_request":
                # These retained legacy lines still promise obsolete ok/okay recruitment.
                skipped.append({"file": str(file.relative_to(SOURCE)), "index": index, "reason": "Inactive legacy invitation; ok/okay is not acceptance. Do not train obsolete behavior."})
                continue
            target = parts[2] if len(parts) > 2 and parts[2].lower() not in {"all", "default", ""} else TARGET_NAMES[index % len(TARGET_NAMES)]
            name = parts[1] if len(parts) > 1 and parts[1].lower() not in {"all", "default", ""} else "Alex"
            fact = f"{['oak', 'birch', 'spruce', 'jungle', 'acacia', 'dark oak', 'mangrove'][index % 7]} {['log', 'planks', 'sapling', 'stairs', 'slab', 'door', 'fence', 'boat'][index // 7 % 8]}" if event == "burn_item" else target
            facts = [fact] if re.search(r"%(?:\d+\$)?s", template) else []
            say = re.sub(r"%(?:\d+\$)?s", lambda match: fact, template).replace("%%", "%")
            if "%" in say or len(say) > 180:
                skipped.append({"file": str(file.relative_to(SOURCE)), "index": index, "reason": "Unsupported formatter/oversized line"})
                continue
            context = {"npc": {"name": name, "personality": "playful", "health": 0 if event in {"death_reaction", "warn_death"} else 20,
                       "max_health": 20, "current_goal": "unknown", "goal_detail": "unknown", "daily_job": "unknown", "relationship": 0,
                       "inventory": {}, "held_item": "minecraft:air"}, "world": {"time": "day", "rain": False, "threat": "none observed"},
                       "event": {"type": event, "target": "none" if event in {"missing_home_chest", "burn_item", "throw_trash"} else target, "facts": facts},
                       "language_policy": {"reply_locale": file.parent.name, "speaker_locale": file.parent.name, "language_mismatch": False},
                       "allowed_actions": ["NONE"], "recent_turns": []}
            # Event identity groups all paraphrases together; different target facts are real context.
            reply = {"say": say, "emotion": "neutral", "action": {"type": "NONE", "target": None, "item": None, "count": 0}}
            row = {"scenario_id": "event_" + event,
                   "provenance": {"source": "Repository resource " + str(file.relative_to(ROOT.parents[1])).replace('\\', '/') + f" [{index}]",
                                  "license": "SmartNpc repository GPL; preserve source attribution", "contains_player_chat": False},
                   "messages": [{"role": "system", "content": system + "\nThis is event-driven NPC speech. React only to event.type and event.facts. Action must be NONE."},
                                {"role": "user", "content": json.dumps(context, ensure_ascii=False, separators=(",", ":"))},
                                {"role": "assistant", "content": json.dumps(reply, ensure_ascii=False, separators=(",", ":"))}]}
            validate(row)
            result.append(row)
    # Multiple stylistic lines can share identical event facts. Use one deterministic label per context.
    seen, unique = set(), []
    for row in result:
        key = validate(row)
        if key not in seen:
            seen.add(key)
            unique.append(row)
    return unique, skipped


def main():
    data, skipped = build_rows()
    output = ROOT / "data/clean/event_chats.jsonl"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in data), encoding="utf-8")
    report = ROOT / "data/clean/event_import_report.json"
    report.write_text(json.dumps({"imported": len(data), "skipped": skipped,
                      "note": "Repeated event contexts use one label. Original resource arrays remain the full style reference."}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Imported {len(data)} distinct event examples; skipped {len(skipped)} legacy/unsupported entries. See event_import_report.json.")


if __name__ == "__main__":
    main()
