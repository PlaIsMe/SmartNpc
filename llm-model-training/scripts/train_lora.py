"""Text-only assistant-response LoRA SFT, using architecture-specific Unsloth loaders.

Requires a separate Linux/WSL2 CUDA training environment; never used inside Minecraft.
Run validate_dataset.py before training. No telemetry and no live chat collection.
"""
import argparse
import hashlib
import importlib.metadata
import json
import subprocess
import sys
from pathlib import Path
from model_paths import MODEL_ROOT, model_id


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, help="Default: llm-model/work/<language>/<model-id>/v1")
    parser.add_argument("--base-dir", type=Path, help="Override the downloaded original model directory")
    parser.add_argument("--max-steps", type=int, default=-1, help="Use 5 for a disposable pipeline check")
    parser.add_argument("--language", choices=("en_us", "vi_vn", "zh_cn"), default="en_us")
    parser.add_argument("--data-dir", type=Path, help="Isolated prepared profile directory containing train.jsonl, validation.jsonl and preparation_receipt.json")
    args = parser.parse_args()
    key = model_id(args.config)
    args.output = args.output or MODEL_ROOT / "work" / ("en_us-unfiltered" if args.data_dir else args.language) / key / "v1"
    base_dir = (args.base_dir or MODEL_ROOT / "import" / key).resolve()
    from validate_dataset import ROOT, rows, validate

    config = json.loads(args.config.read_text(encoding="utf-8"))
    receipt_path = args.data_dir / "preparation_receipt.json" if args.data_dir else ROOT / f"data/preparation_receipt_{args.language}.json"
    if not receipt_path.exists():
        command = "prepare_unfiltered_chats.py" if args.data_dir else f"import_my_chats.py --language {args.language}"
        raise ValueError(f"Prepare data first: python llm-model-training/scripts/{command}")
    if receipt_path.exists():
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if receipt.get("language") != args.language:
            raise ValueError("Prepared dataset language does not match training language")
        if args.data_dir and (receipt.get("profile") != "unfiltered" or args.language != "en_us"):
            raise ValueError("The isolated data directory must contain an English unfiltered preparation receipt")
        expected = {receipt["source"]: receipt["source_sha256"], "prompts/system_v1.txt": receipt["system_prompt_sha256"], **receipt["sha256"]}
        for relative, digest in expected.items():
            if hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != digest:
                command = "prepare_unfiltered_chats.py" if args.data_dir else f"import_my_chats.py --language {args.language}"
                raise ValueError("Prepared data is stale: " + relative + ". Run python llm-model-training/scripts/" + command + " before training.")
        config["prepared_data"] = receipt
        if args.data_dir:
            config["style_profile"] = receipt["profile"]
    config["language"] = args.language
    config["model_id"] = key
    source_receipt = base_dir / "smartnpc-base-model.json"
    if not source_receipt.is_file() or not any(base_dir.glob("*.safetensors")):
        raise ValueError(f"Download the original model first: python llm-model-training/scripts/download_base_models.py --model {key}")
    base_receipt = json.loads(source_receipt.read_text(encoding="utf-8"))
    if base_receipt.get("repository") != config["base_model"] or base_receipt.get("model_id") != key:
        raise ValueError("The local base-model receipt does not match the selected training configuration")
    if config.get("revision") and config["revision"] != base_receipt.get("revision"):
        raise ValueError("The local base-model revision differs from the configured pinned revision")
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Output directory must be new to preserve experiment provenance")
    print("Data verified. Loading training and CUDA libraries; first startup can take a few minutes...", flush=True)
    # Import Unsloth before torch/transformers so its supported patches initialize in order.
    from unsloth import FastLanguageModel, FastVisionModel
    import torch
    from datasets import Dataset
    from transformers import Trainer, TrainingArguments, DataCollatorForSeq2Seq


    if not torch.cuda.is_available():
        raise RuntimeError("This recipe needs an NVIDIA CUDA training GPU; inference remains CPU compatible")
    # Transformers 5 accepts a fractional warmup_steps value instead of warmup_ratio.
    # Validate arguments before loading weights or creating experiment output.
    training_args = TrainingArguments(output_dir=str(args.output / "checkpoints"), per_device_train_batch_size=1,
            per_device_eval_batch_size=1, gradient_accumulation_steps=8, num_train_epochs=2,
            max_steps=args.max_steps, learning_rate=1e-4, warmup_steps=0.05, weight_decay=0.01,
            bf16=torch.cuda.is_bf16_supported(), fp16=not torch.cuda.is_bf16_supported(),
            optim="adamw_torch", eval_strategy="epoch", save_strategy="epoch", save_total_limit=2,
            logging_steps=5, report_to="none", seed=3407, dataloader_num_workers=0)
    qwen35 = config["architecture"] == "qwen3.5"
    if qwen35 and config["load_in_4bit"]:
        raise ValueError("Qwen3.5 uses BF16 LoRA in this kit, not 4-bit training")
    if qwen35 and not torch.cuda.is_bf16_supported():
        raise RuntimeError("Use a BF16-capable GPU (e.g. Ampere or newer) for this Qwen3.5 recipe")
    revision = base_receipt["revision"]
    loader = FastVisionModel if qwen35 else FastLanguageModel
    model, processor = loader.from_pretrained(model_name=str(base_dir), local_files_only=True,
            load_in_4bit=config["load_in_4bit"], dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16,
            max_seq_length=config["max_length"], use_gradient_checkpointing="unsloth")
    if qwen35:
        model = loader.get_peft_model(model, finetune_vision_layers=False, finetune_language_layers=True,
                finetune_attention_modules=True, finetune_mlp_modules=True, r=16, lora_alpha=16,
                lora_dropout=0, bias="none", random_state=3407)
    else:
        model = loader.get_peft_model(model, r=16, lora_alpha=16, lora_dropout=0, bias="none",
                target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
                use_gradient_checkpointing="unsloth", random_state=3407)
    tokenizer = getattr(processor, "tokenizer", processor)
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    tokenizer.padding_side = "right"

    def encode(row):
        validate(row)
        messages = row["messages"]
        from training_adapters import render_training_turn
        prefix, full = render_training_turn(tokenizer, messages, config)
        prompt_ids = tokenizer(prefix, add_special_tokens=False)["input_ids"]
        encoded = tokenizer(full, add_special_tokens=False)
        if len(encoded["input_ids"]) > config["max_length"]:
            raise ValueError(f"Scenario {row['scenario_id']} exceeds max_length; shorten context, never truncate the JSON label")
        if encoded["input_ids"][:len(prompt_ids)] != prompt_ids:
            raise ValueError("Tokenizer boundary mismatch; cannot safely mask prompt labels")
        encoded["labels"] = [-100] * len(prompt_ids) + encoded["input_ids"][len(prompt_ids):]
        return encoded

    print(f"Preparing tokenized training and validation examples for {args.language}...", flush=True)
    train_path = args.data_dir / "train.jsonl" if args.data_dir else ROOT / f"data/train_{args.language}.jsonl"
    validation_path = args.data_dir / "validation.jsonl" if args.data_dir else ROOT / f"data/validation_{args.language}.jsonl"
    train = Dataset.from_list([encode(row) for row in rows(train_path)])
    validation = Dataset.from_list([encode(row) for row in rows(validation_path)])
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Output directory must be new to preserve experiment provenance")
    args.output.mkdir(parents=True, exist_ok=True)
    config["resolved_base_revision"] = revision
    packages = ["torch", "transformers", "peft", "datasets", "unsloth"]
    config["packages"] = {name: importlib.metadata.version(name) for name in packages}
    (args.output / "experiment.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
    (args.output / "environment.txt").write_bytes(subprocess.check_output([sys.executable, "-m", "pip", "freeze"]))
    loader.for_training(model)
    trainer = Trainer(model=model, processing_class=tokenizer, train_dataset=train, eval_dataset=validation,
            data_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer, padding=True, label_pad_token_id=-100),
            args=training_args)
    print(f"Starting training: {len(train)} training rows, {len(validation)} validation rows. Output: {args.output}", flush=True)
    trainer.train()
    metrics = trainer.evaluate()
    metrics["peak_allocated_vram_bytes"] = torch.cuda.max_memory_allocated()
    (args.output / "validation_metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    model.save_pretrained(str(args.output / "adapter"))
    processor.save_pretrained(str(args.output / "adapter"))
    if qwen35:
        model.save_pretrained_merged(str(args.output / "merged"), processor)
    else:
        model.save_pretrained_merged(str(args.output / "merged"), processor, save_method="merged_16bit")
    print("Saved adapter and merged weights. Export and evaluate GGUF before publishing.")


if __name__ == "__main__":
    main()
