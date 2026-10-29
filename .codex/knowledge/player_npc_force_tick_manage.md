# Player NPC Force Tick Manage

Natural-spawn population cap:
- `maxNaturalPlayerNpcs` defaults to `-1`, which now selects the conservative hardware/MSPT automatic policy documented in `.codex/knowledge/player_npc_natural_spawn_cap.md`. `0` disables natural spawning and a positive value is a fixed cap.
- `PlayerNpcNaturalSpawnCap` and its persisted UUID registry own population admission. The spawn predicate no longer reads or initializes `MANAGED_NPCS`, and the cap remains active with `forceTickManage=false` without scanning every level for each spawn attempt.
- Force management still affects runtime cost: enabled NPCs remain loaded/ticking, while disabled remote NPCs may unload. The automatic formula accounts for that through measured total/non-NPC MSPT, but force-ticket enablement does not change the cap's configured semantics.
- Runtime evidence from the 2026-08-27 CurseForge report: Forge corrected a missing key to the regressed default `maxNaturalPlayerNpcs=-1` at 23:38:46, and the performance warning reported `totalPlayerNpcs=41` at 23:42:29. Twelve NPC finalizations ran on `Worker-Main` threads during 1-99% spawn-area preparation before the player joined; another 32 worker-thread finalizations followed while the fresh world loaded a 16-chunk view/12-chunk simulation area. This distinguishes fresh-chunk generation from the already-generated dev-world observation. With `forceTickManage=true`, counting worked for registered entities; the unlimited value and asynchronous chunk-generation path prevented a useful bound.

Feature flag:
- `SmartNpcConfig.forceTickManage` is a ranged integer: `-1` automatic (default), `0` disabled, and `1` fully enabled.
- Automatic mode always prioritizes current `PlayerNpcAiWorkBudget` worker holders. Its internal spare-ticket target starts at one and, while both trimmed baseline and rolling MSPT are at or below 40, adds at most one slot after two five-second evaluations. Growth stops above 40 and confirmed overload removes non-worker spares. The absolute guard is 64, further bounded by the known NPC count and the current routine-worker limit plus one prefetch slot. Active workers remain an unconditional ticket floor even above that internal target.
- Shortly before the scheduler's time-1000 daily roster rotation, automatic mode force-ticks a spare candidate. The old roster and prefetched replacement overlap briefly; new worker tickets are installed before old non-worker tickets are released.
- When disabled, Player NPC force-tick tickets are released, NPC tab-list rows are removed, `/smart_npc tp` is hidden, and inspectator left/right falls back to the old nearby-client scan.
- `forceTickManage` does not control `PlayerNpcAiWorkBudget`. The scheduler is called by `StartupWorkGatedGoal` from each entity's ordinary goal-selector tick, so every normally loaded/ticking Player NPC still requests and releases routine worker turns while force-ticket management is disabled. A distant NPC whose chunk unloads no longer ticks, consumes no AI CPU, and therefore owns no scheduler resource until it is loaded again.
- Scheduler and performance diagnostics remain valid for loaded entities when force-ticket management is disabled. Trace-all explicitly falls back to enumerating loaded Player NPCs per level, the inspector resource view reads scheduler holders directly, and performance warnings enumerate loaded entities. What is lost is remote coverage: unloaded NPCs, the managed cross-dimension registry, tab rows, teleport lookup, and server-driven remote inspectator cycling are unavailable.

Core owner:
- `src/main/java/com/pla/smart_npc/util/PlayerNpcForceTickManager.java`
- It is a NeoForge event subscriber and owns the tracked loaded Player NPC registry, force-tick chunk tickets, tab-list fake players, command lookup, and tracked inspectator cycling.

Chunk tickets:
- Each selected alive `PlayerNpcEntity` has one moving distance-2 region-ticket anchor at its current chunk. Mode `1` selects all known NPCs; automatic mode selects the adaptive worker-first subset. The ticket's normal distance propagation supplies a loaded fringe around the center; do not add a 3x3 grid of independent force-tick anchors. Nine anchors per NPC multiply entity-ticking chunks and scale poorly when many NPCs are spread across the world.
- Tickets use `ServerChunkCache.addRegionTicket(..., forceTicks=false)` and are removed with the matching `removeRegionTicket(..., forceTicks=false)`. The boolean is part of NeoForge ticket identity, so add/remove values must always match.
- Distance 2 creates ticket level 31 (`ENTITY_TICKING`) at the NPC center. This keeps the Player NPC entity AI alive; block entities and scheduled block/fluid ticks follow the ordinary block-ticking status. NeoForge's separate `forceTicks=true` registry bypasses the real-player proximity gate in `ServerChunkCache.tickChunks`, adding natural spawning and `tickChunk` random ticks at every remote center. Do not enable it for Player NPC anchors. Consequently, crops and other random-tick blocks do not advance solely from an NPC ticket, matching vanilla `/forceload`; they still advance when a real player's simulation range covers the chunk.
- The ticket type is `smart_npc:player_npc_force_tick`; ticket values include the NPC UUID and chunk long so overlapping NPCs release independently.
- Old chunks are released whenever a selected NPC changes chunk/dimension, is deselected, dies, the server stops, or the config is turned off. An ordinary unload keeps the NPC's persisted identity and last center available for later automatic selection.

