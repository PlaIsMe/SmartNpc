"""Sequential train/export runner for selected my_chats datasets and two Qwen models.

Examples: retrain.py --all | --en_us | --en_us --en_us_unfiltered | --zh_cn | --vi_vn
--resume skips completed stages with identical inputs, not partial training checkpoints.
"""
import argparse
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path
from model_paths import MODEL_ROOT, MODEL_IDS, REGISTRY_PATH, REPOSITORY_ROOT, TRAINING_ROOT, default_export_tools
from release_workflow import artifact, filename, sha256, write_json

PROFILES = ("en_us", "en_us_unfiltered", "zh_cn", "vi_vn")
WORK = MODEL_ROOT / "work/retrain-qwen"
RECEIPTS = MODEL_ROOT / "release-qwen"
SCRIPTS = TRAINING_ROOT / "scripts"
MARKER = ".smartnpc-qwen-managed"
# The pre-fix runner combined training and export hashes. Only this exact old
# export pair is accepted when migrating a legacy state; training inputs must
# still reproduce the recorded digest. Never trust `trained` alone.
LEGACY_EXPORT_HASHES = (
    "b3504a236ca180bf2a4395f96cca0a2fc1263455edbfd353e5f38eb13e4dfebe",
    "d2fed388f0e92c9771d9e8d38624e134d62579440766c2a8e9c6a36f4bd2bc70",
)


def input_signatures(args, model, profile):
    language = profile_language(profile)
    receipt = TRAINING_ROOT / ("data/en_us_unfiltered/preparation_receipt.json" if profile == "en_us_unfiltered" else f"data/preparation_receipt_{language}.json")
    # Presentation-only registry changes (icons, RAM labels, other model tiers)
    # do not change this model's training inputs. Its config pins the actual recipe.
    paths = [receipt, TRAINING_ROOT / f"configs/{model}.json", MODEL_ROOT / f"import/{model}/smartnpc-base-model.json",
             SCRIPTS / "train_lora.py", SCRIPTS / "training_adapters.py"]
    training_hashes = [sha256(path) for path in paths]
    python = str(training_python(args, model))
    def digest(values):
        return hashlib.sha256(json.dumps(values).encode()).hexdigest()
    training = digest([python, *training_hashes])
    export_hashes = [sha256(SCRIPTS / name) for name in ("export_gguf.py", "prepare_export_tokenizers.py")]
    converter_hash = sha256(args.llama_root / "convert_hf_to_gguf.py")
    legacy_prefix = [python, str(args.export_python), str(args.llama_root), *training_hashes]
    legacy = {digest([*legacy_prefix, *pair, converter_hash]) for pair in (export_hashes, LEGACY_EXPORT_HASHES)}
    converter_files = sorted((args.llama_root / "conversion").rglob("*.py")) + sorted((args.llama_root / "gguf-py/gguf").rglob("*.py"))
    export = digest([str(args.export_python), str(args.llama_root), *export_hashes, converter_hash,
                     *[(str(path.relative_to(args.llama_root)), sha256(path)) for path in converter_files]])
    return training, export, legacy


def resume_state(args, model, profile):
    work = WORK / identity(model, profile)
    state_path = work / "state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if args.resume and state_path.is_file() else {}
    training, export, legacy = input_signatures(args, model, profile)
    if state:
        matches = state.get("training_inputs") == training if "training_inputs" in state else state.get("inputs") in legacy
        if not matches:
            raise ValueError(f"Training inputs changed for {work.name}; completed training cannot be reused. Preserve this run before starting a new one.")
        if state.get("trained") and (not (work / "run/merged/config.json").is_file() or not any((work / "run/merged").glob("*.safetensors"))):
            raise ValueError(f"Completed training files are missing for {work.name}; refusing to silently retrain")
        state["training_inputs"] = training
    if args.export_only and not state.get("trained"):
        raise ValueError(f"No completed training to export for {work.name}; --export-only never starts training")
    return state, training, export


def profile_language(profile):
    return "en_us" if profile == "en_us_unfiltered" else profile


def identity(model, profile):
    return f"{model}-en_us-unfiltered" if profile == "en_us_unfiltered" else f"{model}-{profile}"


def final_filename(model, profile):
    value = filename(model, profile_language(profile))
    return value.replace("-Q4_K_M", "-unfiltered-Q4_K_M") if profile == "en_us_unfiltered" else value


def training_python(args, model):
    return args.train_python


def clear_managed(path):
    if path.parent.resolve() != WORK.resolve() or path.is_symlink() or path.resolve() != WORK.resolve() / path.name:
        raise ValueError(f"Refusing cleanup outside managed work: {path}")
    if path.exists():
        shutil.rmtree(path)


