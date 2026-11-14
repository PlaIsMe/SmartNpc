"""Use the game's event prompt verbatim when preparing event training examples."""
import json
import re
from functools import lru_cache
from model_paths import REPOSITORY_ROOT

SOURCE = REPOSITORY_ROOT / "common/src/main/java/com/pla/smart_npc/ai/AiEventPrompts.java"


@lru_cache(maxsize=1)
def definitions():
    source = SOURCE.read_text(encoding="utf-8")
    match = re.search(r'private static final String SYSTEM = """\n(.*?)\n\s*""";', source, re.S)
    if not match:
        raise ValueError("Cannot read the game's event prompt; update event_prompt.py for the new source format")
    system = "\n".join(line.strip() for line in match.group(1).splitlines()) + "\n"
    instruction = source.split("public static String instruction(String event) {", 1)
    if len(instruction) != 2:
        raise ValueError("Cannot locate the game's event instruction method")
    instruction = instruction[1]
    cases = {}
    for labels, text in re.findall(r'case (.*?) -> ("(?:[^"\\]|\\.)*");', instruction):
        for label in re.findall(r'"([a-z_]+)"', labels):
            cases[label] = json.loads(text)
    default = re.search(r'default -> ("(?:[^"\\]|\\.)*");', instruction)
    if not cases or not default:
        raise ValueError("Cannot read event instructions from AiEventPrompts.java")
    return system, cases, json.loads(default.group(1))


def event_prompt(language, event):
    system, cases, default = definitions()
    if language == "zh_cn":
        system = system.replace("natural English chat", "natural Chinese chat")
        ending = "\n你必须只用简体中文说话。人名和 JSON 字段名保持原样。不要用英语写 say 的内容。"
    elif language == "vi_vn":
        system = system.replace("natural English chat", "natural Vietnamese chat")
        ending = "\nViết say bằng tiếng Việt kiểu chat game, ngắn gọn. Giữ nguyên tên người và khóa JSON."
    else:
        ending = "\nThe say field must contain English speech only."
    return system + "\n" + cases.get(event, default) + ending
