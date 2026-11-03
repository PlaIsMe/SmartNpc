# PlayerNpcRangedBowAttackGoal

## Source

- `common/src/main/java/com/pla/smart_npc/entity/goal/PlayerNpcRangedBowAttackGoal.java`
- `common/src/main/java/com/pla/smart_npc/entity/goal/NpcRangedBowGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs use bows from their inventory in vanilla combat.

## Activation

- Requires a live combat target.
- Requires bow availability and arrow ammo.
- Does not run while healing, burning, in lava, or while bow use is on cooldown.
- Its priority-2 attack waits while `isClearingCombatObstruction()` reports actual running
  obstruction recovery, including nested tool crafting. Shared activation/continuation
  eligibility plus start/tick guards prevent bow swaps or shots from preempting that work.

## Behavior

Temporarily equips a bow from inventory if needed, fires using vanilla ranged-bow goal behavior, consumes arrows through `InventoryUtils`, then restores the previous main-hand item and starts swap cooldown.

The multi-loader port uses `NpcRangedBowGoal` for vanilla-style movement, visibility,
strafing and charging. Fabric's vanilla `RangedBowAttackGoal<T>` requires a `Monster`;
the NPC is a `PathfinderMob`. The portable superclass preserves those mechanics without
changing the NPC's entity inheritance. Projectile creation, ammo consumption and Forge
custom-arrow hooks remain in `PlayerNpcEntity` and the platform adapter.
