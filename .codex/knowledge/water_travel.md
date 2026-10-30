# Player NPC Water Travel

## Source

- `src/main/java/com/pla/smart_npc/entity/ai/WaterEscapeAi.java`
- `src/main/java/com/pla/smart_npc/entity/ai/WaterTravelAi.java`
- `src/main/java/com/pla/smart_npc/entity/goal/PlayerNpcFloatGoal.java`
- `src/main/java/com/pla/smart_npc/entity/ai/PathNavigationAi.java`
- `src/main/java/com/pla/smart_npc/entity/PlayerNpcEntity.java`
- `src/main/java/com/pla/smart_npc/client/renderer/FakePlayerRenderer.java`
- `src/main/java/com/pla/smart_npc/client/renderer/FakePlayerRenderState.java`
- Direct owners currently include `GatherLogsGoal`, `GatherStoneGoal`, `BuildHouseGoal`, and `ExploreAroundGoal`.

## Ownership Rule

Player NPC has no global `EscapeWaterCurrentGoal`. `PlayerNpcFloatGoal` remains priority zero and supplies
buoyancy without exclusive MOVE/JUMP flags, allowing active work and melee goals to retain their destinations. It suppresses jumping during an intentional dive, and resumes for air recovery, bubbles and lava. A global
water goal must not choose an unrelated nearby bank because it does not know whether the NPC was
crossing a river toward a log, farm supply, build position, or exploration target.

Movement goals that need stronger water recovery use a goal-owned `WaterEscapeAi` directly or the
instance inside their `PathNavigationAi`. Goals with a repath interval must tick water travel every
goal tick, not only when the next path is created, and must stop the helper from their own `stop()`.
Log gathering, stone gathering, and `ExploreAroundGoal` retain vanilla's reduced goal tick cadence;
the capped water impulse persists between updates and does not justify promoting their full state
machines with `requiresUpdateEveryTick()`. The entity-owned `WaterTravelAi` ticks only cheap motion and local safety each server tick; float buoyancy no longer preempts JUMP-owning melee pursuit.

## Destination-Preserving Behavior

When a preferred destination exists, `WaterEscapeAi` swims toward that same destination and uses
cooperative buoyancy and `WaterTravelAi` for randomized dives. It normally selects a dry exit only when the exit reduces horizontal
distance to the owning destination; an exit on the bank the NPC just left is rejected. A destination
directly above or below the current water column is the exception: horizontal swimming cannot reach
it, so the best dry side exit may briefly increase distance and let the owner repath from land.
Destination swimming and no-progress episodes are bounded so an unreachable occupied target cannot
consume AI time forever. The absolute episode start, swim count, best distance, and failure history
survive transitions through dry-exit and footing fallback; resuming swimming must not reset them.
The absolute episode budget is selected once from the initial horizontal distance (200 plus 20 ticks per block, clamped to 20–120 seconds). This allows longer crossings without disabling the existing eight-second no-movement failure, three stall attempts, or failed-destination quarantine.
Because destination swimming stops land navigation, `MoveControl.setWantedPosition(...)` alone is
not horizontal propulsion for this NPC. Water travel also applies a small capped X/Z acceleration
toward the owner destination (and destination-side dry exit); omitting it leaves the NPC bobbing in
place until the stall timeout despite a correct retained target.
The acceleration, look update, fluid-state check, and progress arithmetic are cheap enough for each
owning goal tick. Dry-exit discovery and shallow/wall footing discovery are not: all destination recovery
world scans share one absolute `tickCount` gate and run at most once per 20 server ticks, independent
of how often an owning goal invokes the helper. Those scans also acquire `PlayerNpcAiWorkBudget`;
denial schedules a short jittered retry while cheap steering continues and does not count as a failed
stall episode. Once a GatherLogs destination episode fails, abandon that exact log route before
admitted reselection instead of feeding the same target back into a permanently failed helper.

Do not slow the whole `GoalSelector` to 20 ticks to protect these scans. That phase-locks all NPC
activation, target, farm, tree, fishing-water, and path work into a once-per-second server-thread
burst. Keep vanilla selector cadence and use each expensive goal's randomized `CanUseThrottle` plus
the helper's absolute 20-tick recovery gate.

Water steering can expose an existing land-recovery cost when it successfully delivers several
NPCs to their targets at once. In particular, log pillaring must not rescan a 21x5x21 dirt volume
and path every possible stand on each goal tick. Missing-dirt and alternate-base recovery are
staggered by at least 20 ticks, tree work is local, and both outer targets and inner stand-path
checks have hard budgets.

Footing placement is recovery, not the first action after entering water. It is considered only
after destination swimming stalls, and only when the current underfoot water cell is one block deep
over a solid floor or directly adjacent to a validated dry stand. The selected placement is that
exact original water cell at the NPC's feet or one block below a surface-bobbing body; it is retained
while the NPC jumps clear. Side cells and cells above the feet are never footing candidates. A
successful footing is remembered as an owned temporary support. Ordinary river crossings do not
place blocks or switch banks.

