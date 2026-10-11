# EatHealingFoodGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/EatHealingFoodGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs swap healing food into the main hand, play eating behavior, heal or regenerate, then restore their previous held item.

## Activation

- Server side only.
- NPC health must be below 70%.
- NPC must not already be healing.
- `PlayerNpcEntity.getGapCooldown()` must be `0`.
- Requires a valid healing food item in the Player NPC inventory.

## Behavior

Consumes one food item from the inventory, shows it in the main hand, plays eating sounds and item use, applies the food result after 32 server ticks, then restores the old main-hand item through the guarded AI setter. Epic Fight uses one looping EAT_MAINHAND composite animation, stopped on completion or abort; repeated attack swings are not sent. The goal is not interruptible during its short eat timer. While this goal is active, `PlayerNpcEntity.equipBetterGearFromInventory()` does not auto-swap the main hand, so food is not replaced by a weapon or tool before the heal is applied. Vanilla `completeUsingItem` defers consumption while healing so the goal remains the sole owner of food consumption and effects. Missing/replaced food, no-AI, riding, or death aborts; stop is idempotent and drops only inventory remainders.

The Epic Fight attack/chase goals yield their LOOK/MOVE controls to eating and cancel the
interrupted combat animation. Patch guard/attack eligibility also rejects healing/item use.
The eating bridge additionally clears queued combat behavior and cancels any actual current
`AttackAnimation` on the server and client while healing. The client EAT animation uses a
composite layer, so starting it alone does not stop a surviving base-layer attack. This
cancellation is independent of whether the EAT asset is available and does not cancel stun
or death animations. Target distance never releases the healing lock: only the eating goal
owns movement during eating, and `stop()` restores its saved main-hand item before clearing
healing. Do not replace this saved item unconditionally with the cached weapon, which may
be stale or disarmed.

Vanilla melee already checks healing at goal admission, continuation, tick, and its damage
helper. `PlayerNpcEntity.doHurtTarget()` and `performRangedAttack()` also reject healing at
execution time, covering Epic Fight animation damage callbacks and delayed ranged calls.
Bow start/tick cannot replace food or advance an attack while healing. Starting healing
clears vanilla/Better Combat attack timers and swing state; the vanilla renderer suppresses
any remaining client swing while the synchronized healing flag is set.

`start()` synchronizes the separately registered `EpicFightCloneAnimations.EAT_MAINHAND`
StaticAnimation and its own `smart_npc:biped/living/eat_mainhand` asset and composite metadata.
Do not use native BIPED_EAT or MirrorAnimation for this mob. The animation loops with fixed-head
rotation and an upper-body mask. Healing is synchronized entity data, owned by the eating goal;
client motion uses that state and the held edible item rather than depending on vanilla use-item
packet timing. Server upkeep and `EpicFight.updateClientEatingAnimation` inspect the actual
standalone animation player and restart only a missing/interrupted loop. The client repairs the
composite EAT binding after equipment living-motion resets, in which ClientAnimator's
separate composite map is not included in `getLivingAnimations()`. Item-use end stops the actual
standalone animation locally; a healthy loop or its link transition is never restarted each tick.
Calls go directly to `compat.epicfight.EpicFight` behind an
`epicfight` `ModList` guard. Without Epic Fight, vanilla item-use rendering remains active.

Regular food directly sets health to current health plus exactly 4 HP, capped at max health, and does not add absorption. Golden apples still use their stronger direct heal/effect behavior.

Eating delegates danger selection and retreat movement to a retained `DangerousReactionAi`.
Primed TNT and igniting/swelling creepers take priority over the healing combat threat.
For health-related retreat, the helper selects a valid combat target or recent attacker;
CAUTIOUS NPCs can also use their existing bounded threat lookup. The old weaker-target
chase branch is removed: eating NPCs keep retreating from their valid threat until food use
finishes, rather than holding still at the ordinary ten-block danger margin or chasing while
attacks are locked. This is shared vanilla/Epic Fight navigation behavior.

Both enemy and explosive healing retreat use sprinting at `0.79D`, matching the corrected
low-health flee speed without extra velocity impulses. With no threat,
noncombat wandering remains at `0.55D`. The eating goal keeps MOVE/LOOK and ownership
of the food/hand and healing lock throughout these reactions; it still completes its
32-tick consumption rather than handing the item to another goal.

Threat scans use the helper's staggered 10-tick explosive safety cadence, and bounded
escape path planning is limited to at most two attempts per 20 ticks. It prefers grounded
four-block escape destinations directly away or to either side, then a random fallback.
Candidate order advances after failures so blocked early directions cannot starve the rest;
a safe unfinished route can resume after a navigation interruption without a new path search. Only retained
threat validation, use timer, sounds and movement/look updates run every tick. Noncombat
wander paths remain limited to one per 20 ticks. Stop clears retreat navigation and
restores sprint/hand state before releasing healing. Healing remains an unwrapped safety goal.

Stopping after food was equipped calls `PlayerNpcEntity.setGapCooldown()`, which uses the entity's tick-down `gapCooldown` API. `PlayerNpcEntity.tick()` decrements that field every server tick.
