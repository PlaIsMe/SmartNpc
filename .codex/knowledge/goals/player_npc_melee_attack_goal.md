# PlayerNpcMeleeAttackGoal

This goal is registered at priority 6 and owns MOVE, LOOK, and JUMP. Cautious NPCs refuse combat. Healing, item use, sleep, obstruction clearing, ranged combat, and higher-priority safety goals prevent or preempt it.

Targets must be alive, non-allied, and not creative/spectator players. Attacks require line of sight and at most three blocks of entity-position distance. `PlayerNpcEntity.doHurtTarget()` independently rejects healing so a delayed damage call cannot land during food use.

`CombatMovementAi` owns paced pursuit. Outside attack range or without line of sight, it keeps pursuing independently of attack cooldowns. Inside attack range, it has a 70% chance every 24-40 ticks to take a brief 8-13-tick forward step, stopping at a body-width-based separation. This prevents the NPC from repeatedly swinging at the edge of range and waiting for the opponent to approach. It adds no velocity or sprint boost. New bounded paths are budgeted and spaced by 20-25 ticks; a retained route may resume after a movement interruption.

Normal swing delays are 8-16 server ticks. After a swing, a 30% roll can start 2-4 fast unarmed intervals of 3-6 ticks. The next-swing deadline survives preemption, and vanilla hurt immunity remains intact.

Eligible grounded attacks have a 20% chance to attempt a critical jump. The hit waits for actual descent and positive fall distance, with normal range, sight, fluid, climbing, blindness, and levitation checks. `VanillaMeleeAttackAi` temporarily applies the 1.5x attack-damage modifier only around the damage call and removes it in `finally`. A successful critical emits the vanilla effects and can disable an actively raised shield.

Epic Fight is disabled in the Minecraft 26.1.2 checkout. Better Combat integration remains visual; server reach and damage use this goal.