Restart persistence:
- Runtime chunk tickets do not survive a server stop. `PlayerNpcForceTickData` persists each tracked NPC UUID, dimension, and last center chunk in overworld `SavedData`.
- On server start or feature enable, `PlayerNpcForceTickManager` restores known identities and saved centers without immediately ticketing every entry. The allocator selects a bounded subset, preferring active workers and already-loaded candidates; an unresolved saved entry is only a bootstrap fallback. Player login uses that idempotent initialization state and does not repeat a level-wide reconciliation.
- `EntityLeaveLevelEvent` removes the unloaded tab row but does not delete the saved registry entry because chunk unloads and server stop also fire leave events.
- `LivingDeathEvent` removes both runtime tracking and the saved registry entry. If a restored entry cannot resolve an entity after the center chunk has loaded and the grace window expires, it is treated as stale and pruned.
- Tracking is immediate on entity join. Ongoing movement refresh runs once per 20 server ticks per NPC, with a deterministic UUID phase so distant NPCs do not create one synchronized housekeeping burst. Unchanged centers and saved registry values do no ticket-set or dirty-data work; display/profile metadata uses a slower stagger and unchanged metadata does not recopy the fake profile. A moving NPC's center ticket can therefore follow a chunk crossing by up to one second; the distance-2 center ticket keeps the adjacent transition fringe loaded during ordinary movement.
- Restored unresolved entries count that refresh interval toward the 30-second entity-load grace, so throttling housekeeping does not accidentally extend stale tickets to ten minutes.

Tab list:
- Tracked NPCs are added to the multiplayer tab list only while `forceTickManage` is enabled.
- The manager sends 26.1.2 `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(...)` for a NeoForge fake player whose UUID matches the NPC entity UUID.
- A joining viewer receives all currently resolved managed NPC rows in one initializing packet rather than one packet and one reconciliation pass per NPC.
- `PlayerEvent.TabListNameFormat` gives managed fake players a `[NPC] ` prefix before the NPC display name.
- Fake tab players are put in spectator mode and use a late-sorting synthetic profile name so vanilla tab sorting keeps normal real players above NPC rows.
- Rows are removed with `ClientboundPlayerInfoRemovePacket` when the NPC is no longer tracked.

Teleport command:
- `/smart_npc tp <name>` is registered under the existing command event and is gated by `forceTickManage`.
- Suggestions come from currently tracked alive NPC display names.
- Name matching strips an optional `[NPC] ` prefix and compares case-insensitively.
- If multiple tracked NPCs share the same name, the command chooses a random match.
- `/smart_npc tp <name> <destination>` moves that named NPC to one selected entity, including across dimensions. Examples: `/smart_npc tp MrBeast @s`, `/smart_npc tp MrBeast @p`, `/smart_npc tp MrBeast @e[type=minecraft:armor_stand,limit=1,sort=nearest]`. A destination selector must resolve to exactly one entity, as in vanilla teleport.
- Both forms require operator permission level 2 and enabled force-tick management. The one-argument form still moves the executing player to the NPC. Names containing spaces must now be quoted, e.g. `/smart_npc tp "Example NPC" @s`; teleport suggestions quote names as needed while resource-command suggestions retain their greedy-name syntax.
- NPC teleport honors NeoForge's teleport-command event, stops navigation, clears momentum/fall distance, and refreshes force tracking on the destination instance after a dimension transfer. It does not change the NPC's job/home/team.

Inspectator cycling:
- Client left/right in inspectator mode sends `PlayerNpcInspectatorCyclePacket`.
- If the server feature is enabled, the server picks the next tracked alive NPC, teleports the viewer to that NPC if needed, starts riding it in spectator mode, and sends both `PlayerNpcInspectatorCycleResultPacket` and a fresh `PlayerNpcInspectorPacket`.
- If the server feature is disabled, the result is marked unhandled and the client runs the previous 64-block nearby cycle scan.
- The client overlay keeps a short pending-target window so a remote target does not immediately clear while chunks/entities are arriving.

Verification:
- For the adaptive tri-state change, static reference/brace checks and `git diff --check` were run. Compilation was intentionally not run at the user's request.

Force-manager attribution must be read alongside loaded-NPC and entity-tick timing. If every known NPC remains in `totalPlayerNpcs`/the loaded trace and `forceManagerMs` is near zero, a remote idle slowdown is not evidence that its center ticket failed. Teleporting can warm client/server terrain and navigation caches without being what made the NPC tick. A real restoration failure instead presents as a persisted NPC that is absent from loaded timing/trace until its chunk is loaded by another source.
