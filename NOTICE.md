# Notices

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

## Bundled multi-loader libraries (26.3)

Both distributions include Architectury API 22.0.5 (LGPL-3.0). Fabric also includes
Forge Config API Port 26.3.1 (MPL-2.0), retaining its nested Night Config 3.8.4
libraries. Original notices/licenses remain in the nested jars.

- Architectury API source: https://github.com/architectury/architectury-api
- Forge Config API Port source: https://github.com/Fuzss/forgeconfigapiport

The Fabric ranged-bow movement adapter follows vanilla's ranged-bow goal for the
player-shaped mob; job eligibility and temporary equipment/restoration stay shared.
Epic Fight credits above describe historical code, not an active 26.3 integration.
