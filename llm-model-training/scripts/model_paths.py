"""Repository paths and the maintained Qwen registry. Legacy runs remain readable."""
import json
import re
import sys
from pathlib import Path

TRAINING_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = TRAINING_ROOT.parent
MODEL_ROOT = REPOSITORY_ROOT / "llm-model"
REGISTRY_PATH = TRAINING_ROOT / "configs/model_registry.json"
REGISTRY = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
if REGISTRY.get("schema") != 1 or len(REGISTRY.get("tiers", [])) != 2:
    raise ValueError("Expected the two-model Qwen registry")
MODELS = {row["model_id"]: row for row in REGISTRY["tiers"]}
MODEL_IDS = tuple(MODELS)
LEGACY_MODEL_IDS = tuple(REGISTRY["legacy_model_ids"])
ALL_MODEL_IDS = MODEL_IDS + LEGACY_MODEL_IDS
if len(MODEL_IDS) != 2 or set(MODEL_IDS) & set(LEGACY_MODEL_IDS):
    raise ValueError("Model registry contains duplicate/current legacy identities")


def model_id(config_path):
    value = Path(config_path).stem
    if value not in ALL_MODEL_IDS:
        raise ValueError("Unknown model configuration: " + value)
    return value


def model_metadata(key):
    if key not in MODELS:
        raise ValueError("Not a current release tier: " + key)
    return MODELS[key]


def default_export_tools():
    """Reuse the newest prepared tool folder for this OS, without downloading or building."""
    suffix = "wsl" if sys.platform == "linux" else "windows"
    candidates = []
    for path in (MODEL_ROOT / "tools").glob(f"llama.cpp-b*-{suffix}"):
        match = re.fullmatch(r"llama\.cpp-b([0-9]+)-" + suffix, path.name)
        if match and (path / "smartnpc-export-tools.json").is_file() and (path / "convert_hf_to_gguf.py").is_file():
            candidates.append((int(match[1]), path))
    build, root = max(candidates, default=(12180, MODEL_ROOT / "tools" / f"llama.cpp-b12180-{suffix}"))
    python = Path.home() / f".smartnpc/export-envs/b{build}" / ("bin/python" if sys.platform == "linux" else "Scripts/python.exe")
    return root, python
