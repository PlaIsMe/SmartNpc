# LowHealthFleeGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/LowHealthFleeGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()` at priority `1`.

## Purpose

Lets low-health Player NPCs with the `COWARD` characteristic quit combat and sprint away.

## Activation

- Health ratio must be at or below 40%.
- Requires `COWARD` at activation and throughout continuation. It is not assigned to any name
  automatically; NPCs without it no longer enter this goal. CAUTIOUS avoidance stays separate.
- Requires a living target.
- Does not run in water/lava or while mounted.
- If healing food is available, it sometimes declines so `EatHealingFoodGoal` can run at the same priority.

## Behavior

Clears the target, sets sprinting, pathfinds away from the threat, randomly jumps while fleeing, and marks AI state as `ai.player_npc.fleeing_low_health`.

Flee navigation uses speed `0.79D` with sprinting enabled. In the current Forge 1.20.1
movement code, `Mob.setSpeed()` sets both movement speed and forward input. With the NPC's
base movement attribute `0.35` and vanilla sprint multiplier `1.3`, navigation speed `1.0`
produces about 1.59 times a player's flat-ground sprint acceleration/speed. The flee-only
multiplier approximates `sqrt(0.13) / (0.35 * 1.3)`, matching ordinary player sprinting on
normal ground without changing shared NPC attributes or other goals. This is not exact
player physics parity across potion effects, terrain, and airborne movement.

Flee jumps use vanilla `JumpControl.jump()`. Do not call `PlayerNpcEntity.jump()` here:
that helper adds another `0.28-0.48` forward impulse after vanilla already applies its
`0.2` sprint-jump impulse, causing excessive speed on repeated jumps. Random jump cadence,
support placement, sprint animation, and goal cleanup remain intact.

The initial escape target follows vanilla `PanicGoal`/random-away shape. Unlike vanilla's single retained path, this custom goal may adjust away from the moving threat on its controlled cadence, so every initial/recalculated route uses `PathNavigationAi.createBoundedPath(..., 0.15F)`. Starting the goal initializes the repath timer after the first route; it must not immediately create a duplicate path on the following tick.

Activation probes are staggered at >=20 ticks and running repaths are at least 20 ticks apart.
Each actual grounded fleeing jump rolls exactly 30% for support placement. On success, an
ordinary carried full block is equipped through `ToolAi` before the jump. The old feet cell is
retained for at most ten airborne ticks with bounded horizontal drift; placement waits for the
real bounding box to clear the full block, including `PlacingBlockAi`'s collision margin. An
early expected overlap waits rather than discarding the attempt. The live target must remain
air over a fluid-free sturdy support, loaded and within world border, outside home/build and
farm protection. Actual full collision shape and entity overlap are checked before commit;
unsupported/floating placement and soft-cover replacement are rejected. Only successful
`placeHeldBlock` consumes one carried block and emits its single use signal. All success,
failure, landing, timeout, and goal-preemption paths restore the original held item. The goal
keeps MOVE/LOOK/JUMP ownership while attempting the jump, and entity auto-equipping must honor
its temporary hand lifetime.
