# Current training review

The active English dataset is `data/my_chats_en_us.toml`: 3,277 expanded examples / 121 scenarios, split 3,180 train / 89 validation / 8 frozen test. Non-English examples and cross-language negotiation scenarios were removed from the active data. User-authored rude replies/emoticons remain. All 13 currently offered action types have training labels; validation remains sparse for several actions.

Gifts now include item-word searches, exact IDs, random/exact counts, modded carried items, missing inventory and rude refusal. Refusal uses NONE. The server enforces permissions, distance, safe state, stock, item policy and full capacity; random quantities are chosen by Java. A model label never creates items or implements attack behavior.

Preparation/training/export are language-specific. The model language is recorded in the training receipt and experiment metadata, then used as the GGUF filename prefix. The UI supports English and registered Chinese model releases, with Vietnamese disabled. A separate Chinese translation draft is prepared locally with preserved machine fields and independent evaluation mirrors; its language/tone still needs human review. Historical starter/evaluation bytes are preserved; archives do not enter the active pipeline.

See [DATA_USAGE_en_us.md](DATA_USAGE_en_us.md) and [SCENARIOS_en_us.md](SCENARIOS_en_us.md). No fine-tuning, native inference benchmark or gameplay validation was performed for this update. Compilation and data/schema checks establish integration consistency, not learned quality. The eight frozen tests are a historical pipeline check; independently evaluate the exported model for dialogue quality and gameplay correctness.


Training code is now at the repository root under llm-model-training. Original model snapshots live in llm-model/import, runs in llm-model/work, and GGUF exports in llm-model/export. Large artifacts are ignored by Git. Each model/language trains independently from its local original-weight snapshot. No GPU fine-tuning was started during the reorganization.
