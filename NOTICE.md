# Notices

## Bundled libraries

Both distributions include an unmodified nested jar of their loader's Architectury API
9.2.14 (LGPL-3.0). Fabric additionally bundles Forge Config API Port 8.0.3 (MPL-2.0).
Their original metadata and license notices remain inside the nested jars.

- Architectury API source: https://github.com/architectury/architectury-api
- Forge Config API Port source: https://github.com/Fuzss/forgeconfigapiport

## MineColonies and Structurize

PlayerNpc's `.blueprint` structure-loading support is inspired by and format-compatible with the Structurize blueprint format used by MineColonies.

- MineColonies: https://github.com/ldtteam/minecolonies
- Structurize: https://github.com/ldtteam/Structurize

MineColonies and Structurize are GPL-licensed projects. PlayerNpc is distributed under GPLv3 in `License.md`.

The PlayerNpc builder does not embed MineColonies' colony, work-order, citizen, farming, fishing, or mining AI systems. The PlayerNpc implementation keeps those AI goals native to this mod. The `.blueprint` reader ports the small Structurize v1 compressed-NBT palette/block-index decoding behavior needed to read scanned structures, and the code comments identify the Structurize reference.

## Player Mob

Credit to the Player Mob mod for the player skin fetching approach used as a reference for Minecraft profile/skin lookup behavior.

- Player Mob repository: https://github.com/GoryMoon/PlayerMobs
- Owner: GoryMoon

### Combat Evolution

Custom EpicFight mobpatch that having builder root design. The mod distributed under GPLv3
- Source code: https://www.curseforge.com/minecraft/mc-mods/combat-evolution
- Owner: ShelMarow

## llama.cpp

Credit to llama.cpp for the embedded local AI runtime. Distributed under MIT.

- Source code: https://github.com/ggml-org/llama.cpp
- Owner: Georgi Gerganov and llama.cpp contributors

## Qwen

Smart NPC chat models are fine-tuned from Qwen2.5-0.5B-Instruct and Qwen3.5-0.8B, distributed under Apache License 2.0.

- Qwen2.5: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct
- Qwen3.5: https://huggingface.co/Qwen/Qwen3.5-0.8B
- Owner: Qwen team, Alibaba Cloud

## llama.jar

Credit to llama.jar for the embedded inference approach used as a reference.

- Mod page: https://modrinth.com/mod/llama.jar
