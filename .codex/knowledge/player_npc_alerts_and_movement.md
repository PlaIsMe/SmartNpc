# Player NPC Alerts And Movement

September 9 combat/eating regressions: equipment updates that only change stack count or Damage
must preserve the current AdvancedCombatBehaviors instance and living motions. Real item or
other NBT changes still rebuild presets. Durability damage on a successful hit previously removed
the attack goal, canceling its animation midway. A killing hit also retains the owned animation
through completion when the target disappears; utility preemption still cancels it. Epic Fight's
damage callback does not trigger the separate vanilla/Better Combat attack signal. Combo timing
continues to use Combat Evolution's entity-state rules, with no phase/recovery-time shortcut.

Healing now synchronizes entity data and plays the registered standalone EAT_MAINHAND loop with
its own resource and composite metadata. Native BIPED_EAT/MirrorAnimation is not used. CAUTIOUS
NPCs reject combat targets and the advanced patch disables attack/chase/guard selection so
avoidance owns threat movement even between goal activations.

Low-health combat withdrawal now requires COWARD. The generic flee-health ratio and
shouldSmartNpcFleeFromTarget return no flee decision for other personalities, including
compatibility high-danger thresholds. CAUTIOUS remains its separate always-avoid behavior.
Without Epic Fight, PlayerNpcMeleeAttackGoal replaces generic melee with randomized 8–16-tick
swings, occasional 3–6-tick bursts, and physical critical jumps. See its focused goal note for
range, damage, particles, shield-disabling, and priority ownership. Epic Fight combat is unchanged.

## Death Alerts

`ChatUtil.warnDeath` uses wildcard/default entries only for player-like killers:

- `net.minecraft.world.entity.player.Player`
- `PlayerNpcEntity`

Other entity types only receive custom `warn_death` or `death_reaction` chat when a
datapack entry explicitly targets that entity's namespaced registry ID. This supports
modded threats without making wildcard/default griefing lines fire for ordinary monster
kills. With no explicit match, the NPC keeps the vanilla death summary.

Death event chat is emitted through `ChatUtil.reportDeath`; `PlayerNpcAlertManager.raiseDeathAlert`
owns only the nearby AI alert. `PlayerNpcEntity` still gates the AI death alert to
player-like killers, so an explicitly configured modded-mob chat reaction does not
silently change nearby NPC combat/avoidance behavior.

## Movement Speed Cap

Use movement speed `1.0D` as the maximum requested path/navigation speed for Player NPC goals.

Known examples:

- `RecoverWeaponInCombatGoal` is registered on Player NPC at `1.0D`.
- vanilla `MeleeAttackGoal` is registered on Player NPC at `1.0D`.
- `PlayerNpcRangedBowAttackGoal` is registered on Player NPC at `1.0D`.
- `LowHealthFleeGoal.RUN_SPEED = 0.79D` while sprinting (avoids compounding mob forward input).
- `RespondToNpcAlertGoal.AVOID_SPEED = 1.0D`

Do not raise Player NPC run, flee, avoid, chase, recover, or combat navigation to `1.25D+`.

Eating uses the shared `DangerousReactionAi`: ordinary healing retreat and imminent explosive avoidance both use corrected sprinting (`0.79D`). A valid healing threat keeps retreat active through eating rather than stopping at the ordinary safe-distance margin. Healing retreat no longer chases weaker enemies. `DangerousReactionGoal` handles explosives outside eating and yields once safely away; eating retains its own MOVE/LOOK and food lifecycle.

## Epic Fight Combat And Utility Arbitration

`AdvancedPlayerNpcPatch` installs the animation attack at priority 6 with LOOK and
chasing at priority 6 with MOVE (speed 1.0). They can run together, but the existing
MOVE/LOOK safety and utility goals preempt both, including eating, obstruction
breaking, hole escape, buckets, flint and steel, and combat item use. Do not restore
the flagless priority-0 attack or priority-1 chase: the 2026-09-08 trace demonstrated
simultaneous eating/attacking and chase starvation of hole escape.

