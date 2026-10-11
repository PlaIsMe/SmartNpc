# Dangerous reaction retreat

This note describes the Minecraft 26.1.2 / NeoForge 26.1.2 implementation in `entity/goal/DangerousReactionGoal.java` and `entity/ai/DangerousReactionAi.java`.

The Annoying Villagers danger-reaction classes were used only as a design reference for separating danger detection from movement. Smart NPC has no dependency on that mod and does not copy its custom animations, velocity impulses, delayed jumps, or wall placement.

`DangerousReactionGoal` owns MOVE and LOOK at priority 0 while a primed TNT or an ignited/swelling creeper is near. Normal creepers use a six-block danger radius, charged creepers ten blocks, and TNT eight blocks. The goal keeps a two-block continuation margin to avoid rapid start/stop cycles. It preserves the NPC's combat target and yields after the explosive is far enough away.

`EatHealingFoodGoal` owns movement while healing and calls the same helper directly. Explosives take priority over the current combat target or recent attacker. If no explosive is urgent, a healing NPC retreats from that living threat until eating completes. CAUTIOUS NPCs may also use their existing bounded nearby-threat lookup. Allies and creative/spectator players are excluded.

Explosive queries inspect loaded entities in a 16-block box on a UUID-staggered ten-tick cadence. Retreat planning tries at most two bounded paths per 20 ticks. It prefers grounded positions four blocks away in a fan opposite the threat, then uses a random-away fallback. A candidate and its real path endpoint must increase distance and avoid moving closer to another active explosive. An interrupted valid path may resume without another search.

Retreat navigation uses sprinting at `0.79D`, matching the corrected low-health flee speed. Failure stops stale navigation and reports a blocked retreat in the inspector detail. Epic Fight is disabled in this checkout; the behavior applies to the active vanilla/Better Combat combat path.
