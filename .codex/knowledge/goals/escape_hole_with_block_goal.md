# EscapeHoleWithBlockGoal

Generic trap discovery yields to actual running `GatheringGoal` delegates, including DigDown
behind the startup and interest wrappers. DigDown's successful stone/ore/cave handoff now also
reserves the local excavation for up to 60 server ticks after it stops, preventing priority-one
escape from filling the new step before the next mining goal acquires its work slice. This
nonpersistent reservation is owned by the DigDown instance shared at goal registration; it
expires, ends after movement beyond three blocks, and yields immediately to combat/healing or
explicit upward recovery. Failure/abort stops do not reserve a site.

Terraform support-fill requests use an exact requested feet height throughout forced-route
admission, pillar planning, and completion. Ordinary forced-route one-block arrival tolerance is
not valid for this handoff: it can leave the builder inside the support work level and repeatedly
request the same climb. Open sky must not shorten Terraform's planned ascent below that height.
DescendHighColumn protects the authoritative build footprint below the home origin as well as
the house volume; it rechecks an active Terraform handoff before continuation and breaking so
generic descent cannot remove the foundation/recovery column that Terraform just needed.

The 2026-09-06 regression run passed all ten GameTests, including exact Terraform climb height,
foundation protection, distant support approach, pending support retention, and the previous worker
regressions. The isolated 1,024-entry empty-site scan completed in 18 bounded slices (7.63 ms total);
this validates cursor throughput in the fixture, not timing or full navigation in the user's world.

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/EscapeHoleWithBlockGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs escape simple holes by jumping and placing a block at their feet.

## Behavior

The 21:38 Skeppy trace never started escape while carrying 17 sand: the former dirt/plank/stone
whitelist counted zero material even during the recorded live-target +3-height episodes. Sand,
red sand and gravel now share one explicit carried-material predicate for counting and equipping.
They follow the existing physical jump, collision-safe placement and guarded hand restoration;
immediately before committing, gravity blocks require a loaded, dry full-collision support below.
Air, fluid and partial supports cannot receive them. Existing temporary-column validation remains.

Without a combat target or routine worker slot, an ordinary NPC with carried material may now
borrow emergency execution only after the bounded open-shaft proof succeeds outside its owned
build footprint/farm. This runs at the existing >=20-tick stagger with shared admission, carries
the exception through placement/settlement, and never gathers materials. Cautious NPCs retain
their threat requirement. Routine worker and team handoffs remain available independently.
Existing no-worker farm-gate egress is checked first and retains its block-free admission;
the passive pillar material/protection guards must not suppress that safety handoff.

The middle side cell of a 2x3 shaft touches only one wall. All shaft probes can now pass that
cheap wall gate through the bounded four-direction nearby-wall check, then must still complete
the loaded component proof with no walking exit, no open drop and elevated safe rims on all sides.
The ordinary six-stand cap already fits 2x3; the emergency cap remains 24 and radius remains three.
Generic nonforced upward hints also receive a staggered shaft probe, so a stale chase hint does
not hide this geometric evidence once the target disappears. Terraform/forced handoffs retain
their existing admission and protection rules.

Live combat admission is height-directed: only a living target more than two blocks above
the NPC can start an upward episode. Same/lower targets belong to combat obstruction clearing;
the intermediate zero-to-two-block rise stays with ordinary navigation/obstruction handling.
At a randomized >=20-tick cadence, a grounded NPC with carried pillar material and a loaded
safe current stand requests one shared-admitted 0.01 bounded path to the elevated target.
A reachable route keeps normal pursuit. The complete local chunk corridor is preflighted
before the admitted path probe, so a null/incomplete result may permit a bounded direct-current-
column pillar plan even from a completely confined stand. Unknown/unloaded corridors decline.
Current support, clearable headroom, height limit, placement collision and protection still apply.
Live combat no longer depends on any fixed shaft footprint or number of floor cells.