Farm-area and log exploration detect both fluid-aware wet bodies and a dry body stranded on a tiny
support with water on at least two sides and no connected dry step. Before cycling ordinary random
distance bands, one admitted pass samples at most 192 loaded columns within radius 18 and accepts
only sky-visible terrain stands with at least three connected terrain neighbors. The selected mainland
stand becomes the water destination; an NPC perched on a crafting table/support first jumps and
steers off it, then the normal destination swim owns the crossing. No scan reads an unloaded column
or creates a land path across the water.

Land pillar requests are invalid while the NPC is fluid-aware wet (entity water flag, feet fluid,
head fluid, or water directly below an airborne surface-bobbing body). `PlayerNpcEntity` clears stale upward requests centrally, and
`EscapeHoleWithBlockGoal` uses the same wet predicate for activation and continuation. This prevents
an old exploration climb from preempting the active water crossing at a fluid tick boundary.

`ReturnHomeGoal.needsHomeSurfaceRecovery(...)` must also reject the same fluid-aware wet state.
Water bobbing can floor `blockPosition().getY()` one block below a saved home origin while X/Z is
still inside the build footprint. Treating that as an underground-home failure lets priority-four
ReturnHome cancel a priority-six gather route, issue a land upward request that water safety clears,
and leave only FloatGoal; the gatherer then rescans the same tree after its cooldown. Re-evaluate
home surface recovery after the NPC is dry instead.

A started log/stone supply episode or log/stone ExploreAround route owns its destination during clear daytime, including after it crosses
the saved home work-area boundary. Ready build/utility work must not let priority-four ReturnHome
preempt that priority-six supply owner; night/thunder and explicit exploration/home-surface recovery
remain allowed to interrupt it. Without this ownership latch the NPC advances a few water blocks,
stops, rescans the same tree/path after cooldown, then starts an unrelated ExploreAround route.

## Dive and Surface Motion

`WaterEscapeAi` renews a six-tick destination intent while crossing; `CombatMovementAi` supplies the current target while wet and outside attack range. Ordinary live navigation also participates, steering along its next path node rather than cutting directly across its route. The helper never searches for a new destination, places blocks or changes goals. Stopping the owner releases its intent; a missing renewal expires it. Moving combat targets retain their randomized swim phase.

After 10-25 ticks of travel, a sufficiently deep open-water route may select a 1.4-2 block dive lasting 100-140 ticks (5-7 seconds), with 20-40 tick retry/surface intervals. Decisions still run at most once per 20 ticks, so actual starts can be later. A new dive requires reaching the surface first; air recovery may extend the surface interval. The deeper minimum avoids selecting a submerged standing box whose head is still above water. The decision checks only two short loaded columns and local collision boxes, requires a clear air opening overhead, and avoids bubbles, shallow floors and obstructed water. Local collision and overhead checks keep movement out of banks, ice, overhangs and unloaded edges. Within three horizontal blocks of the destination, or after a collision, dive timeout or low air, the NPC surfaces. Air below 140 triggers recovery until nearly full; using items pauses deliberate travel dives. Local survival-only escape remains buoyant.

Surface horizontal propulsion now uses acceleration 0.018 and a 0.10 blocks/tick velocity cap (previous destination steering used 0.035 and 0.16). Underwater propulsion uses 0.025 and 0.16. Owner speed scales both. WaterEscapeAi shares these values; WaterTravelAi also caps final motion for live navigation and owner-propelled intents so propel=false cannot bypass surface pacing. These are motion parameters, not measured player-equivalent speeds. Vanilla FloatGoal's vertical buoyancy and emergency air recovery remain intact.

The server synchronizes swimming and `Pose.SWIMMING`, with a 0.6-block swim body and 0.4-block eye height. The renderer uses player-style body rotation and swimming animation. Standing resumes only when the full standing body fits; dive planning conservatively checks standing-size clearance. Better Combat holding poses yield to swimming through their existing suppression guard. Epic Fight's renderer remains owned by that mod.

On 26.1.2, `HumanoidMobRenderer` extracts the swim animation state but does not rotate the whole body like vanilla `AvatarRenderer`. `FakePlayerRenderer.setupRotations` must explicitly apply the blended swim rotation and swimming translation using the render-state API; otherwise the limbs swim while the torso stays upright. During wet Player NPC swim blending, the separate `swimXRot` render-state field derives pitch from synchronized velocity (`-atan2(vertical, horizontal)`), with neutral pitch when stationary. Descending motion therefore points the body down and ascending motion points it up, independently of a goal looking toward a bank above the water. This is a visual transform only; AI look targets, propulsion and server pose ownership remain unchanged. Fall flying, auto-spin, death and sleeping bypass this swim transform. The 1.21.1 branch has not been validated by this correction.

Validation requires in-game crossings for log/stone work, navigation destinations, moving combat targets, shallow shores, deep water, low air, ice and interrupted goals. Check horizontal, descending and ascending body orientation with both skin widths, equipped armor and held items, plus the return to standing on shore. Compilation alone does not establish the visual result or route success in those scenes.
