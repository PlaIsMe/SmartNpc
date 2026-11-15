# SmartNpc Model Training

The current release roster has three tiers, defined in `configs/model_registry.json`:

| Tier | Base model | Training backend | Status |
| --- | --- | --- | --- |
| Lightweight | Qwen2.5 0.5B Instruct | Existing Unsloth path | Supported |
| Standard | MiniCPM5 1B | Transformers + PEFT, Llama architecture | Candidate; needs local train/export/in-game validation |
| Advanced | LFM3 1.7B | Transformers + PEFT, pinned publisher architecture code | Candidate; needs local train/export/in-game validation |

Old SmolLM2, Qwen3 and Qwen3.5 configs, weights, receipts and benchmark reports are retained for rollback. They are excluded from new batches and the four-card picker. An already-selected old model keeps running until the player changes it. No dataset content is rewritten by this migration.

## One retraining command

Run from the SmartNpc repository root in WSL. Choose one or more source datasets:

```bash
# All three tiers for all four datasets: 12 exports
python3 llm-model-training/scripts/retrain.py --all

# Standard English: 3 exports
python3 llm-model-training/scripts/retrain.py --en_us

# Both English styles: 6 exports
python3 llm-model-training/scripts/retrain.py --en_us --en_us_unfiltered

# Chinese or Vietnamese: 3 exports each
python3 llm-model-training/scripts/retrain.py --zh_cn
python3 llm-model-training/scripts/retrain.py --vi_vn

# Preview without changing files
python3 llm-model-training/scripts/retrain.py --all --dry-run

# Resume the SAME selection after stopping
python3 llm-model-training/scripts/retrain.py --en_us --en_us_unfiltered --resume
```

The flags map directly to `data/my_chats_<flag>.toml`. No flags prints an error instead of starting an expensive batch. `--models qwen2.5-0.5b` can limit any selection to one tier. The old `retrain_all.py`, `retrain_en_us.py` and `retrain_unfiltered.py` entry points are replaced by this command.

Each selected dataset is prepared/validated, then its models train and export sequentially. Final versionless Q4_K_M files go directly into `llm-model/`. Successful verification/promotion removes only that job's staged f16. A failed export preserves the previous final file. Logs/checkpoints live under `llm-model/work/retrain-three-tier/<model>-<locale>[-unfiltered]/`. Receipts live under `llm-model/release-three-tier/<dataset>.json`; historical workspaces and receipts are untouched.

`--resume` skips completed stages with matching source/config/tool fingerprints. It does **not** resume a partially trained model from an optimizer checkpoint. After editing chats, use a fresh run without `--resume`. A single lock prevents two new batches from modifying the same workspace.

## Prepare original models and environments

```bash
python3 -m pip install huggingface_hub
python3 llm-model-training/scripts/download_base_models.py --list
python3 llm-model-training/scripts/download_base_models.py --all
```

MiniCPM5 and LFM3 configs pin publisher revisions. LFM3 needs the publisher's Python architecture files; the downloader saves them at that revision and hashes them in the receipt, and the trainer verifies them before loading. Qwen2.5 keeps its existing configuration and source receipt behavior.

The runner defaults to the existing `llm-model/.venv-train/bin/python` CUDA environment. MiniCPM5 uses the publisher's Llama-compatible PEFT projections. LFM3 uses its attention, convolution-projection and MLP LoRA targets, with no Unsloth monkey patching. Both new recipes use BF16/FP16 LoRA, not 4-bit training; GPU capacity must be checked with a small run before launching all languages. LFM3 requires Transformers 5.20.2 or newer; the publisher recipe documents PEFT 0.21.2. The existing Transformers 5.17.0 environment failed to load its configuration, so the batch now rejects that version before preparing data or training earlier tiers. Select a compatible environment with `--train-python` if needed; see [compatibility notes](THREE_TIERS.md). Do not assume an older converter supports this architecture.

