# PlayerNpcMeleeAttackGoal

Registered at priority 6 only when Epic Fight is absent, below priority-5 obstruction breaking.
Admission and the damage helper also
reject Epic Fight. This replaces the generic vanilla MeleeAttackGoal and owns MOVE/LOOK/JUMP;
the separate RandomCombatJumpGoal is retained only for Epic Fight so it cannot interrupt a
vanilla critical jump. Cautious NPCs still refuse combat. Healing and higher-priority survival,
shield, ranged, escape, and item-use goals preempt this goal normally.

Target must be alive, non-allied, and not a creative/spectator player. Swings require line of
sight and at most 3 blocks of entity-position distance. Cheap look/timer/jump checks run each
tick; pursuit creates at most one bounded 0.15 path per admitted staggered 20–25-tick window,
reusing a live path while its target stays nearby. No routine worker lease is needed.

CombatMovementAi now shares pursuit with Epic Fight's AdvancedChasingGoal. Inside the
3-block attack range, NPCs sometimes approach for 8-13 ticks, with 24-40 ticks between
decisions and a body-width-based stopping distance. Outside range (or without line of
sight), pursuit is unconditional and independent of attack cooldowns. Path creation remains
budgeted, loaded-chunk bounded, and spaced by 20-25 ticks, including goal restarts.
There are no extra velocity or sprint boosts. Epic Fight keeps its movement goal admitted
through attack animations but stops navigation during movementLocked frames; it respects
turningLocked and all existing utility, guard, stun, and healing combat locks. Its approach
range now matches generated attacks (3 blocks). Installed Epic Fight 8049910 exposes both
movementLocked() and turningLocked(); these are distinct from the broader inaction state.

Normal swing delays are 8–16 server ticks. After a swing, a 30% roll can start 2–4 fast
intervals of 3–6 ticks. The next-swing deadline survives preemption. Vanilla hurt immunity
remains intact: rapid swings do not force damage through invulnerability frames.

An eligible grounded attack has a 20% chance to attempt a critical jump. JumpControl supplies
the physical jump; the selected target is retained for at most 20 ticks. The critical requires
actual descent, positive fall distance, range, and line of sight, with no water/lava, climbing,
blindness, or levitation. Landing early, losing the target, or timeout cancels the attempt.

VanillaMeleeAttackAi applies a temporary 1.5x ATTACK_DAMAGE modifier only around doHurtTarget,
removing it in finally. Armor, enchantments, weapon durability, and normal hurt handling remain
in place. Successful critical damage emits CRIT particles and the vanilla critical sound.
A raised ShieldItem is disabled before critical damage: real players use disableShield(true)
(100 ticks), Player NPCs receive at least 100 shieldGuardCooldown ticks, and other shield users
have their active use interrupted. This disables blocking; it does not destroy the shield item.
ShieldGuardGoal honors externally applied cooldown during continuation/tick and preserves it
on stop, preventing immediate re-raising after a critical.