Stopping `AdvancedAnimationAttackGoal` clears the combo and synchronously stops its
owned animation, including remaining recovery frames. It does not cancel an
animation that replaced it (stun/execution), or an external combat-action lock.
Patch guard/attack/chase eligibility also checks running utility controls and
healing, item use, digging, sleep, and the remaining USE_MAINHAND animation. These
checks inspect selector state only; never call goal eligibility or pathfinding.

`triggerMainHandUseAnimation` plays one Epic Fight USE_MAINHAND animation, falling
back to one vanilla swing without Epic Fight. Atomic uses signal only after a
successful commit. Eating uses a separately stopped looping EAT_MAINHAND composite
animation that is asserted on every active eating tick so slow movement can continue; utility
animation startup cancels local guard. All calls use `compat.epicfight.EpicFight` directly after an
`epicfight` ModList check; do not reintroduce reflection or cached compatibility hook methods.
Other AdvancedMobPatch types retain their original goal priorities/controls.

Generated weapon-capability attacks and roots added by `addCustomBehaviorRoots` are scheduled by
one `AdvancedCombatBehaviors` instance with one current behavior. Normal chaining follows
`reference/CombatEvolution-1.20`'s `CECombatBehaviors`: a new root starts only when `inaction` is
false; an animation with child behaviors advances when `canBasicAttack` is true; a terminal
animation finishes when `inaction` is false. Do not inspect final `AttackAnimation.Phase` recovery
times or substitute server animation emptiness for these normal entity-state windows. Existing
explicit `waitForAnimationCompletion` roots remain reserved for standalone compatibility actions.

The AdvancedMobPatch local weapon guard is separate from `ShieldGuardGoal`. While the capability
BLOCK animation owns the local guard, a direct blockable Epic Fight hit returns BLOCKED with zero
damage, consumes stamina on a successful hit, and notifies the attacker patch. Unblockable, guard
puncture, and invulnerability-bypassing sources retain their Epic Fight behavior. A real stun clears
the local guard so its flag cannot survive into a later damage event.

Player NPC jump helpers should stay player-like. `PlayerNpcEntity.jump()` and `shortPillarJump()` use vanilla-style `0.42D` vertical lift; do not raise this back to high values that make the NPC look like it can jump around two blocks.

`shortPillarJump()` validates server side, life, AI, passenger and grounded state, but does not
require a routine worker lease. Owning goals/helpers enforce their own work admission: emergency
hole escape is allowed without a worker slot, while routine `PillarUpAi` still checks its lease.
Requiring the lease again inside the physical jump left emergency NPCs holding a block and
looking down through three failed jump retries without ever leaving the ground.

`isMainHandReservedForAi()` protects temporary tool/block use from both automatic equipment and
`RecoverWeaponInCombatGoal`. Running hole escape, obstruction clearing, flint use and fleeing
are checked by goal lifecycle, alongside healing and existing work states. Damage can overwrite
the inspector state with retaliation, so the inspector string alone must not release their hand
reservation. Recovery must not preempt escape during a transient empty hand between utility swaps.

## Cooldown Style

Retaliation ownership outlasts vanilla's recent-damage reference. Passive-target cleanup must
accept the current target while `HurtByTargetGoal` remains running, as well as when it matches
`getLastHurtByMob()`. Otherwise non-HUNT_PLAYERS NPCs (including the Philza fishing trace on
2026-09-09 at 23:31) repeatedly have a legitimate defensive target restored by the target selector
and erased by post-tick cleanup: logs show target=none with melee/obstruction still running, and
mining never accumulates progress. Death, alliance, target-goal termination and stale-combat
cleanup retain their normal behavior; this does not grant proactive hunting to non-hunters.

Player NPC AI goals should use explicit tick-down fields on `PlayerNpcEntity` for per-NPC cooldowns. Do not add new `getPersistentData().putLong("PlayerNpc...Cooldown", gameTime + ticks)` cooldown tags for Player NPC goals.

Existing AI cooldown fields are saved on the entity and decremented from `PlayerNpcEntity.tick()` through `tickAiCooldowns()`.