Before a large batch, prepare English and do a disposable candidate run:

```bash
python3 llm-model-training/scripts/import_my_chats.py --language en_us
llm-model/.venv-train/bin/python llm-model-training/scripts/train_lora.py --config llm-model-training/configs/minicpm5-1b.json --language en_us --max-steps 5 --output llm-model/work/smoke/minicpm5-1b
llm-model/.venv-train/bin/python llm-model-training/scripts/train_lora.py --config llm-model-training/configs/lfm3-1.7b.json --language en_us --max-steps 5 --output llm-model/work/smoke/lfm3-1.7b
```

Use a new output directory for another smoke run. The low-level `train_lora.py` is the per-model training engine, not a second batch runner.

## Export tools

Reuse an installed Windows llama.cpp runtime with `prepare_export_tools.py` from WSL. This copies its quantizer and downloads matching converter sources; it does not build CMake:

```bash
python3 llm-model-training/scripts/prepare_export_tools.py
```

It defaults to the runtime configured in `fabric/run/config/smart_npc/ai.json`; use `--runtime /mnt/c/.../runtimes/llama-bBUILD-cpu-windows-x64-HASH` for a different instance. The runner automatically selects the newest prepared tool folder for the current OS and its matching export environment. Override with `retrain.py --llama-root PATH --export-python PATH` if needed. `--train-python PATH` overrides the CUDA environment. Existing b12180 defaults remain usable for Qwen2.5, but are **not presumed LFM3-compatible**; the batch checks converter support before starting training.

For a single smoke export:

```bash
python3 llm-model-training/scripts/export_gguf.py /path/to/current/llama-tools llm-model/work/smoke/lfm3-1.7b/merged --language en_us
```

Run with the prepared export Python. Keep the current publisher chat template. Confirm non-thinking JSON replies with the actual runtime before publishing or claiming a candidate is supported.

## Upload and checksum synchronization

Manually upload the final files to the existing language releases (`llm-en-us-v1.0`, `llm-zh-cn-v1.0`, `llm-vi-vn-v1.0`). English standard and Unfiltered share the English release. Then use the **same dataset flags**:

```bash
python3 llm-model-training/scripts/sync_model_releases.py --en_us --en_us_unfiltered
python3 llm-model-training/scripts/sync_model_releases.py --zh_cn
python3 llm-model-training/scripts/sync_model_releases.py --all
```

`--check` previews without writing; `--models` limits families; `--release-version 1.1` selects newer language tags. The sync verifies every selected local receipt/file and uploaded size/SHA before atomically changing the catalog. New families receive registry-specific license/runtime metadata, never a copied Apache license for LFM3. New or replaced weights show RAM as unmeasured until actually benchmarked. Other catalog entries remain intact. No script uploads or deletes GitHub assets.

Rebuild the mod after syncing. New tiers are visible before publication, but unavailable language rows stay disabled until verified release entries exist. The bundled catalog retains older model metadata for installed-model compatibility. `sync_model_registry.py --check` validates that the mod's generated tier resource matches the training registry.

Example final files per locale:

```text
llm-model/
  en_us_smartnpc-qwen2.5-0.5b-instruct-Q4_K_M.gguf
  en_us_smartnpc-minicpm5-1b-Q4_K_M.gguf
  en_us_smartnpc-lfm3-1.7b-Q4_K_M.gguf
  en_us_smartnpc-qwen2.5-0.5b-instruct-unfiltered-Q4_K_M.gguf
  en_us_smartnpc-minicpm5-1b-unfiltered-Q4_K_M.gguf
  en_us_smartnpc-lfm3-1.7b-unfiltered-Q4_K_M.gguf
  # Three zh_cn and three vi_vn standard files use the same base names.
```

## Authoring conversations

```toml
[[chat]]
scenario = "item_gift"
split = "train"
action = "GIVE_ITEM"
item = "apple"
count = "random"
inventory = { "minecraft:apple" = 3, "minecraft:golden_apple" = 2 }
player = "Have you got an apple I can have?"
npc = ["Sure bro, take some.", "Fine, don't waste them.", "Alright, delivery time lol"]
```

