# Qwen model setup

The maintained roster contains two models. The historical filename remains for existing links.

| Card | Training source | Training recipe | Card RAM label |
| --- | --- | --- | --- |
| Lightweight — Qwen2.5 0.5B | `Qwen/Qwen2.5-0.5B-Instruct`; existing local source receipt | Existing Unsloth Qwen2 recipe | ≈ 0.5 GB |
| Standard — Qwen3.5 0.8B | `Qwen/Qwen3.5-0.8B` at `2fc06364715b967f1860aea9cf38778875588b17` | Unsloth BF16 LoRA, language layers only, 1024-token cap | ≈ 1 GB |

Legacy chat remains a separate card. Both Qwen cards use the supplied `qwen25.png` / `qwen35.png` assets. Card RAM labels are the user's requested approximate guidance, not a new benchmark. MiniCPM5, LFM3 and Qwen3.5 2B are outside the current roster.

`configs/model_registry.json` owns the roster and presentation. Run `scripts/sync_model_registry.py --check` after changes. No download metadata is invented for unpublished fine-tuned exports. The existing verified Qwen3.5 0.8B releases remain selectable; a new retrained export must pass the release synchronizer before replacing them.

## Training and resume

Use the prepared WSL Unsloth environment at `llm-model/.venv-train`. Qwen3.5 uses the existing `FastVisionModel` loader with vision-layer fine-tuning disabled. Its original multimodal weights are required by that loader; Minecraft chat still uses server-supplied text observations. Both models use assistant-only labels, the publisher's non-thinking chat template, and the same validated action contract.

From the repository root in WSL, these commands select four sequential jobs: two models for standard English, then two for unfiltered English.

```bash
python3 llm-model-training/scripts/retrain.py --en_us --en_us_unfiltered --dry-run
python3 llm-model-training/scripts/retrain.py --en_us --en_us_unfiltered --resume
```

The runner invokes the configured training/export interpreters itself. Managed runs are under `llm-model/work/retrain-qwen`; receipts are under `llm-model/release-qwen`. Completed, verified Qwen2.5 English/unfiltered runs were preserved during the roster change. `--resume` reuses those runs and their verified exports, then trains missing Qwen3.5 jobs. Omitting `--resume` deliberately creates fresh selected runs. `--models qwen3.5-0.8b` selects only that model. `--all` selects eight jobs across four dataset profiles.

For export recovery, `--resume --export-only` requires every selected job to have complete merged training output. It never starts training. Training fingerprints exclude presentation-only registry changes; changed source data, recipe, tokenizer source receipt or training code still invalidates reuse. Retain the tokenizer export compatibility preparation for Transformers 5 to the pinned converter environment.

After manually uploading verified exports, run `scripts/sync_model_releases.py` with the same dataset/model selection. It checks local receipts and GitHub asset sizes/checksums before updating the bundled catalog. No uploads happen automatically.

## More varied dialogue

The recent Qwen2.5 game log already contains activity-conditioned replies such as “Building the house rn.” More useful variety should come from distinct questions and truthful situations, not merely more responses to the same serialized input.

Suggested future coverage, not implemented or trained by this migration:

- Pair the same activity with different questions: greetings, work status, jokes, offers, warnings and corrections. This teaches the model when its job is relevant.
- Pair the same question with different jobs, inventories, health, weather, visible entities and conversation histories.
- Add coherent follow-ups across changing authoritative facts: a supply shortage, received materials, resumed work, then confirmed completion.
- Let a reply combine one activity fact with one relevant observation or personality reaction. For confirmed building in rain: “Building in the rain, great timing lol.” For building alone: “Still building, wish these blocks placed themselves.”
- New progress stages, remembered rescues or entity-specific dangers require a real server observation/memory field first. Do not infer hunger, a seen house, a completed build, player health, or successful rescue from missing context.
- Keep ten distinct short replies per block, while expanding the actual input/context combinations. Use unseen paraphrases, names and held-out grounding/follow-up cases to determine whether Qwen3.5 improves on Qwen2.5.

Both models should receive the facts needed for honest dialogue. A larger model's benefit must appear as better relevance, continuity and natural wording; enabling extra facts alone does not prove a quality upgrade. No new gameplay permissions or long reasoning output are needed.
