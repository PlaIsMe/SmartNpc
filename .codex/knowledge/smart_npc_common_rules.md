# Smart NPC Common Modding Rules

## User Verification Preference (2026-09-06)

The user requested removal of `src/test` and the GameTest Gradle run configuration, and does not want automated tests run or recreated for routine fixes. Validate future changes by focused code review and Java compilation/packaging (`gradlew.bat assemble`, which does not run tests) unless the user explicitly changes this preference. Earlier notes mentioning passing GameTests describe historical verification, not an active test package or a requirement to restore it.

## Project Baseline

- Smart NPC targets Minecraft Forge `1.20.1` with Forge `47.4.4`, Parchment `2023.06.26-1.20.1`, and Java `17`.
- Production Java packages use `com.pla.smart_npc`. Some older knowledge notes still mention the pre-rename `annoyingvillagers` package; verify paths against the current source tree before editing.
- Epic Fight is an optional compatibility boundary. Generic Smart NPC code may call the small
  static API in `compat.epicfight.EpicFight` directly, but every such call and all Epic Fight event
  registration must first pass `ModList.get().isLoaded("epicfight")`. Do not restore reflection,
  cached `Method` fields, or an `EpicFightCompat` invocation layer.

## AI Design Pattern

- Keep goal selection, lifecycle, and goal-local plans in focused classes under `entity.goal`.
- Put reusable mechanics under the flat `entity.ai` package and suffix helper class names with `Ai`.
- Keep `PlayerNpcEntity` focused on entity-owned state, synchronized animation state, generic hand/inventory operations, persistence, cooldown ticking, and goal registration. Do not add large job state machines there.
- A goal must revalidate destructive work at execution time, not only in `canUse()`. Combat, healing, death, no-AI, passenger state, changed blocks, changed ownership, and invalid targets can occur after planning.
- Use goal flags deliberately. Work that navigates or turns the head normally owns `MOVE` and `LOOK`; priority alone does not prevent incompatible goals from running together.
- Work navigation speed must not exceed `1.0D`. Prefer reachable stand positions and bounded path checks; never use an unbounded random walk or destructive stuck fallback.
- Validate a work stand with the same physical entity-to-target reach geometry used by the action itself. Integer `BlockPos` distance plus slack can let navigation finish on a stand from which mining or interaction can never begin, creating a successful-path retry loop.
- Do not use a global MOVE-owning water escape for destination-based work. Keep `FloatGoal` as buoyancy, and let the active movement goal tick destination-aware `WaterEscapeAi` every goal tick while wet. When exploration has no real land path, it must run local survival-first escape without inventing a far work destination; failed local escape gets a randomized negative backoff. A dry exit is valid only when it advances toward a real owning destination; shallow/wall footing placement is a bounded stalled-route recovery.
- Treat 20 server ticks as the minimum cadence for expensive AI decisions. Entity/block candidate searches, owned-plan eligibility, target selection, and path creation must not run every tick. Keep the vanilla selector cadence and put a randomized >=20-tick throttle around each expensive goal's `canUse()` work; a shared `GoalSelector.setNewGoalRate(20)` synchronizes all NPC scans into a once-per-second server-thread burst. `canContinueToUse()` may be called every server tick: only death/combat/current-target and similarly O(1) guards belong on that immediate path; cache world/inventory/plan eligibility for at least 20 ticks.
- `requiresUpdateEveryTick()` is allowed only for a demonstrably cheap motion/animation/safety fast path. The owning `tick()` must return from that fast path while active and explicitly keep land work, scans, blocker discovery, target changes, and pathfinding on reduced or >=20-tick cadence. Never use full-rate goal ticking merely to make one helper smoother.
- Apply the same cadence boundary to server-level NPC housekeeping. Force-ticket reconciliation, saved-data comparison, skin/profile signatures, and tab-list synchronization must be change-driven or run on a deterministic per-NPC stagger of at least 20 ticks; do not reprocess every tracked NPC on every server tick.
- AI searches are observers of the currently loaded world, not chunk loaders. Before any candidate `getBlockState`, `getFluidState`, height-derived follow-up read, or owned-position validation that may cross a chunk boundary, require `hasChunkAt`/`hasChunk`; skip unloaded candidates. Force-ticket movement uses one distance-2 anchor at the NPC's loaded center; never add a grid of neighbor anchors or forward tickets that make an NPC generate or independently tick more terrain.
- Cadence does not make an unbounded batch safe. A running recovery such as missing pillar material must wait at least 20 ticks after a failed scan, and every nested candidate search must cap path creation explicitly. Filter and sort with block/safety checks first, then path only the small nearest subset; never place `createPath(...)` in a stream feeding `min(...)`, because it evaluates a path for every surviving candidate.
- Shared supply scans need both cadence and early exit. `GatherLogsGoal` may rebuild its tree queue at most once per 20 server ticks, and `TreeAi.findNearest(...)` searches cached horizontal columns nearest-first; after finding a real tree it stops when the next column's horizontal lower bound cannot beat the chosen stump. Do not restore the unconditional full 65x25x65 cuboid read for a radius-32 lookup.
- Defer routine Player NPC work during server restoration through the central `StartupWorkGatedGoal` registration wrapper. Work is locked for at least the first 60 server ticks and each NPC receives a stable UUID-derived 0-60 tick release offset, so restored workers do not all run their first eligibility/path scans together. The wrapper checks the gate before calling the delegate and also rejects continuation during the grace period. Target selectors, attack/defense, fleeing, healing, floating, cautious avoidance/hiding, help calls, hole/high-column escape, combat weapon recovery, and door traversal remain available immediately. Do not replace this with a persisted per-NPC cooldown or a synchronized flat release tick.
- `AvoidEntityGoal`-style safety may keep only cheap look/speed updates on its active tick. Threat scans, away-position selection, and path creation belong to randomized >=20-tick activation checks, with a hard path-attempt cap; navigation completion is an O(1) continuation stop, never a signal to rescan and restart a path every tick.