The initial target height is retained through the ascent, so gaining one block and reducing the
height difference below two does not restart the goal. Completion uses safe grounded footing at
that cached height minus the ordinary one-block route tolerance. Visible sky cannot stop it early,
and a roof or the enemy's occupied feet do not require extra ascent/horizontal arrival to finish.
The direct plan uses that same latched height; combat disables the generic two-block open-sky
planning shortcut before scanning the column, so the planned budget matches completion.
If the enemy moves down to or below the
NPC, recovery yields only on grounded footing outside a pending placement/settlement/clear.
Death/loss of the enemy does not cancel an airborne placement. Cautious/no-target and team
recovery retain the separate confined-shaft proofs below.

The 21:20 Sapnap trace already reached `pillaring 0/2`, then lost ownership to priority-zero
weapon recovery while holding its temporary block. This is execution/hand ownership evidence,
not another shaft-size detection failure. The entity's short jump must authorize this running
emergency episode without requiring a routine worker, and weapon recovery must respect its
temporary pillar hand. The goal continues to request the ordinary physical short pillar jump.

The 2026-09-09 Sapnap trace remained in priority-6 vanilla melee at floor Y99 under a Y102
rim, despite 19 carried planks. Emergency material counting formerly applied the routine log
reserve and could report zero usable blocks before any trap probe. Combat/team/cautious
emergency episodes now bypass the wood reserve consistently for counting, equipping, and
placing carried planks; ordinary worker pillar material policy remains unchanged.

The earlier confined-shaft proof can also cover nine floor cells: the ordinary six-stand cap rejected a 3x3
chamber, and its center/edges do not necessarily touch two immediate walls. Cautious recovery
may pass that cheap wall gate only after finding a loaded body-height wall within three cells
in each cardinal direction. It then uses the existing radius-three loaded component proof,
capped at 24 stands and 24 boundary columns, requiring no walking exit and elevated safe rims
in all four directions. That proof remains for cautious and passive recovery without a live combat
target; live combat now uses the height/path rule above. Ordinary noncombat shaft probes retain
their smaller cap.

The 2026-09-09 combat trace placed mossy cobblestone at (-84,108,17), then recorded the NPC
knocked sideways onto (-83,108,18). The block remained intact; the old 30-tick landing timeout
incorrectly reported missing solid support and abandoned the escape. Settlement now separates
destroyed support from displacement. Once grounded away from the intended support, it may
replan only its current safe feet column under shared admission at >=20-tick cadence, retaining
the escape target and goal controls. At most three successful displacement replans occur in one
activation; each missed landing has a 60-tick limit. A displaced confirmed landing follows the
same recovery. No snap onto the old column, knockback cancellation, or unrelated side clearing
is permitted. Existing support blocks remain remembered/validated; unloaded support is unsafe.

Combat recovery retains the live attack target while the goal owns MOVE, JUMP, and LOOK. A
CAUTIOUS NPC deliberately retains no attack target, but receives the same local emergency
admission when a valid attacker remains within 28 blocks and the last hit was within 200 ticks,
or an admitted radius-14 cautious threat search succeeds. The cached attacker check is O(1);
the fallback search runs only after a proven trap/open shaft, on the existing >=20-tick stagger
and shared optional admission. It uses the same carried-block requirement, direct current-column
planner, and complete-episode execution access without assigning an attack target.
This exemption applies when ordinary worker/team recovery is unavailable; cautious NPCs with
an existing worker lease or team recovery retain their ordinary climb requests even without threats.

A grounded NPC carrying usable pillar blocks may start an emergency escape without a routine
worker lease. Live combat uses the height and failed-route checks above; cautious/passive recovery
with no live target still requires its loaded, bounded trap/open-shaft proof. These use the randomized
>=20-tick cadence, shared admission and bounded path helper also used by the team-follow
exception, and neither starts escape-material gathering. The exception
is retained through the complete escape episode, including the final placement/settlement if the
enemy dies or disappears, then cleared on stop. Normal utility/combat goal arbitration must keep
attack animation and damage stopped throughout this MOVE/LOOK-owning escape.

Combat recovery always plans the pillar at the NPC's current feet column and resets the retained
pillar candidate cursor. It may clear a real obstruction in that vertical column, but collision and
adjacent-wall recovery fallbacks are disabled for this episode: surrounding blocks must not replace
the ready pillar placement. This prevents a boxed combatant from spending
the escape timeout mining dirt or cobblestone walls without a preferred tool.

