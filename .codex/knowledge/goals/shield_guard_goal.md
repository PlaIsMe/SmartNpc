# ShieldGuardGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ShieldGuardGoal.java`
- Registered only from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`, so it only runs when `epicfight_player_npc` is not loaded.
- Damage blocking runs through the vanilla/Forge `LivingEntity.hurt` pipeline. `PlayerNpcEntity` implements its shield durability and melee-disable hooks, which are otherwise absent on this pathfinder mob.
- Render support is in `FakePlayerRenderer`.

## Purpose

Lets Player NPCs use shields defensively during vanilla replacement combat.

## Behavior

If the NPC has a shield in the offhand or inventory, it can guard against the current target. Ranged threats using bows, crossbows, or projectile weapons are prioritized. The shield is temporarily placed in the offhand when needed, `startUsingItem(InteractionHand.OFF_HAND)` is called for the guarding pose, and movement is slowed to `0.45D`.

Incoming damage uses vanilla blocking eligibility, including the five-tick raise delay, horizontal facing, piercing-arrow exception, and `BYPASSES_SHIELD` damage tag. Forge `ShieldBlockEvent` can cancel or reduce blocking and control durability damage. This replaces the former early damage cancellation, which bypassed these hooks.

As for a vanilla player, blocked hits of at least 3 damage consume `1 + floor(damage)` shield durability. Breaking the actual used stack clears item use and the hand; the matching offhand cache follows its durability/break state. A melee attack whose held item answers Forge `canDisableShield` (including vanilla axes) lowers the shield and applies at least 100 ticks of guard cooldown. Goal stop preserves that cooldown. Shield eligibility uses the Forge `SHIELD_BLOCK` action.

Warden sonic boom bypasses blocking through vanilla damage tags; ordinary melee can wear out the shield. Unlike axe disabling, sonic boom does not need to lower the shield before dealing damage. Minecraft 1.20.1 Warden melee has no special shield-disable hook. The user clarified that the report concerns vanilla axes and sonic boom, not a modded shield bash. This does not change Epic Fight's separate guard/stamina behavior.

Blocked hits use vanilla entity events rather than an offhand attack swing. While using a shield, residual vanilla attack swings and Better Combat attack/body/item transforms cannot pull down the `BLOCK` arm pose. Both handedness variants keep the vanilla player blocking pose and item model.

Inspector state is `ai.player_npc.shield_guarding`.

Cooldown uses `PlayerNpcEntity.shieldGuardCooldown`.

Validation: inspect shield hits from front/back, axes, piercing arrows, Warden sonic boom, durability exhaustion and both player skin/arm variants in game. Compilation does not certify rendering or combat integration.

Approach movement retains the 10-tick combat repath cadence but uses a scoped `0.15F` path. Shield defense remains directly available without routine-worker ownership; bounding its synchronous path is local CPU protection, not job scheduling.
