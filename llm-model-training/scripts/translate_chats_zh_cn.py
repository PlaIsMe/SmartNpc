"""Create a reviewable Simplified Chinese draft locally; never alter the English source."""
import argparse
import copy
import hashlib
import json
import re
import sys
import tomllib
from pathlib import Path
from model_paths import TRAINING_ROOT, MODEL_ROOT

TRANSLATOR = "Helsinki-NLP/opus-mt-en-zh"
REVISION = "408d9bc410a388e1d9aef112a2daba955b945255"
GLOSSARY = {
    "health bar": "生命值条", "health points": "生命值", "health": "生命值", "hunger bar": "饥饿条", "hunger meter": "饥饿值",
    "stone pickaxe": "石镐", "iron pickaxe": "铁镐", "wooden axe": "木斧", "pickaxes": "镐", "pickaxe": "镐",
    "diamond sword": "钻石剑", "iron sword": "铁剑", "enchanted sword": "附魔剑", "armor": "盔甲", "boots": "靴子",
    "raw iron": "粗铁", "raw gold": "粗金", "raw copper": "粗铜", "iron ore": "铁矿石", "gold ore": "金矿石", "coal ore": "煤矿石", "diamond ore": "钻石矿石",
    "ancient debris": "远古残骸", "netherite": "下界合金", "cobblestone": "圆石", "diamonds": "钻石", "diamond": "钻石",
    "birch logs": "白桦原木", "birch log": "白桦原木", "spruce logs": "云杉原木", "spruce log": "云杉原木", "oak logs": "橡木原木", "oak log": "橡木原木",
    "coal": "煤炭", "copper ingot": "铜锭", "copper coin": "铜币", "copper": "铜", "iron": "铁", "gold": "金",
    "golden apple": "金苹果", "apples": "苹果", "apple": "苹果", "bread": "面包", "loaves": "面包", "sticks": "木棍", "stick": "木棍",
    "crouches": "下蹲", "crouching": "下蹲", "crouch": "下蹲", "respawn": "重生", "inventory": "背包", "stack": "一组",
    "enchantments": "附魔", "enchanted": "附魔", "durability": "耐久度", "creeper": "苦力怕", "wheat": "小麦", "lava": "熔岩",
    "NPC": "NPC", "NPCs": "NPC", "lol": "哈哈", "bro": "兄弟", "pleaseee": "求你啦",
}
for wood, chinese in {"oak": "橡木", "birch": "白桦", "spruce": "云杉", "jungle": "丛林", "acacia": "金合欢", "dark oak": "深色橡木", "mangrove": "红树"}.items():
    for part, localized in {"log": "原木", "planks": "木板", "sapling": "树苗", "stairs": "楼梯", "slab": "台阶", "door": "门", "fence": "栅栏", "boat": "船"}.items():
        GLOSSARY[f"{wood} {part}"] = chinese + localized
ANCHORS = [
    ('banana', r'香蕉'), ('orange', r'橘色|橙色|橘子|橙子|橘|橙'), ('lemon', r'柠檬'), ('watermelon', r'西瓜'),
    ('pineapple', r'菠萝|凤梨'), ('strawberry', r'草莓'), ('blueberry', r'蓝莓'), ('cherry', r'樱桃'),
    ('grape', r'葡萄'), ('peach', r'桃子|桃'), ('pear', r'梨子|梨'), ('mango', r'芒果'), ('coconut', r'椰子|椰'),
]