## World Ownership And Destructive Work

- Persist owned temporary world objects with enough identity to recover safely: position, dimension, and an owner marker where the block entity supports it.
- Never destroy a block merely because it is near an NPC. Validate the exact owned plan/reference immediately before breaking or replacing it.
- Treat homes, build footprints, farm irrigation, farm entrances, crop soil, and other job-owned infrastructure as protected volumes. Shared generic goals such as bucket filling and obstruction clearing must query the owning job utility rather than duplicating geometry.
- Never destroy an immature crop. Harvest logic must check the current block is an accepted crop and is at maximum age again on the action tick.

## Inventory, Tools, And Durability

- Use `ToolAi` for temporary work-tool swaps and restoration. Use guarded Player NPC hand setters so a work tool does not overwrite cached combat gear.
- Use `BreakingBlockAi` for ordinary timed breaking when its semantics fit; it owns tool selection, hardness timing, crack progress, sounds, vanilla swing, Epic Fight digging, durability, and optional work sneaking.
- When returning an item to a potentially full inventory, preserve the actual remainder. `InventoryUtils.addItem(...)` returns only success/failure and can partially mutate the inventory; do not drop the original stack after a partial insertion. Use an API that returns the remaining stack or simulate capacity first.
- Apply durability to the item actually used for the work. Restore temporary hands on every normal, failed, or preempted stop path.

## Animation Compatibility

- Route visible work through one renderer-appropriate action signal. Atomic actions must not start both the vanilla swing and a separate Epic Fight animation for the same commit.
- Distinguish timed continuous work from atomic commits. Breaking may keep the repeating Epic Fight digging motion alive across progress ticks and must clear it on abort. Block placement, bucket use, hoe tilling, planting, and bone meal keep their delay animation-free, then emit exactly one normal NPC use swing or one dedicated non-repeating Epic Fight use animation only after the world mutation succeeds.
- Optional compatibility must be a no-op when the external mod is absent and must not introduce external classes into generally loaded code.

## Combat Evolution Execution Eligibility (NeoForge 1.21.1)

- `compat.epicfight.advancedmobpatch.AdvancedMobPatch.canBeExecuted` is called through the optional Combat Evolution `ExecutionHandlerMixin`. It must not return unconditional support: require a live, unfinished `NEUTRALIZE` animation. Idle, ordinary hit stun, knockdown, and completed/interrupted neutralize animations do not qualify.
- Server execution checks also require depleted stamina and `AdvancedStaminaStatus.BREAK`; recovery closes the execution window. The patch's stamina fields are server-only, so client prompts use the synchronized animation rather than reading those unsynchronized fields.
- Keep this check free of Combat Evolution classes so Epic Fight compatibility still loads when Combat Evolution is absent. Mirror behavior changes in the Annoying Villagers Epic Fight addon's advanced patch.

## Persistence And Cooldowns

- Persistent AI cooldowns are integer fields on `PlayerNpcEntity`, saved/loaded with the entity and decremented in the centralized cooldown tick path. Do not add ad hoc persistent-data deadline tags for cooldowns.
- Persistent job plans belong in one shared utility per job. Readers, builders, operational goals, protection predicates, and migration logic must use that API instead of copying NBT keys or geometry.

## Collaboration And Verification

- Preserve unrelated dirty-worktree changes. Inspect the focused diff before and after editing shared files.
- When behavior changes, update its `.codex/knowledge` note. Add a goal note for a new goal.
- Use MineColonies, Structurize, Workers, and decompiled projects as behavior/design references only unless their license and the requested port explicitly permit code reuse.
- Run focused compilation or tests when the user requests verification or the coordinating task explicitly requires it; otherwise follow the active task's verification scope.
