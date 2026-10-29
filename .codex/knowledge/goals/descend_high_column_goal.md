# DescendHighColumnGoal

Active resource-gathering delegates (including DigDown and both scheduling wrappers) block
activation through `isGatheringJobRunning()`. This runtime guard supplements the idle/return
state filter so a diagnostic-state change cannot authorize descending during intentional digging.
Successful DigDown-to-mining handoffs also retain the same local 60-server-tick reservation
checked by hole escape, covering the selector gap before the next work goal starts.
For natural terrain, validate narrow-column geometry and nearby lower walkable terrain before
checking/reporting an unsafe landing. An ordinary dirt hillside is not a failed pillar descent.
Owned temporary supports retain their direct landing check and all existing protection guards.

Generic descent may remove a safe narrow natural column or a remembered temporary support outside
protected home/foundation/farm areas. Worker ownership and vertical recovery handoffs remain guarded.

Every step must validate the prospective landing body column, even when another owned temporary
support is underneath. The current feet being outside a hole does not prove the lower landing is
outside it. In the 2026-09-06 09:24-09:26 trace the builder had stone 22/20 and no upward request;
Descend removed dirt at -86,95,135 and generic EscapeHole replaced that same block indefinitely.
The stacked-support shortcut had skipped landing-exit validation.

Reject a landing with the same enclosure evidence that stops descent when the NPC is already inside
a hole. Open stacked columns remain descendable; removing their final support additionally requires
an adjacent open-sky stand. Validate loaded state and support below, and recheck the landing during
breaking and in its final target predicate so terrain changes after activation cannot reopen the pit.
Failure stops breaking, retains the support, and leaves normal work goals eligible; never request a
new climb or add a cooldown merely to hide this cycle. The idle trace identifies the unsafe landing.

Descent also yields to a real uphill builder-home return. The shared cheap predicate covers an active
return state, night/thunder shelter return, explicit exploration recovery, completed downhill stone
trips, and below-home surface recovery when the saved home is above the NPC. Admission, continuation,
the running tick, and the final break commit all recheck it. This preserves the support across brief
idle or cooking interruptions at night without disabling ordinary daytime descent during log work.
