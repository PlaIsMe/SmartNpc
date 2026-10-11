# EatHealingFoodGoal

## Source and registration

- `src/main/java/com/pla/smart_npc/entity/goal/EatHealingFoodGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()` at priority `1`.

## Activation and ownership

The server-side goal starts below 70% health when healing food is available and `gapCooldown` is zero. It owns MOVE and LOOK and is not interruptible during its 32-tick eating cycle. It removes one selected food item from inventory, saves the current main hand, equips the food through the guarded AI setter, starts item use, and sets the synchronized healing flag.

While healing, automatic equipment upgrades and combat weapon recovery respect the reserved main hand. Melee admission and continuation reject healing, `PlayerNpcEntity.doHurtTarget()` rejects delayed melee damage, bow start/tick cannot replace the food, and `performRangedAttack()` rejects delayed shots. Starting healing also clears vanilla and Better Combat attack timers and swing state. The 26.1 render-state path suppresses any remaining attack swing while the healing flag is set.

Epic Fight is disabled in this checkout. Eating uses normal item-use rendering and the active vanilla/Better Combat compatibility path.

## Movement and danger response

The goal retains MOVE/LOOK while eating and delegates threat selection and retreat routing to `DangerousReactionAi`. Primed TNT and ignited/swelling creepers take priority. Otherwise it retreats from the current combat target or recent attacker; CAUTIOUS NPCs may select their nearby threat through the existing bounded scan. The NPC keeps the combat target locked but cannot attack until eating finishes.

Threat retreat sprints at `0.79D`. Path planning attempts at most two bounded routes per 20 ticks, prefers grounded four-block steps away from danger, and can resume a retained safe route after navigation interruption. When no threat exists, the NPC chooses a bounded random movement destination at `0.55D` rather than standing still.

## Completion and cleanup

Regular food restores exactly 4 health, capped at maximum health. Golden and enchanted golden apples keep their explicit stronger healing and effect branches. On success or interruption, the goal stops item use and retreat navigation, restores its saved main-hand stack, restores the prior sprint state, clears healing, and applies the entity's tick-down food cooldown. Unfinished food is returned to inventory or dropped only as an insertion remainder.