def run(command, log):
    print("\n> " + shlex.join(map(str, command)), flush=True)
    with log.open("a", encoding="utf-8") as stream:
        stream.write("\n> " + shlex.join(map(str, command)) + "\n")
        stream.flush()
        env = dict(os.environ, PYTHONUNBUFFERED="1", PYTHONIOENCODING="utf-8")
        process = subprocess.Popen(list(map(str, command)), cwd=REPOSITORY_ROOT, env=env,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        try:
            for line in process.stdout:
                print(line, end="", flush=True)
                stream.write(line)
                stream.flush()
            if process.wait():
                raise RuntimeError(f"Step failed; see {log}. Fix it, then rerun with --resume.")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            process.stdout.close()


def preflight(args):
    """Check the complete selection before preparing data or replacing managed work."""
    for path in (args.train_python, *{training_python(args, model) for model in args.models}, args.export_python, args.llama_root / "convert_hf_to_gguf.py"):
        if not path.is_file():
            raise ValueError(f"Missing {path}; select your tools with --train-python / --peft-python / --export-python / --llama-root")
    if not any((args.llama_root / "build/bin" / n).is_file() for n in ("llama-quantize", "llama-quantize.exe", "Release/llama-quantize.exe")):
        raise ValueError("Missing llama-quantize in the selected tools")
    converter_files = [args.llama_root / "convert_hf_to_gguf.py", *list((args.llama_root / "conversion").rglob("*.py"))]
    if any(model.startswith("qwen3.5-") for model in args.models) and not any("Qwen3_5" in path.read_text(encoding="utf-8") for path in converter_files):
        raise ValueError("The selected converter has no Qwen3.5 support. Prepare current export tools and pass --llama-root / --export-python.")
    requirements = {}
    for model in args.models:
        base = MODEL_ROOT / "import" / model
        if not (base / "smartnpc-base-model.json").is_file() or not any(base.glob("*.safetensors")):
            raise ValueError(f"Download original weights first: download_base_models.py --model {model}")
        receipt = json.loads((base / "smartnpc-base-model.json").read_text(encoding="utf-8"))
        config = json.loads((TRAINING_ROOT / f"configs/{model}.json").read_text(encoding="utf-8"))
        if config.get("minimum_transformers"):
            requirements.setdefault(training_python(args, model), {})[model] = config["minimum_transformers"]
        if receipt.get("repository") != config["base_model"] or receipt.get("model_id") != model or config.get("revision") and receipt.get("revision") != config["revision"]:
            raise ValueError(f"Base model receipt does not match {model}; download its configured revision first")
    for python, models in requirements.items():
        # Metadata-only check: reject an incompatible batch before preparing data
        # or spending hours training the earlier, compatible tiers.
        check = subprocess.run([str(python), "-c",
                "import importlib.metadata as m,json,sys; from packaging.version import Version; "
                "v=m.version('transformers'); "
                "bad=[k+' needs transformers >= '+r+' (installed '+v+')' for k,r in json.loads(sys.argv[1]).items() if Version(v)<Version(r)]; "
                "print('; '.join(bad)); sys.exit(bool(bad))", json.dumps(models)], capture_output=True, text=True)
        if check.returncode:
            raise ValueError("Training environment check failed: " + (check.stdout.strip() or check.stderr.strip()))
    for profile in args.profiles:
        if not (TRAINING_ROOT / f"data/my_chats_{profile}.toml").is_file():
            raise ValueError(f"Missing source my_chats_{profile}.toml")
    for path in (MODEL_ROOT, WORK.parent, WORK, RECEIPTS):
        if path.is_symlink() or path.resolve() != path.absolute():
            raise ValueError(f"Workspace must not be redirected: {path}")
    if WORK.exists() and not (WORK / MARKER).is_file():
        raise ValueError(f"Unrecognized managed workspace: {WORK}")


def execute(args):
    # Reject invalid resumes before data preparation or receipt updates.
    if args.resume:
        for profile in args.profiles:
            for model in args.models:
                if args.export_only or (WORK / identity(model, profile) / "state.json").is_file():
                    resume_state(args, model, profile)
    for profile in args.profiles:
        language = profile_language(profile)
        unfiltered = profile == "en_us_unfiltered"
        log = WORK / f"prepare-{profile}.log"
        preparation = [SCRIPTS / "prepare_unfiltered_chats.py"] if unfiltered else [SCRIPTS / "import_my_chats.py", "--language", language]
        run([args.train_python, "-u", *preparation], log)
        receipt_path = RECEIPTS / f"{profile}.json"
        # Keep other successful families when a caller intentionally retrains a subset.
        prior = json.loads(receipt_path.read_text(encoding="utf-8")) if receipt_path.exists() else {}
        entries = {row["family"]: row for row in prior.get("models", []) if row.get("family") in MODEL_IDS and row.get("family") not in args.models}
        batch = {"schema": 1, "profile": profile, "complete": False, "models": list(entries.values())}
        write_json(receipt_path, batch)
        for model in args.models:
            python = training_python(args, model)
            name = identity(model, profile)
            work = WORK / name
            state_path = work / "state.json"
            state, signature, export_signature = resume_state(args, model, profile)
            print(f"\nTraining/exporting {name}", flush=True)
            if not state.get("trained"):
                clear_managed(work)
                work.mkdir()
                state = {"training_inputs": signature, "trained": False}
                write_json(state_path, state)
                extra = ["--data-dir", TRAINING_ROOT / "data/en_us_unfiltered"] if unfiltered else []
                run([python, "-u", SCRIPTS / "train_lora.py", "--config", TRAINING_ROOT / f"configs/{model}.json",
                     "--language", language, "--output", work / "run", *extra], work / "train.log")
                if not (work / "run/merged/config.json").is_file():
                    raise ValueError(f"Training produced no merged model: {name}")
                state["trained"] = True
                write_json(state_path, state)
            else:
                print(f"Reusing completed training: {name}", flush=True)
                write_json(state_path, state)
            final = MODEL_ROOT / final_filename(model, profile)
            staging = WORK / f"{name}-export"
            if state.get("exported") and final.is_file() and artifact(final) == state["exported"]:
                print(f"Already exported and verified: {final.name}", flush=True)
                clear_managed(staging)
            else:
                run([args.export_python, "-u", SCRIPTS / "prepare_export_tokenizers.py", work / "run/merged"], work / "export.log")
                clear_managed(staging)
                version = "unfiltered" if unfiltered else "current"
                run([args.export_python, "-u", SCRIPTS / "export_gguf.py", args.llama_root, work / "run/merged", staging,
                     "--language", language, "--version", version], work / "export.log")
                exported_name = final.name if unfiltered else final.name.replace("-Q4_K_M", "-current-Q4_K_M")
                exported = staging / exported_name
                metadata = artifact(exported)
                os.replace(exported, final)
                metadata["file"] = final.name
                state["exported"] = metadata
                state["export_inputs"] = export_signature
                write_json(state_path, state)
                clear_managed(staging)  # f16 only removed after successful Q4 verification/promotion.
            entries[model] = {"id": name, "family": model, "language": language, "profile": "unfiltered" if unfiltered else "standard", **state["exported"]}
            batch["models"] = [entries[key] for key in MODEL_IDS if key in entries]
            batch["complete"] = set(entries) == set(MODEL_IDS)
            write_json(receipt_path, batch)
    print(f"\nFinished. Q4_K_M files: {MODEL_ROOT}. Upload manually, then run sync_model_releases.py with the same dataset flags.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="All four datasets: two Qwen models each, eight exports")
    for profile in PROFILES:
        parser.add_argument("--" + profile, action="store_true", help=f"Train my_chats_{profile}.toml")
    parser.add_argument("--models", nargs="+", choices=MODEL_IDS, default=list(MODEL_IDS), help="Optional subset of the two Qwen models")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--export-only", action="store_true", help="With --resume, export completed training only; fail if any selected job needs training")
    parser.add_argument("--dry-run", action="store_true", help="Show selected jobs without modifying anything")
    parser.add_argument("--train-python", type=Path, default=MODEL_ROOT / ".venv-train/bin/python")
    export_root, export_python = default_export_tools()
    parser.add_argument("--export-python", type=Path, default=export_python)
    parser.add_argument("--llama-root", type=Path, default=export_root)
    args = parser.parse_args()
    if args.export_only and not args.resume:
        parser.error("--export-only requires --resume to preserve completed training")
    args.profiles = [p for p in PROFILES if args.all or getattr(args, p)]
    if not args.profiles:
        parser.error("Choose --all or one or more dataset flags: --en_us --en_us_unfiltered --zh_cn --vi_vn")
    args.models = list(dict.fromkeys(args.models))
    args.train_python = args.train_python.expanduser().absolute()
    args.export_python = args.export_python.expanduser().absolute()
    args.llama_root = args.llama_root.expanduser().resolve()
    if args.dry_run:
        print(f"Unsloth Python: {args.train_python}\nExport Python: {args.export_python}\nExport tools: {args.llama_root}")
        for profile in args.profiles:
            for model in args.models:
                print(f"my_chats_{profile}.toml -> {model} [{training_python(args, model)}] -> {MODEL_ROOT / final_filename(model, profile)}")
        print(f"{len(args.profiles) * len(args.models)} jobs; work: {WORK}; resume={args.resume}")
        return
    if sys.platform != "linux":
        parser.error("Use Linux/WSL with the CUDA training environment")
    try:
        preflight(args)
        WORK.mkdir(parents=True, exist_ok=True)
        (WORK / MARKER).touch()
        import fcntl
        with (WORK / ".lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            execute(args)
    except BlockingIOError:
        parser.exit(1, "Another retrain.py batch is running.\n")
    except KeyboardInterrupt:
        parser.exit(130, "Stopped. Rerun the same dataset flags with --resume to keep completed stages.\n")
    except (OSError, ValueError, RuntimeError) as error:
        parser.exit(1, f"Stopped: {error}\n")


if __name__ == "__main__":
    main()
