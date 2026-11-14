"""Export one trained language/model; infer both from the run's experiment.json."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from model_paths import MODEL_ROOT
from prepare_export_tokenizers import prepare


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("llama_root", type=Path)
    parser.add_argument("merged_dir", type=Path)
    parser.add_argument("output_dir", type=Path, nargs="?", help="Default: llm-model/export/<language>/<model-id>")
    parser.add_argument("--language", choices=("en_us", "vi_vn", "zh_cn"), help="Optional assertion; must match the trained run")
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    experiment = json.loads((args.merged_dir.parent / "experiment.json").read_text(encoding="utf-8"))
    language = experiment.get("language")
    if language not in {"en_us", "vi_vn", "zh_cn"} or args.language and args.language != language:
        raise ValueError("Export language must match the language recorded during training")
    model = re.sub(r"[^a-z0-9._-]+", "-", experiment["base_model"].split("/")[-1].lower())
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,24}", args.version):
        raise ValueError("Use a short filename-safe version")
    stem = f"{language}_smartnpc-{model}-{args.version}"
    key = experiment.get("model_id", model)
    if not re.fullmatch(r"[a-z0-9._-]{1,80}", key) or ".." in key:
        raise ValueError("Invalid model ID in experiment metadata")
    args.output_dir = args.output_dir or MODEL_ROOT / "export" / language / key
    raw = args.output_dir / f"{stem}-f16.gguf"
    quantized = args.output_dir / f"{stem}-Q4_K_M.gguf"
    if raw.exists() or quantized.exists():
        raise ValueError("Export already exists; choose another output directory or version")
    converter = args.llama_root / "convert_hf_to_gguf.py"
    candidates = [args.llama_root / "build/bin" / name for name in ("llama-quantize", "llama-quantize.exe", "Release/llama-quantize.exe")]
    quantizer = next((path for path in candidates if path.is_file()), None)
    if not converter.is_file() or quantizer is None:
        raise ValueError("Provide a built llama.cpp checkout with its converter and llama-quantize")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    prepare(args.merged_dir)
    subprocess.run([sys.executable, str(converter), str(args.merged_dir), "--outfile", str(raw), "--outtype", "f16"], check=True)
    subprocess.run([str(quantizer.resolve()), str(raw), str(quantized), "Q4_K_M"], check=True)
    with quantized.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    print(f"{digest}  {quantized}")


if __name__ == "__main__":
    main()