When the NPC is boxed in by at least three two-block-tall collision barriers, has no adjacent walkable open body space, and has a normal placeable block, it jumps first, waits a few ticks until its body is clear of the target block space, then consumes one block, places it below itself, swings, and returns to idle.

The same goal also handles the cave return-home case where the navigation/home target is above the NPC, the route is blocked by a tall wall, and normal pathing is done or stuck. In that mode it first finds a nearby pillar column with clear body space upward to a step-out or sky-visible surface route. It chooses a random 16-32 block escape budget, gathers nearby stone/cobblestone/deepslate only with a usable pickaxe if it has too few blocks, then moves to that pillar column.

One-block obstacles or flat ground with an open adjacent exit must not trigger this goal because Player NPC can jump those normally. Do not re-add a simple "two-block block ahead" trigger unless it is also tied to reliable stuck/path intent.

The ordinary activation predicate must reject any open horizontal side before running `hasLocalWalkingEscape`. Being genuinely trapped ultimately requires all four lower horizontal sides to have collision; the older equivalent ordering ran the bounded 9x9x4 walking flood first even on open terrain. Since this safety goal is deliberately unwrapped and checked without a routine worker resource, that ordering multiplied collision reads across every idle NPC selector pass. Keep explicit upward/farm escape requests immediate and keep the goal outside routine scheduling; the cheap-side-first ordering changes only evaluation cost, not the trapped result.

The delayed placement prevents the block from being placed inside the NPC's bounding box before the jump lifts it clear.

Utility blocks such as crafting tables, chests, furnaces, beds, and torches are not used as pillar material for this escape.

A bed that physically occupies the active pillar/body column is a narrow clearance exception. The escape goal may break it even when it lies inside the home/build footprint, because otherwise the bed collision is invisible to pillar recovery and the NPC loops while shifting the pillar base. This exception does not extend to any other build block, and owned-farm destruction protection still applies to the target and its matching head/foot half. Occupied beds are not cleared. Bed breaking allows the bed block entity only for this validated target, uses normal `destroyBlock` neighbor updates so the paired half is removed, and emits loot from the struck half once; a no-drop cleanup removes a matching half only if it survives that vanilla update.

Emergency escape material mining re-equips a pickaxe before every mining pass, adds cobblestone or cobbled deepslate directly to the NPC inventory after breaking the source block, damages the pickaxe, uses the same crack overlay helper as other gradual mining goals, and plays the source block's hit sound during mining. The final break sound/effect comes from `ServerLevel.destroyBlock`, so it only plays after the source block is actually broken.

Escape-material stand selection must use the same physical reach geometry as runtime breaking: entity feet at the centre of the stand block to the target block centre must be within the squared break distance. Do not use integer `BlockPos` distance with extra slack; that can accept the NPC's current block as a completed path even though the target remains outside breaking reach.

If navigation still cannot enter reach, `PathStuckFallbackAi` watches the unchanged stand for five seconds and performs at most three physical step-off attempts. Each started recovery stops block breaking, restores the work hand, invalidates the stale material target/stand, and reacquires from the new feet position; it does not teleport or remove remembered temporary pillar supports. Its bounded landing search only reads already-loaded chunks. A 30-second total episode deadline also covers the case where no safe step-off exists. Exhaustion releases the upward request (or uses the exploration failed-climb handoff), applies a 30-second hole cooldown, and returns ownership to the interrupted mining/exploration goal. Per-tick `canContinueToUse()` must not rescan the escape-material volume; target acquisition stays in the running tick after the stale target is cleared.

A forced home-surface pillar has a separate one-second watcher while `placePos` is still null and navigation has completed one cell short of `pillarBasePos`. The normal placed-pillar stuck watcher cannot observe this phase. Recovery first replans the current feet column, then another adjacent base, and keeps explicit trace detail. When a forced current-column obstruction belongs to the build footprint, an open alternate pillar plan is preferred before the narrowly scoped house-block clearance fallback.

While pillaring, the NPC temporarily equips a placeable block in the main hand, looks down at the placement position, uses a player-like pre-placement delay, places the block underneath after the jump clears the old feet space, and restores the previous hand item when the goal stops. The held block is counted as available escape material, and placement can use a fallback near the top of the jump so the NPC does not repeatedly jump without placing.

