# ShieldGuardGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ShieldGuardGoal.java`
- Registered only from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`, so it only runs when `epicfight_player_npc` is not loaded.
- Damage blocking runs through the vanilla/NeoForge `LivingEntity.hurt` pipeline. `PlayerNpcEntity` applies blocking-item durability and attack-triggered disabling because the 26.1.2 `BlocksAttacks` durability path only handles `Player` instances.
- Render support is in `FakePlayerRenderer`.

## Purpose

Lets Player NPCs use shields defensively during vanilla replacement combat.

## Behavior

If the NPC has a shield in the offhand or inventory, it can guard against the current target. Ranged threats using bows, crossbows, or projectile weapons are prioritized. The shield is temporarily placed in the offhand when needed, `startUsingItem(InteractionHand.OFF_HAND)` is called for the guarding pose, and movement is slowed to `0.45D`.

Incoming damage uses vanilla blocking eligibility, including the blocking component raise delay, horizontal facing, piercing-arrow exception, and bypass damage tags. NeoForge `LivingShieldBlockEvent` can cancel or reduce blocking. The former early damage cancellation bypassed these hooks.

Blocked hits consume durability according to the shield?s `BlocksAttacks.itemDamage` component. Breaking the actual used stack clears item use and the hand; the matching offhand cache follows its durability/break state. A melee attacker with positive `getSecondsToDisableBlocking()` (including vanilla axes) lowers the shield and applies the component-scaled guard cooldown. Goal stop preserves that cooldown. Shield eligibility uses `DataComponents.BLOCKS_ATTACKS`.

Warden sonic boom bypasses blocking through vanilla damage tags; ordinary melee can wear out the shield. Unlike axe disabling, sonic boom does not need to lower the shield before dealing damage. Warden melee reports a five-second blocking disable in this version, so it lowers an NPC shield on a successful block. The user clarified that the report concerns vanilla axes and sonic boom, not a modded shield bash. This does not change Epic Fight's separate guard/stamina behavior.

Blocked hits use vanilla entity events rather than an offhand attack swing. While using a shield, residual vanilla attack swings and Better Combat attack/body/item transforms cannot pull down the `BLOCK` arm pose. Both handedness variants keep the vanilla player blocking pose and item model.

Inspector state is `ai.player_npc.shield_guarding`.

Cooldown uses `PlayerNpcEntity.shieldGuardCooldown`.

Validation: inspect shield hits from front/back, axes, piercing arrows, Warden sonic boom, durability exhaustion and both player skin/arm variants in game. Compilation does not certify rendering or combat integration.

Approach movement retains the 10-tick combat repath cadence but uses a scoped `0.15F` path. Shield defense remains directly available without routine-worker ownership; bounding its synchronous path is local CPU protection, not job scheduling.
