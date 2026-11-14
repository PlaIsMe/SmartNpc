"""Shared naming and file verification for the local release workflow (stdlib only)."""
import hashlib
import json
import os
import re
import tempfile

from model_paths import MODEL_IDS, TRAINING_ROOT

LANGUAGES = ("en_us", "zh_cn", "vi_vn")
REPOSITORY = "PlaIsMe/SmartNpc"


def jobs():
    return [(model, language) for model in MODEL_IDS for language in LANGUAGES]


def filename(model, language):
    config = json.loads((TRAINING_ROOT / "configs" / f"{model}.json").read_text(encoding="utf-8"))
    base = re.sub(r"[^a-z0-9._-]+", "-", config["base_model"].split("/")[-1].lower())
    return f"{language}_smartnpc-{base}-Q4_K_M.gguf"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def artifact(path):
    with path.open("rb") as stream:
        if stream.read(4) != b"GGUF" or path.stat().st_size <= 4:
            raise ValueError(f"Not a completed GGUF: {path}")
    return {"file": path.name, "downloadBytes": path.stat().st_size, "sha256": sha256(path)}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
            temporary = stream.name
            stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        os.replace(temporary, path)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)