def toml_value(value):
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, list):
        return '[' + ', '.join(toml_value(v) for v in value) + ']'
    if isinstance(value, dict):
        return '{ ' + ', '.join(toml_value(str(k)) + ' = ' + toml_value(v) for k, v in value.items()) + ' }'
    raise TypeError(type(value))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=TRAINING_ROOT / "data/my_chats_en_us.toml")
    parser.add_argument("--output", type=Path, default=TRAINING_ROOT / "data/my_chats_zh_cn.toml")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    frozen_path = args.output.parent / "test_zh_cn.jsonl"
    if args.output.exists() or frozen_path.exists():
        raise ValueError("Chinese source or frozen evaluation already exists; edit it instead of overwriting")
    document = tomllib.loads(args.source.read_text(encoding="utf-8"))
    originals = document["chat"]
    names = set()
    for row in originals:
        names.update([row.get("npc_name", "Alex"), row.get("speaker_name", "Steve")])
        event = row.get("event", {})
        if event.get("target") not in {None, "none"}:
            names.add(event["target"])
        for fact in event.get("facts", []):
            if fact.startswith(("victim=", "killer=")) and fact.split('=', 1)[1] != "unknown":
                names.add(fact.split('=', 1)[1])
    protected = re.compile(r"\b(?:" + '|'.join(re.escape(name) for name in sorted(names, key=len, reverse=True))
                           + r")\b|\b(?i:" + '|'.join(re.escape(word) for word in sorted(GLOSSARY, key=len, reverse=True))
                           + r")\b|\b[a-z0-9_.-]+:[a-z0-9_./-]+\b|\b(?:npc|speaker|world|language_policy)\.[a-z_]+\b|\b[A-Z][A-Z_]{2,}\b|/[a-z]+\b|\b(?:allowed_actions|model_language)\b")

    def pieces(text):
        result = []
        start = 0
        for match in protected.finditer(text):
            result.append((False, text[start:match.start()]))
            result.append((True, match.group()))
            start = match.end()
        result.append((False, text[start:]))
        return result

    def needs_translation(text):
        return bool(re.search('[A-Za-z]', text)) and not re.fullmatch(r"[Tt]_[Tt]|[xX][dD]", text.strip())

    texts = []
    for row in originals:
        if row.get('kind', 'dialogue') != 'event':
            texts.append(row['player'])
        texts.extend(row['npc'] if isinstance(row['npc'], list) else [row['npc']])
        texts.extend(turn.split(': ', 1)[1] for turn in row.get('recent_turns', []))
    def masked(text):
        parts = pieces(text)
        replacements = []
        output = []
        for keep, part in parts:
            if not keep:
                output.append(part)
            else:
                if len(replacements) >= len(ANCHORS):
                    raise ValueError('Too many protected terms in one short utterance')
                anchor, pattern = ANCHORS[len(replacements)]
                localized = part if part in names else GLOSSARY.get(part.lower(), part)
                replacements.append((pattern, localized))
                output.append(anchor)
        return ''.join(output), replacements

    fragments = sorted({masked(text)[0] for text in texts if needs_translation(text)})
    cache_root = MODEL_ROOT / '.translation-tools'
    cache_path = cache_root / 'zh_cn_glossary_translation_cache.json'
    cache = json.loads(cache_path.read_text(encoding='utf-8')) if cache_path.exists() else {}
    missing = [fragment for fragment in fragments if fragment not in cache]
    if missing:
        import ctranslate2
        from transformers import MarianTokenizer
        from opencc import OpenCC
        tokenizer = MarianTokenizer.from_pretrained(str(cache_root / 'opus-mt-en-zh'), local_files_only=True)
        engine = ctranslate2.Translator(str(cache_root / 'opus-mt-en-zh-int8'), device='cpu', compute_type='int8', inter_threads=1, intra_threads=args.threads)
        simplify = OpenCC('t2s')
        for offset in range(0, len(missing), args.batch_size):
            batch = missing[offset:offset + args.batch_size]
            tokens = [tokenizer.convert_ids_to_tokens(tokenizer.encode('>>cmn_Hans<< ' + text)) for text in batch]
            results = engine.translate_batch(tokens, beam_size=4, num_hypotheses=4, max_decoding_length=160)
            for english, result in zip(batch, results):
                candidates = [simplify.convert(tokenizer.decode(tokenizer.convert_tokens_to_ids(hypothesis), skip_special_tokens=True)).strip() for hypothesis in result.hypotheses]
                cache[english] = list(dict.fromkeys(candidate for candidate in candidates if candidate))
                if not cache[english]:
                    raise ValueError('Empty translation for ' + english)
            cache_root.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding='utf-8')
            print(f'Translated {min(offset + args.batch_size, len(missing))}/{len(missing)} text fragments', flush=True)

    def translate(text, rank=0):
        if not needs_translation(text):
            return text
        key, replacements = masked(text)
        candidates = cache[key]
        result = candidates[min(rank, len(candidates) - 1)]
        # Restore glossary terms and names without translating them incorrectly.
        for pattern, localized in replacements:
            result = re.sub(pattern, lambda match, value=localized: value, result)
        # Keep gamer shorthand natural, while identifiers and proper names stay literal.
        result = re.sub(r'\bbro\b', '兄弟', result, flags=re.I)
        result = re.sub(r'\blol\b', '哈哈', result, flags=re.I)
        return result

    translated = []
    for row in originals:
        copy_row = copy.deepcopy(row)
        copy_row.pop('frozen_case', None)
        copy_row.pop('language', None)
        copy_row.pop('speaker_language', None)
        if copy_row.get('kind', 'dialogue') != 'event':
            copy_row['player'] = translate(row['player'])
        copy_row['recent_turns'] = [turn.split(': ', 1)[0] + ': ' + translate(turn.split(': ', 1)[1]) for turn in row.get('recent_turns', [])]
        original_replies = row['npc'] if isinstance(row['npc'], list) else [row['npc']]
        replies = []
        for reply in original_replies:
            candidates = [translate(reply, rank) for rank in range(4)]
            translated_reply = next((candidate for candidate in candidates if candidate not in replies), candidates[0])
            if translated_reply not in replies:
                replies.append(translated_reply)
        copy_row['npc'] = replies
        copy_row['source'] = row.get('source', 'Locally authored English SmartNpc examples') + f'; Simplified Chinese draft translated locally using {TRANSLATOR}@{REVISION}'
        translated.append(copy_row)

    # Seed a separate Chinese evaluation version; the frozen English files are never touched.
    from import_my_chats import convert, readable_fields
    prompt = (TRAINING_ROOT / 'prompts/system_v1.txt').read_text(encoding='utf-8').strip()
    tests = []
    for row in translated:
        if row['split'] == 'test':
            evaluation = {**row, 'split': 'validation', 'npc': row['npc'][0]}
            if evaluation.get('daily_job') == 'idle':
                evaluation['daily_job'] = 'unknown'
            compiled = convert({'format_version': 2, 'model_language': 'zh_cn', 'chat': [evaluation]}, prompt)[0]
            compiled.pop('split', None)
            tests.append(compiled)
    test_iter = iter(tests)
    for index, row in enumerate(translated):
        if row['split'] == 'test':
            translated[index] = readable_fields(next(test_iter), 'test')
    lines = ['# Simplified Chinese translation draft; review wording and facts before release training.',
             '# Proper names, identifiers, item IDs, actions, quantities, scenario IDs and splits are preserved.',
             '# Translated locally; English source and its frozen test set are unchanged.',
             'format_version = 2', 'model_language = "zh_cn"', '']
    last = None
    for row in translated:
        if row['scenario'] != last:
            lines += ['# ============================================================================', '# ' + row['scenario'], '# ============================================================================', '']
            last = row['scenario']
        lines.append('[[chat]]')
        for key, value in row.items():
            if key == 'npc' and isinstance(value, list):
                lines += ['npc = ['] + ['    ' + toml_value(reply) + ',' for reply in value] + [']']
            else:
                lines.append(key + ' = ' + toml_value(value))
        lines.append('')
    args.output.write_text('\n'.join(lines), encoding='utf-8')
    frozen_path.write_text(''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n' for row in tests), encoding='utf-8')
    report = {'language': 'zh_cn', 'english_source_sha256': hashlib.sha256(args.source.read_bytes()).hexdigest(),
              'translator': TRANSLATOR, 'translator_revision': REVISION, 'human_review_complete': False,
              'source_blocks': len(originals), 'translated_blocks': len(translated), 'translated_fragments': len(fragments)}
    (args.output.parent / 'translation_zh_cn.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'Wrote {args.output}; review the machine-translated draft before training a release.', flush=True)


if __name__ == '__main__':
    main()