`npc` accepts a string or a list. Each answer is one example with shared context, action and emotion. Every editable train/validation block now contains at least ten replies; frozen test mirrors are unchanged. Keep replies short (usually 3–12 words, at most 100 characters), distinct, and separated by commas. Names, health, inventory, weather and history must agree with the answer. `recent_turns` contains at most two complete alternating `heard:`/`said:` pairs. Most replies are friendly or dry/funny; profanity is occasional, mostly in insult banter.

Vary the conversational response as well as its wording: a plain answer, a joke, a question, a boundary or a dry remark can fit the same decision. Keep every alternative consistent with the block's action and `interaction_result`: an accepted truce cannot threaten another hit, and a refused truce cannot promise to stop. Do not copy the same reply list into multiple blocks or create apparent variety only by reordering it or adding punctuation. Occasional text emotes such as `:))))`, `:O`, `><` and `T_T` fit the casual server-chat style; use them for the situation rather than on every line. Current authored sources have no identical lists after ignoring order and trailing emotes, with emotes in about 11% of replies.

Whole-server dialogue uses `audience = "group"` for player messages such as "anyone…", "who wants to…", "any takers?", "有人要…" and "谁想…". These examples are speech-only (`NONE`): public chat can express interest or refusal, but does not itself transfer items, start work, join a team or target a distant player. NPC starts use the event types `server_invitation`, `server_offer`, `server_request`, `server_observation`, `server_banter` and rare `server_insult`; responses use `server_chat_reply` with the other NPC's actual message in the event facts. Cover friendly and reluctant replies, and calm or hostile verbal responses to a roast. Do not invent seeing the remote speaker or receiving offered items.

Bare gift words such as `apple` search carried item registry paths; namespaced IDs match exactly. Random gift quantities compile to count zero, then the server chooses 1..min(stock,maxGiftCount). Numeric counts remain exact. Refusals use a separate `NONE` block without item/count. Nearby players can request stored-item gifts without leader/operator status; equipped gear is excluded. New configurations enable gifts; existing explicit `giftsEnabled: false` is preserved until an administrator enables it. Item policy, range, safe state and capacity checks still apply.

Current world context includes `weather` (`clear`, `rain`, `snow`), `exposed`, `thunder`, `visible_entities`, speaker `creative`, and NPC `cautious`. `interaction_result` records a real server decision such as `attack_accepted`, `attack_refused`, `insult_retaliation`, `insult_no_attack`, `peace_accepted`, `peace_refused`, or a confirmed observation. The model reacts with `NONE`; it does not choose arbitrary targets or grant itself combat permissions. Direct insults may cause controller retaliation, excluding cautious NPCs, allies and creative/spectator players and respecting disabled PvP. Fight invitations are optional; truces can be accepted/refused with or without a reply. Accepted NPC truces clear both combat targets for 60 seconds unless a new hit breaks the agreement.

New examples cover mob damage/deaths, sudden nearby arrivals, creative-mode requests, weather/time/shelter, spare bows and other gifts, visible/missing mobs, fight invitations, NPC/player/observer truces, chest loot/empty chests/owned-chest damage, bow aiming, witnessed fights and greeting exchanges. All are explicit editable TOML blocks in all three languages. Events use the production `AiEventPrompts.java` prompt through `scripts/event_prompt.py`, with source hashes recorded in preparation receipts. Re-import after changing prompts, then retrain; changing the data does not update an already-exported model.

Scenario IDs organize related situations and keep their paraphrases in one split. Frozen test mirrors cannot be edited into training. No finite file covers every conversation, and training cannot add gameplay absent from the controller. See [schema/context_schema.md](schema/context_schema.md) and [licenses-and-provenance.md](licenses-and-provenance.md).
