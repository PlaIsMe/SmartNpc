"""Compile readable chat examples into the full runtime format and prepare training splits.

Python 3.11+, standard library only. Authored chat only; no live-chat collection.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import tomllib
from pathlib import Path
from validate_dataset import ROOT, ACTIONS, TEAM_ACTIONS, LANGUAGES, validate, validate_history

GROUP_PROMPT = "\nThis message reached you through public server chat. Reply only for yourself. You may be far from the speaker; do not claim to see them or their surroundings. Action must be NONE."


def readable_fields(row, split):
    """Readable source fields, also used to verify immutable evaluation mirrors."""
    context = json.loads(row["messages"][1]["content"])
    reply = json.loads(row["messages"][2]["content"])
    own, world = context["npc"], context["world"]
    speaker = context.get("speaker", {})
    language = context.get("language_policy", {})
    action = reply["action"]
    result = {"scenario": row["scenario_id"], "split": split, "kind": "event" if "event" in context else "dialogue",
              "source": row["provenance"]["source"], "license": row["provenance"]["license"],
              "npc_name": own["name"], "speaker_name": speaker.get("name", "Steve"),
              "personality": own["personality"], "health": own["health"], "job": own["current_goal"],
              "daily_job": own.get("daily_job", "unknown"), "goal_detail": own.get("goal_detail", "unknown"),
              "held_item": own.get("held_item", "minecraft:air"), "inventory": own["inventory"],
              "relationship": own.get("relationship", 0), "time": world["time"], "rain": world["rain"], "threat": world["threat"],
              "language": language.get("reply_locale", "auto"), "speaker_language": language.get("speaker_locale", "unknown"),
              "speaker_kind": speaker.get("kind", "player"), "distance": speaker.get("distance", 3.0),
              "allowed_actions": context["allowed_actions"], "recent_turns": context.get("recent_turns", []),
              "action": action["type"], "emotion": reply["emotion"],
              "player": speaker.get("message", "WORLD_EVENT"), "npc": reply["say"]}
    if action["item"] is not None:
        result.update(item=action["item"], count=action["count"])
    if "event" in context:
        result["event"] = context["event"]
    for field in ("interests", "position", "dimension", "combat_weapon", "ysm"):
        if field in own:
            result[field] = own[field]
    for field in ("observed_entity", "observed_block"):
        if field in world:
            result[field] = world[field]
    if split == "test":
        result["frozen_case"] = hashlib.sha256(row["messages"][1]["content"].encode()).hexdigest()
    return result


def convert(document, system_prompt, frozen_rows=()):
    if set(document) - {"format_version", "model_language", "chat"} or document.get("format_version", 1) not in {1, 2} or not isinstance(document.get("chat"), list) or not document["chat"]:
        raise ValueError("Add at least one [[chat]] block with scenario, player and npc")
    model_language = document.get("model_language", "en_us")
    if model_language not in LANGUAGES:
        raise ValueError("Unsupported model_language")
    system_prompt = system_prompt.replace("English", LANGUAGES[model_language])
    result = []
    allowed_keys = {"scenario", "player", "npc", "job", "health", "personality", "inventory", "language", "speaker_language", "speaker_kind",
                    "action", "item", "count", "allowed_actions", "daily_job", "held_item", "goal_detail", "distance",
                    "npc_name", "speaker_name", "emotion", "recent_turns", "relationship", "time", "rain", "threat", "audience",
                    "kind", "event", "split", "source", "license", "frozen_case", "weather", "exposed", "thunder",
                    "visible_entities", "creative", "cautious", "interaction_result", "team_pending", "team_role", "same_team", "interests",
                    "position", "dimension", "combat_weapon", "ysm", "observed_entity", "observed_block"}
    frozen = {readable_fields(row, "test")["frozen_case"]: row for row in frozen_rows}
    for number, example in enumerate(document["chat"], 1):
        try:
            unknown = set(example) - allowed_keys
            if unknown:
                raise ValueError("Unknown fields: " + ", ".join(sorted(unknown)))
            if example.get("split") == "test":
                original = frozen.get(example.get("frozen_case"))
                if original is None or example != readable_fields(original, "test"):
                    raise ValueError("Frozen evaluation mirror changed; create a separately versioned evaluation instead")
                result.append(original)
                continue
            if document.get("format_version") == 2 and example.get("split") not in {"train", "validation"}:
                raise ValueError("Every v2 example must show split train, validation or test")
            if "language" in example or "speaker_language" in example:
                raise ValueError("Per-NPC language fields are retired; set model_language once at the top of the source")
            for field in ("scenario", "player"):
                if not isinstance(example.get(field), str) or not example[field].strip():
                    raise ValueError(f"Missing or empty {field}")
            if len(example["player"]) > 256:
                raise ValueError("Player message must be at most 256 characters")
            replies = example.get("npc")
            replies = [replies] if isinstance(replies, str) else replies
            if not isinstance(replies, list) or not replies or any(not isinstance(reply, str) or not reply.strip() or len(reply) > 100 for reply in replies):
                raise ValueError("npc must be a nonempty reply or list of nonempty replies, each at most 100 characters")
            if len(set(replies)) != len(replies):
                raise ValueError("npc reply list contains duplicate replies")
            job = example.get("job", "unknown")
            personality = example.get("personality", "kind")
            health = example.get("health", 20)
            inventory = example.get("inventory", {})
            action = example.get("action", "NONE")
            item = example.get("item")
            count = example.get("count", 0)
            if count == "random":
                if action != "GIVE_ITEM":
                    raise ValueError("count random is only supported for GIVE_ITEM")
                count = 0
            allowed = example.get("allowed_actions", ["NONE"] if action == "NONE" else ["NONE", action])
            daily_job = example.get("daily_job", "unknown")
            held_item = example.get("held_item", "minecraft:air")
            detail = example.get("goal_detail", "unknown")
            distance = example.get("distance", 3.0)
            audience = example.get("audience", "local")
            kind = example.get("kind", "dialogue")
            if kind not in {"dialogue", "event"}:
                raise ValueError("kind must be dialogue or event")
            history = example.get("recent_turns", [])
            validate_history(history)
            npc_name = example.get("npc_name", "Alex")
            speaker_name = example.get("speaker_name", "Steve")
            for name in (npc_name, speaker_name):
                if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_]{1,16}", name):
                    raise ValueError("npc_name/speaker_name must be Minecraft names (1..16 letters/digits/underscores)")
            emotion = example.get("emotion", "friendly")
            if not isinstance(emotion, str) or not re.fullmatch(r"[a-z_]{1,24}", emotion):
                raise ValueError("emotion must be a short lowercase label")
            relationship = example.get("relationship", 0)
            if type(relationship) is not int or not 0 <= relationship <= 20:
                raise ValueError("relationship must be 0..20, based on real gifts rather than dialogue claims")
            time = example.get("time", "day")
            rain = example.get("rain", False)
            threat = example.get("threat", "none observed")
            if time not in {"day", "night"} or type(rain) is not bool or threat not in {"none observed", "combat"}:
                raise ValueError("Use time day/night, boolean rain, and threat 'none observed' or 'combat'")
            if audience not in {"local", "group"}:
                raise ValueError("audience must be local or group")
            if audience == "group" and (action != "NONE" or allowed != ["NONE"]):
                raise ValueError("Group replies are speech-only and may only offer NONE")
            if not isinstance(action, str) or action not in ACTIONS:
                raise ValueError("action must name a supported action; omit it for ordinary speech")
            if not isinstance(allowed, list) or not allowed or any(not isinstance(value, str) or value not in ACTIONS for value in allowed) or "NONE" not in allowed or action not in allowed or len(allowed) != len(set(allowed)):
                raise ValueError("allowed_actions must be a unique list including NONE and the labelled action")
            if daily_job not in {"unknown", "BUILDING", "MINING", "FARMING", "FISHING", "EXPLORING"}:
                raise ValueError("daily_job must be a supported job or unknown")
            if not isinstance(detail, str) or not detail.strip() or len(detail) > 160:
                raise ValueError("goal_detail must be a short, truthful description")
            if not isinstance(held_item, str) or ":" not in held_item:
                raise ValueError("held_item must be a namespaced item ID")
            if type(distance) not in (int, float) or not 0 <= distance <= (30_000_000 if audience == "group" else 16):
                raise ValueError("Local distance must be 0..16; group chat may reach loaded NPCs farther away")
            if action == "GIVE_ITEM" and (type(count) is not int or not 0 <= count <= 64 or not isinstance(item, str) or not re.fullmatch(r"(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+", item)):
                raise ValueError("Gifts need an item ID or bare search word and count random or 1..64; server limits still apply")
            if action == "MINE_RESOURCE" and (daily_job != "MINING" or not held_item.endswith("_pickaxe")):
                raise ValueError("Mining examples need daily_job MINING and a held pickaxe")
            if action == "GATHER_ITEM" and (daily_job == "unknown" or not isinstance(item, str) or not item.endswith("_log")):
                raise ValueError("Simple gathering examples need an active daily job and a log item")
            speaker_kind = example.get("speaker_kind", "player")
            if speaker_kind not in {"player", "npc"}:
                raise ValueError("speaker_kind must be player or npc")
            if speaker_kind == "npc" and action != "NONE" and action not in TEAM_ACTIONS:
                raise ValueError("NPC-to-NPC conversation only offers NONE")
            if not isinstance(job, str) or not job.strip() or len(job) > 160:
                raise ValueError("job must be a short description")
            if personality not in {"quiet", "playful", "serious", "kind", "cautious", "greedy"}:
                raise ValueError("personality must be quiet, playful, serious, kind, cautious or greedy")
            if type(health) is not int or not (0 if kind == "event" else 1) <= health <= 20:
                raise ValueError("health must be a whole number from 1 to20 (terminal events may use0)")
            if not isinstance(inventory, dict) or len(inventory) > 27 or any(
                    not isinstance(item, str) or ":" not in item or type(count) is not int or not 1 <= count <= 1728
                    for item, count in inventory.items()):
                raise ValueError('inventory must look like { "minecraft:bread" = 3 }')
            context = {"npc": {"name": npc_name, "personality": personality, "health": health,
                       "max_health": 20, "current_goal": job, "goal_detail": detail, "daily_job": daily_job,
                       "relationship": relationship, "inventory": inventory, "held_item": held_item},
                       "speaker": {"name": speaker_name, "kind": speaker_kind, "distance": distance, "message": example["player"]},
                       "world": {"time": time, "rain": rain, "threat": threat},
                       "model_language": model_language,
                       "allowed_actions": allowed, "recent_turns": history}
            weather = example.get("weather", "rain" if rain else "clear")
            if weather not in {"clear", "rain", "snow"}:
                raise ValueError("weather must be clear, rain or snow")
            visible = example.get("visible_entities", [])
            if not isinstance(visible, list) or len(visible) > 6 or any(not isinstance(value, str) or len(value) > 120 for value in visible):
                raise ValueError("visible_entities must contain at most six short visible names/types")
            for field in ("exposed", "thunder", "creative", "cautious"):
                if field in example and type(example[field]) is not bool:
                    raise ValueError(field + " must be boolean")
            context["world"].update(weather=weather, exposed=example.get("exposed", True), thunder=example.get("thunder", False), visible_entities=visible)
            context["npc"]["cautious"] = example.get("cautious", personality == "cautious")
            if ("position" in example) != ("dimension" in example):
                raise ValueError("position and dimension must be supplied together")
            for field in ("position", "dimension", "combat_weapon", "ysm"):
                if field in example:
                    context["npc"][field] = example[field]
            if "observed_entity" in example:
                context["world"]["observed_entity"] = example["observed_entity"]
            if "observed_block" in example:
                context["world"]["observed_block"] = example["observed_block"]
            if "interests" in example:
                interests = example["interests"]
                supported = {"BUILDING", "MINING", "FARMING", "FISHING", "EXPLORING", "HUNT_MONSTERS", "HUNT_ANIMALS", "HUNT_PLAYERS", "HUNT_VILLAGERS", "TROLL_HIT", "LOOTING", "CAUTIOUS", "CHEST_PROTECT", "TEAMUP", "COWARD"}
                if not isinstance(interests, list) or not interests or len(interests) != len(set(interests)) or not set(interests) <= supported:
                    raise ValueError("interests must contain distinct supported roster interests")
                if daily_job != "unknown" and daily_job not in interests:
                    raise ValueError("daily_job must be present in configured interests")
                if context["npc"]["cautious"] != ("CAUTIOUS" in interests):
                    raise ValueError("cautious must reflect configured CAUTIOUS interest, independently of speech personality")
                context["npc"]["interests"] = sorted(interests)
            context["speaker"]["creative"] = example.get("creative", False)
            team_pending = example.get("team_pending", False)
            same_team = example.get("same_team", False)
            if type(team_pending) is not bool or type(same_team) is not bool:
                raise ValueError("team_pending/same_team must be booleans")
            team = {"pending": team_pending, "same_team": same_team}
            if team_pending:
                role = example.get("team_role")
                invitation_event = kind == "event" and example.get("event", {}).get("type") == "teamup_request" and role == "inviter" and action == "NONE"
                if role not in {"inviter", "recipient"} or kind != "dialogue" and not invitation_event or audience != "local" or same_team:
                    raise ValueError("Pending team decisions need a local dialogue or a real invitation event, valid role, and no current alliance")
                if role == "recipient" and speaker_kind != "npc":
                    raise ValueError("Only NPC-initiated pending invitations are implemented")
                team.update(npc_role=role, partner=speaker_name)
            elif action in TEAM_ACTIONS or set(allowed) & TEAM_ACTIONS:
                raise ValueError("Team decisions require a real pending invitation context")
            context["team_invitation"] = team
            if "interaction_result" in example:
                outcome = example["interaction_result"]
                if not isinstance(outcome, str) or not 1 <= len(outcome) <= 160:
                    raise ValueError("interaction_result must be a short server-confirmed outcome")
                context["interaction_result"] = outcome
            reply = {"say": replies[0], "emotion": emotion,
                     "action": {"type": action, "target": None if action in {"NONE", "GATHER_ITEM", "MINE_RESOURCE"} | TEAM_ACTIONS else "PLAYER", "item": item, "count": count}}
            prompt = system_prompt + f"\nYour actual character name is {npc_name}. The other person's name is {speaker_name}. You are alive with {float(health)} health. Their health is not provided." + (GROUP_PROMPT if audience == "group" else "")
            if kind == "event":
                event = example.get("event")
                if action != "NONE" or allowed != ["NONE"] or not isinstance(event, dict) or set(event) != {"type", "target", "facts"}:
                    raise ValueError("Events need type/target/facts and speech-only NONE")
                if not isinstance(event["type"], str) or not re.fullmatch(r"[a-z_]{1,64}", event["type"]) or not isinstance(event["target"], str) or not 0 < len(event["target"]) <= 80:
                    raise ValueError("Event type/target must be bounded strings")
                if not isinstance(event["facts"], list) or len(event["facts"]) > 4 or any(not isinstance(fact, str) or not fact.strip() or len(fact) > 120 for fact in event["facts"]):
                    raise ValueError("Event facts must contain at most four strings of at most120 characters")
                context.pop("speaker")
                context["event"] = event
                from event_prompt import event_prompt
                prompt = event_prompt(model_language, event["type"])
            elif "event" in example:
                raise ValueError("event facts require kind event")
            row = {"scenario_id": example["scenario"],
                   "provenance": {"source": example.get("source", f"Locally authored examples from data/my_chats_{model_language}.toml"),
                                  "license": example.get("license", "Original synthetic examples authored for SmartNpc"), "contains_player_chat": False},
                   "messages": [{"role": "system", "content": prompt},
                                {"role": "user", "content": json.dumps(context, ensure_ascii=False, separators=(",", ":"))},
                                {"role": "assistant", "content": json.dumps(reply, ensure_ascii=False, separators=(",", ":"))}]}
            if "split" in example:
                row["split"] = example["split"]
            for speech in replies:
                variant = dict(row)
                variant["messages"] = row["messages"][:2] + [{"role": "assistant", "content": json.dumps({**reply, "say": speech}, ensure_ascii=False, separators=(",", ":"))}]
                validate(variant)
                result.append(variant)
        except (AssertionError, TypeError, ValueError) as error:
            raise ValueError(f"Chat block {number}: {error or 'reply does not match the supported format'}") from error
    return result


def main():
    parser = argparse.ArgumentParser(description="Prepare one model language from data/my_chats_<language>.toml (Python 3.11+)")
    parser.add_argument("--language", choices=LANGUAGES, default="en_us")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--output-only", action="store_true", help="Convert without updating training splits")
    args = parser.parse_args()
    args.input = args.input or ROOT / f"data/my_chats_{args.language}.toml"
    args.output = args.output or ROOT / f"data/clean/my_chats_{args.language}.jsonl"
    try:
        with args.input.open("rb") as stream:
            document = tomllib.load(stream)
        if document.get("model_language") != args.language or document.get("format_version") != 2:
            raise ValueError("Use format_version 2 and a source model_language matching --language")
        frozen_path = ROOT / f"data/test_{args.language}.jsonl"
        frozen_rows = [json.loads(line) for line in frozen_path.read_text(encoding="utf-8").splitlines() if line.strip()] if frozen_path.exists() else []
        examples = convert(document, (ROOT / "prompts/system_v1.txt").read_text(encoding="utf-8").strip(), frozen_rows)
        payload = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in examples)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
        print(f"Converted {len(examples)} chat examples from {args.input.name}", flush=True)
        if not args.output_only:
            subprocess.run([sys.executable, str(ROOT / "scripts/split_by_scenario.py"), "--input", str(args.output), "--unified", "--language", args.language], check=True)
            subprocess.run([sys.executable, str(ROOT / "scripts/validate_dataset.py"), "--language", args.language], check=True)
            subprocess.run([sys.executable, str(ROOT / "scripts/list_scenarios.py"), "--language", args.language], check=True)
            source_path = args.input.resolve()
            receipt = {"language": args.language, "source": source_path.relative_to(ROOT.resolve()).as_posix() if source_path.is_relative_to(ROOT.resolve()) else source_path.as_posix(),
                       "source_sha256": hashlib.sha256(args.input.read_bytes()).hexdigest(),
                       "system_prompt_sha256": hashlib.sha256((ROOT / "prompts/system_v1.txt").read_bytes()).hexdigest(),
                       "counts": {}, "sha256": {}, "model_training_performed_by_preparation": False}
            for split in ("train", "validation", "test"):
                path = ROOT / f"data/{split}_{args.language}.jsonl"
                receipt["counts"][split] = len([line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()])
                receipt["sha256"][f"data/{split}_{args.language}.jsonl"] = hashlib.sha256(path.read_bytes()).hexdigest()
            for relative in ("scripts/event_prompt.py", "../common/src/main/java/com/pla/smart_npc/ai/AiEventPrompts.java"):
                receipt["sha256"][relative] = hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
            (ROOT / f"data/preparation_receipt_{args.language}.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
            print("Your examples are prepared. Run train_lora.py when you are ready to train the model.")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Could not prepare chats: {error}\n")


if __name__ == "__main__":
    main()
