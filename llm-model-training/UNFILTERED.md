# English (Unfiltered)

Edit [data/my_chats_en_us_unfiltered.toml](data/my_chats_en_us_unfiltered.toml).
This is a separate optional English model style, with the same gameplay contexts,
actions and scenario membership as the reviewed English source. Each of its 852
editable blocks has ten distinct replies: six casual/friendly and four rude or
profane. There are 220 scenarios, 7,630 train examples and 890 validation examples.
The eight original frozen English evaluation cases are retained separately and
never trained. Short emotes and slang are mixed with blunt refusals, jokes and
swearing, including when a player is rude or asks the NPC to come closer.

The 40% figure describes the dataset, not a runtime guarantee that four out of
every ten generated messages swear. Sampling, model size, personality, context
and previous replies affect actual output. Greetings can be friendly, rude, or
playfully profane. Insults remain directed at fictional players and gameplay;
there are no protected-group slurs or encouragement of real-world harm.

Player messages, inventory, item quantities, actions, world facts and confirmed
combat/truce decisions are preserved. An accepted truce does not threaten another
attack; a cautious NPC can insult someone without attacking. Rude gift refusals
use NONE. Approved gifts still give the labelled item/quantity. Generated jokes
do not grant new griefing or combat abilities.

## Train, export and publish

The same unified runner handles standard and Unfiltered English:

```bash
python3 llm-model-training/scripts/retrain.py --en_us_unfiltered
python3 llm-model-training/scripts/retrain.py --en_us --en_us_unfiltered
python3 llm-model-training/scripts/retrain.py --en_us_unfiltered --resume
python3 llm-model-training/scripts/retrain.py --en_us_unfiltered --dry-run
```

Three tiers: Qwen2.5 0.5B, MiniCPM5 1B and LFM3 1.7B. The last two are candidates pending small-step training/export/runtime validation. `--models qwen2.5-0.5b` selects one tier. See [README.md](README.md) for current environment requirements, tool paths, resume behavior and the separate per-dataset receipts.

Upload the three `en_us_*-unfiltered-Q4_K_M.gguf` files to `llm-en-us-v1.0`, then run:

```bash
python3 llm-model-training/scripts/sync_model_releases.py --en_us_unfiltered
```

This validates actual local exports and published SHA-256 values before adding releases to the catalog. The language picker shows English (Unfiltered) in red; it stays unavailable until the family has a published verified Unfiltered asset. Choosing an installed language activates it; choosing a missing published language downloads and activates it.

## Tone references and provenance

Public examples illustrate short slang, interruptions, emoticons and competitive
trash talk; they are not demographic evidence about children or a measured 40%
swearing rate. The 40/60 mix is the author's requested style. All training replies
are authored dialogue; no player comments or logs were collected as a dataset.

- [Minecraft discussion using "bro", "wtf" and "lol"](https://www.reddit.com/r/Minecraft/comments/15xsaa1)
- [Competitive Minecraft discussion with slang and emotes](https://www.reddit.com/r/CompetitiveMinecraft/comments/pdq244)
- [Players discussing Minecraft PvP trash talk](https://www.reddit.com/r/MinecraftPVP/comments/1uibjx5/why_are_people_in_the_pvp_community_so_mean_all/)

Dataset validation checks action preconditions, split leakage, duplicate examples,
reply lengths and minimum reply counts. Coverage statistics are not proof of a
trained model's quality; compare normal English and Unfiltered on unseen chats.
