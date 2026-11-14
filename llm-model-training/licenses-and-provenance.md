# Provenance

Current three-tier roster (2026-11-11): Qwen2.5 0.5B and MiniCPM5 1B use Apache-2.0 publisher weight licenses. LFM3 1.7B uses the separate **LFM Open License v1.0**, including its commercial-use provisions; do not label derived LFM3 weights Apache-2.0. Exact source revisions and publisher references are recorded in [THREE_TIERS.md](THREE_TIERS.md) and the model configs. Historical model notes below remain for archived artifacts. Model-weight terms are separate from the mod's code license.

The 120 starter rows are original synthetic examples authored for this task on 2026-11-06.
They contain no collected player chat. Starter example content is dedicated under CC0-1.0;
the surrounding software follows SmartNpc's GPL license. Every row carries provenance.
The small starter set is for pipeline development and editing, not a claim of model quality.

Additional witnessed-death examples are original synthetic CC0-1.0 content in
`data/clean/witness_chats.jsonl`. `data/my_chats_en_us.toml` is the active English source;
imported user examples carry a user-authored provenance label. Event dialogue imported
from `common/src/main/resources/data/smart_npc/chat/en_us` retains the repository GPL
provenance and exact source path/index per row. Preserve that attribution and review the
terms when publishing training data or derivatives. The importer skips obsolete inactive
team-up lines and reports omissions/deduplication; it does not collect live player chat.

The active English source combines retained authored/resource examples with user edits and English gift/refusal additions. Previous multilingual sources are historical archives and are not appended to training. User edits retain their content and provenance fields; review their rights before redistribution. No examples are collected automatically from live chat.

Base models SmolLM2-360M-Instruct, Qwen2.5-0.5B-Instruct, Qwen3-0.6B and Qwen3.5-0.8B
have Apache-2.0 model cards at the linked publisher pages in the tutorial. Preserve the
base license, notices and exact revision alongside adapters/merged weights.

The training script is independently written against public toolkit APIs. It does not
copy the Unsloth notebook (whose source license is LGPL-3.0). Toolkits are installed in
the developer training environment, not bundled into the mod. Keep their notices if
redistributing them. No LlamaJar source, binaries, native libraries or datasets are imported.

Add consent records and redact identifiers before incorporating actual player messages.
Do not put private chat, access tokens, filesystem paths or server credentials in examples.


## Chinese localization

The zh_cn source was initialized locally with [Helsinki-NLP/opus-mt-en-zh](https://huggingface.co/Helsinki-NLP/opus-mt-en-zh), revision 408d9bc410a388e1d9aef112a2daba955b945255, using CTranslate2 CPU inference and Simplified Chinese conversion. The translator model is Apache-2.0; its cached weights and isolated dependencies are not distributed with this repository. Chinese action/fact/event replies and important prompt wording were then semantically post-edited to preserve the game contract and conversational style. Existing source/license attribution is retained. data/translation_zh_cn.json records provenance and flags release language review as incomplete. The Chinese evaluation is a separate localized version; English frozen bytes remain unchanged.
