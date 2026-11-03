# Notices

## Bundled loader libraries

Both distributions include Architectury API 13.0.11 (LGPL-3.0). Fabric also includes
Forge Config API Port 21.1.6 (MPL-2.0), retaining its nested Night Config dependencies.
Original metadata and license files remain inside the nested jars.

- Architectury API source: https://github.com/architectury/architectury-api
- Forge Config API Port source: https://github.com/Fuzss/forgeconfigapiport

## Fabric bow movement

The Fabric bow movement adapter follows Minecraft 1.21.1's `RangedBowAttackGoal`,
generalized for the NPC's `PathfinderMob` hierarchy. NeoForge uses its existing native
bow goal. The NPC's eligibility, temporary equipment and restoration remain shared.


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