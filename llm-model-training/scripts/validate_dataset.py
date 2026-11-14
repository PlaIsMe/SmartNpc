"""Validate authored training rows, action preconditions, provenance and split leakage."""
import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACTIONS = {"NONE", "FOLLOW_PLAYER", "STOP_CURRENT_GOAL", "RESUME_PREVIOUS_GOAL", "GATHER_ITEM", "MINE_RESOURCE", "GIVE_ITEM", "ASK_PLAYER_FOR_ITEM", "APPROACH_ENTITY", "SOCIAL_LOOK_AT", "REQUEST_HELP", "SOCIAL_EMOTE", "TROLL_SWING"}
TEAM_ACTIONS = {"ACCEPT_TEAM_INVITE", "DECLINE_TEAM_INVITE"}
ACTIONS |= TEAM_ACTIONS
LANGUAGES = {"en_us": "English", "vi_vn": "Vietnamese", "zh_cn": "Chinese"}


def gift_matches(query, item):
    return query == item if ":" in query else query in item.split(":", 1)[-1]


def rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def validate_history(history):
    if not isinstance(history, list) or len(history) not in {0, 2, 4}:
        raise ValueError("recent_turns must contain zero, one or two heard/said pairs")
    for index, turn in enumerate(history):
        prefix, limit = ("heard: ", 160) if index % 2 == 0 else ("said: ", 180)
        if not isinstance(turn, str) or not turn.startswith(prefix) or not turn[len(prefix):].strip() or len(turn[len(prefix):]) > limit:
            raise ValueError("History alternates 'heard: ...' (max160) and 'said: ...' (max180)")
        if any(unicodedata.category(c) in {"Cc", "Cf"} or c == "§" for c in turn):
            raise ValueError("History cannot contain control/formatting codes")


def validate_observations(context):
    def position(value):
        if not isinstance(value, list) or len(value) != 3 or any(type(v) is not int for v in value) or any(abs(v) > 30000000 for v in value):
            raise ValueError("position must contain three bounded integer block coordinates")
    def identifier(value):
        if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", value):
            raise ValueError("Expected a namespaced registry identifier")
    own = context["npc"]
    if "position" in own:
        position(own["position"])
        identifier(own["dimension"])
    if "combat_weapon" in own:
        identifier(own["combat_weapon"])
    if "ysm" in own:
        appearance = own["ysm"]
        if not isinstance(appearance, dict) or set(appearance) != {"model", "emote"} or any(not isinstance(v, str) or len(v) > 120 for v in appearance.values()) or not appearance["model"]:
            raise ValueError("ysm requires a confirmed active model and emote string (empty when idle)")
    if "observed_entity" in context["world"]:
        observed = context["world"]["observed_entity"]
        if not isinstance(observed, dict) or set(observed) != {"name", "type", "position"} or not isinstance(observed["name"], str) or not 1 <= len(observed["name"]) <= 120:
            raise ValueError("observed_entity requires the visible name, registry type and position")
        identifier(observed["type"])
        position(observed["position"])
    if "observed_block" in context["world"]:
        observed = context["world"]["observed_block"]
        if not isinstance(observed, dict) or set(observed) != {"type", "position"}:
            raise ValueError("observed_block requires a visible block registry type and position")
        identifier(observed["type"])
        position(observed["position"])


