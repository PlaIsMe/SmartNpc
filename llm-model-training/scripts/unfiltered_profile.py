"""Names and exact runtime style substitutions for the optional English Unfiltered release."""
import re
from model_paths import TRAINING_ROOT, REPOSITORY_ROOT

SOURCE = TRAINING_ROOT / "data/my_chats_en_us_unfiltered.toml"
DATA = TRAINING_ROOT / "data/en_us_unfiltered"
JAVA_STYLE = REPOSITORY_ROOT / "common/src/main/java/com/pla/smart_npc/ai/AiSpeechStyle.java"
RUDE = re.compile(r"\b(?:fuck\w*|shit\w*|bullshit|damn\w*|hell|wtf|stfu|trash|dumbass\w*|asshole\w*|ass)\b", re.I)


def style(prompt):
    # Read literal replacements from Java, keeping training and inference identical.
    import json
    pairs = re.findall(r'\.replace\(("(?:[^"\\]|\\.)*"), ("(?:[^"\\]|\\.)*")\)', JAVA_STYLE.read_text(encoding="utf-8"))
    if len(pairs) != 2:
        raise ValueError("Runtime style substitutions changed; review unfiltered_profile.py")
    for old, new in pairs:
        prompt = prompt.replace(json.loads(old), json.loads(new))
    return prompt


def identity(model):
    return f"{model}-en_us-unfiltered"


def filename(model):
    from release_workflow import filename as standard_filename
    return standard_filename(model, "en_us").replace("-Q4_K_M.gguf", "-unfiltered-Q4_K_M.gguf")
