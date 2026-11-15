"""Original synthetic witnessed-death examples; Java owns any avoidance, not the LLM."""
import json
import tomllib
import re
from validate_dataset import ROOT, validate


def main():
    system = (ROOT / "prompts/system_v1.txt").read_text(encoding="utf-8").strip()
    with (ROOT / "data/witness_chats.toml").open("rb") as stream:
        authored = tomllib.load(stream)["witness"]
    languages = sorted({key for row in authored for key in row if re.fullmatch(r"[a-z]{2,3}_[a-z0-9]{2,8}", key)})
    cases = [(row["event"], row["victim"], row["killer"], row["observed_kills"], row["avoidance_requested"],
              [row.get(locale) for locale in languages]) for row in authored]
    result = []
    for event, victim, killer, count, fleeing, replies in cases:
        if event not in {"witness_death_unknown", "witness_killed_by", "witness_killing_spree"} or type(count) is not int or not 1 <= count <= 10 or type(fleeing) is not bool:
            raise ValueError("Invalid witness event/count/avoidance fields")
        if any(not isinstance(name, str) or not name.strip() or len(name) > 80 for name in (victim, killer)):
            raise ValueError("Witness victim/killer must be short names (use unknown for missing killer)")
        if event == "witness_killing_spree" and (count < 2 or killer == "unknown"):
            raise ValueError("A spree needs at least two observed kills and a known killer")
        for locale, say in zip(languages, replies):
            if say is None:
                continue
            context = {"npc": {"name": "Casey", "personality": "cautious", "health": 20, "max_health": 20,
                       "current_goal": "unknown", "goal_detail": "unknown", "daily_job": "unknown", "relationship": 0,
                       "inventory": {}, "held_item": "minecraft:air"},
                       "world": {"time": "day", "rain": False, "threat": "none observed"},
                       "event": {"type": event, "target": "none" if killer == "unknown" else killer,
                                 "facts": ["victim=" + victim, "killer=" + killer, "observed_kills=" + str(count),
                                           "avoidance=" + ("requested; path not yet proven" if fleeing else "not requested")]},
                       "language_policy": {"reply_locale": locale, "speaker_locale": locale, "language_mismatch": False},
                       "allowed_actions": ["NONE"], "recent_turns": []}
            reply = {"say": say, "emotion": "shocked", "action": {"type": "NONE", "target": None, "item": None, "count": 0}}
            row = {"scenario_id": event, "provenance": {"source": "Original synthetic witnessed-death scenario, no live player chat",
                       "license": "CC0-1.0", "contains_player_chat": False}, "messages": [
                       {"role": "system", "content": system + "\nThis is witnessed-death speech. Use only the supplied victim/killer/count facts. Avoidance is handled by Java; action NONE. Unknown killers stay unknown."},
                       {"role": "user", "content": json.dumps(context, ensure_ascii=False, separators=(",", ":"))},
                       {"role": "assistant", "content": json.dumps(reply, ensure_ascii=False, separators=(",", ":"))}]}
            validate(row)
            result.append(row)
    output = ROOT / "data/clean/witness_chats.jsonl"
    output.write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in result), encoding="utf-8")
    print(f"Prepared {len(result)} witnessed-death examples across {', '.join(languages)}.")


if __name__ == "__main__":
    main()