Pillar ascent must remain physical and visibly paced. Placement is allowed only when the live NPC bounding box is clear; pillar code must not approve a hypothetical collision-free position and then snap/teleport the NPC onto it. After placement, the NPC must fall onto that exact solid support. The dedicated emergency escape goal remains grounded there for 12 ticks before starting the next jump, while shared `PillarUpAi` uses two consecutive grounded ticks so routine resource, pickup, and return pillars do not pause excessively after every block. The active support column and the remembered temporary-support link down to natural ground are revalidated throughout either settlement window. If any support becomes replaceable, loses collision, or leaves a gap, ascent stops and retries through the bounded escape cooldown instead of continuing on a floating column.

Airborne placement readiness must be derived from the proposed block's real collision boxes against the live NPC bounding box. Do not use an early height threshold such as 0.65 or 0.95 blocks and then treat the expected body overlap as a terminal clipping failure: a normal 0.42 jump is still inside a full support cube at those heights. `EscapeHoleWithBlockGoal` waits only two post-jump ticks before checking the live clearance each tick; shared `PillarUpAi` performs its visible action delay before jumping, then checks clearance on every airborne tick. Expected overlap waits for the apex window, while actual ceiling obstruction still follows bounded blocker recovery.

Exploration may request this pillar mode only toward a genuine local surface stand. `ExploreAroundGoal` rejects stands supported by logs/leaves and rejects relaxed elevated candidates without at least one terrain-supported walk-off, preventing a tree trunk or isolated canopy column from being treated as a surface route. Missing-string fishing strolls never request this mode; a confirmed leaf collision in their navigation corridor belongs to shared foliage clearing instead.

Completed exploration supports remain marked temporarily for placement safety, but that memory must not delay `DescendHighColumnGoal`. Once the upward request has ended, the hole cooldown has elapsed, and the existing narrow-column, lower-terrain, home, farm, fluid, block-entity, and breakability checks pass, descent may remove the remembered support immediately instead of waiting for the 45-second support-memory expiry.

Cooldown uses `PlayerNpcEntity.holeEscapeCooldown`, decremented from the entity tick.

The 2026-09-06 builder mine exit at (-86,96,135) exposed a wider shallow pocket: the immediate body neighbors were air, but its connected walking stands ended at two-block walls. When standing on an owned temporary support, the loaded radius-three open-shaft probe admits up to 24 connected stands and a shallower elevated rim; it still requires closed boundaries in all four directions and a terrain-supported walk-off. Open ground must reject this recovery even when its floor is remembered as a pillar support. Retain the proven rim through an exploration-style upward request, climb to its actual surface height, and physically jump/step across the checked body corridor before considering escape complete. Do not finish merely because the NPC can see sky or landed in another low pocket cell. Descent must validate its prospective lower body position for every support, including stacked owned supports, so it cannot remove the escape floor and immediately trigger its replacement.

Route discovery and reachability checks are speculative safety diagnostics, not normal movement paths. They use a scoped 0.01 visited-node multiplier, test at most two route candidates per pass, and test at most two monotonic farm-egress intermediates. A live 209.5 ms NPC tick while this unwrapped goal was actively mining/pillaring showed that its former batches of raw `createPath` calls could bypass routine-worker bounds. Keep emergency escape available without a routine resource; bound its atomic path work instead.

An explicit upward request's `maxPillarBlocks` is also a planning bound, not merely a result filter. Candidate columns must stop scanning at that height because a taller plan would be rejected anyway; scanning the full 96-block emergency ceiling first caused 194-210 ms `super.tick()` spikes in fresh terrain for a request capped at 10 blocks. If planning succeeds but the NPC has no usable pillar material and no gather target can start, retain the request and apply the normal short plan retry deadline instead of rebuilding it on the next selector pass. `EscapeHoleWithBlockGoal.canUse` records its own performance timing because this safety goal intentionally remains outside the routine-work wrapper.

Pillar-plan discovery must inspect at most two columns in one `canUse()` pass. The current column and nearby alternatives belong to one distance-sorted, cursor-backed candidate sequence; do not add a separate direct-column batch before it. Failed passes resume from the cursor after the normal retry cooldown, preserving eventual recovery without repeating several tall vertical corridor scans in one server tick.