def validate(row):
    assert isinstance(row["scenario_id"], str) and row["scenario_id"]
    provenance = row["provenance"]
    assert provenance["source"] and provenance["license"]
    assert provenance["contains_player_chat"] is False or provenance.get("consent_record")
    messages = row["messages"]
    assert [m["role"] for m in messages] == ["system", "user", "assistant"]
    context = json.loads(messages[1]["content"])
    validate_observations(context)
    if "interests" in context["npc"]:
        interests = context["npc"]["interests"]
        supported = {"BUILDING", "MINING", "FARMING", "FISHING", "EXPLORING", "HUNT_MONSTERS", "HUNT_ANIMALS", "HUNT_PLAYERS", "HUNT_VILLAGERS", "TROLL_HIT", "LOOTING", "CAUTIOUS", "CHEST_PROTECT", "TEAMUP", "COWARD"}
        assert isinstance(interests, list) and interests and len(interests) == len(set(interests)) and set(interests) <= supported
        assert context["npc"]["cautious"] == ("CAUTIOUS" in interests)
        assert context["npc"]["daily_job"] == "unknown" or context["npc"]["daily_job"] in interests
    validate_history(context.get("recent_turns", []))
    reply = json.loads(messages[2]["content"])
    assert set(reply) == {"say", "emotion", "action"}
    assert isinstance(reply["say"], str)
    assert 0 < len(reply["say"]) <= 100
    assert all(unicodedata.category(c) not in {"Cc", "Cf"} and c != "§" for c in reply["say"])
    assert isinstance(reply["emotion"], str) and re.fullmatch(r"[a-z_]{1,24}", reply["emotion"])
    action = reply["action"]
    assert set(action) == {"type", "target", "item", "count"}
    assert action["type"] in ACTIONS and action["type"] in context["allowed_actions"]
    assert type(action["count"]) is int and 0 <= action["count"] <= 64
    assert action["target"] in (None, "PLAYER")
    item_actions = {"GIVE_ITEM", "ASK_PLAYER_FOR_ITEM", "GATHER_ITEM", "MINE_RESOURCE"}
    if action["type"] in item_actions:
        pattern = r"(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+" if action["type"] == "GIVE_ITEM" else r"[a-z0-9_.-]+:[a-z0-9_./-]+"
        assert isinstance(action["item"], str) and re.fullmatch(pattern, action["item"])
        assert len(action["item"]) <= 120 and (action["count"] > 0 or action["type"] == "GIVE_ITEM")
        assert action["target"] == (None if action["type"] in {"GATHER_ITEM", "MINE_RESOURCE"} else "PLAYER")
    else:
        assert action["item"] is None and action["count"] == 0
    if action["type"] == "NONE":
        assert action == {"type": "NONE", "target": None, "item": None, "count": 0}
    if action["type"] in TEAM_ACTIONS:
        invitation = context["team_invitation"]
        assert invitation["pending"] is True and invitation["npc_role"] in {"inviter", "recipient"}
        assert invitation["partner"] == context["speaker"]["name"]
        assert action["target"] is None and action["item"] is None and action["count"] == 0
    if action["type"] == "GIVE_ITEM":
        assert any(gift_matches(action["item"], item) and count >= max(1, action["count"]) for item, count in context["npc"]["inventory"].items())
        assert context["speaker"]["distance"] <= 4
    if action["type"] == "MINE_RESOURCE":
        assert action["item"] in {"minecraft:coal", "minecraft:raw_iron", "minecraft:raw_gold", "minecraft:raw_copper"}
        assert action["count"] <= 32
    if action["type"] == "GATHER_ITEM":
        assert action["count"] <= 32
    return hashlib.sha256(json.dumps(context, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def register_example(row, split, contexts, examples):
    """Allow alternative answers only inside one scenario and split; reject exact duplicates."""
    digest = validate(row)
    membership = (split, row["scenario_id"])
    if contexts.setdefault(digest, membership) != membership:
        raise ValueError("Player/context appears in different splits or scenarios")
    reply = json.dumps(json.loads(row["messages"][2]["content"]), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    key = (digest, reply)
    if key in examples:
        raise ValueError("Duplicate player/context and response")
    examples.add(key)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--language", choices=LANGUAGES, default="en_us")
    args = parser.parse_args()
    paths = args.paths or [ROOT / "data" / f"{name}_{args.language}.jsonl" for name in ("train", "validation", "test")]
    seen, contexts, scenarios = set(), {}, {}
    total = 0
    for path in paths:
        dataset = rows(path)
        assert dataset, f"Empty dataset: {path}"
        for index, row in enumerate(dataset, 1):
            try:
                register_example(row, path, contexts, seen)
                prior = scenarios.setdefault(row["scenario_id"], path)
                assert prior == path, "Scenario split leakage"
            except (AssertionError, KeyError, TypeError, ValueError) as error:
                raise ValueError(f"{path}:{index}: invalid row: {error}") from error
        print(f"{path.name}: {len(dataset)} rows")
        total += len(dataset)
    print(f"Validated {total} rows; alternatives stay in one scenario/split; no duplicate examples or split leakage.")


if __name__ == "__main__":
    main()
