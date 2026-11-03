# Player NPC Inspector Item

The shift-right-click-air overall view includes one concise `Search scope` line sourced from the
server-scoped adaptive policy: current log footprint (`5x5`, `9x9`, or `11x11`), ore radius
(`5`, `10`, or `15`), and building-material radius (`8`, `16`, or `32`). Worker auto status keeps
the effective limit, exploration ceiling, baseline MSPT, and readable reason but omits internal
growth/overload counters. Natural-population auto status likewise omits probe mode/progress while
retaining effective population limits, baseline, reason, advisory/learned/exploration diagnostics.

## Source Files

- `src/main/java/com/pla/smart_npc/item/InventoryViewerItem.java`
- `src/main/java/com/pla/smart_npc/client/gui/SmartNpcInspectorOverlay.java`
- `src/main/java/com/pla/smart_npc/network/PlayerNpcInspectorData.java`
- `src/main/java/com/pla/smart_npc/network/PlayerNpcInspectorPacket.java`
- `src/main/java/com/pla/smart_npc/network/PlayerNpcInspectorRequestPacket.java`
- `src/main/java/com/pla/smart_npc/network/PlayerNpcInspectatorModePacket.java`
- `src/main/java/com/pla/smart_npc/event/PlayerNpcInspectatorEvent.java`
- `src/main/java/com/pla/smart_npc/network/SmartNpcNetwork.java`
- `src/main/resources/assets/smart_npc/lang/en_us.json`

## Item Behavior

The inspector item is implemented by `InventoryViewerItem`. It stacks to one item.

Right-clicking a `PlayerNpcEntity` sends a server-authoritative `PlayerNpcInspectorPacket` to the interacting `ServerPlayer`. The packet contains the target entity id, an inventory snapshot from `PlayerNpcInspectorData.createSnapshot(...)`, and server-generated build status text from `PlayerNpcInspectorData.createBuildStatusText(...)`.

Right-clicking a non-Player NPC shows `message.player_npc.inspector.unsupported`. Right-clicking air with the item sends `PlayerNpcInspectorPacket.clear()`, which closes the overlay and also exits inspectator mode if it is active.

The snapshot order is stable:

- main hand
- offhand
- head
- chest
- legs
- feet
- all 27 custom inventory slots

Do not read the custom inventory directly on the client. The overlay should display the latest snapshot sent by the server.

## AI Inspection Overlay

`PlayerNpcInspectorOverlay` is a HUD overlay, not a normal screen. It renders from `RenderGuiEvent.Post`, so the player can keep normal world context while inspecting the NPC.

The overlay displays:

- inspected NPC name
- health and max health
- synced AI state from `PlayerNpcEntity.getCurrentAiState()`
- interest profile from `PlayerNpcEntity.getInterestsDisplayText()`
- build/home status text from the latest inspector packet
- synced task detail from `PlayerNpcEntity.getCurrentAiDetail()`
- main-hand item
- equipment slots
- custom inventory slots

The interest row is a responsive list, not a single clipped label. The client wraps the complete translated `Interests: ...` text at comma/word boundaries to the panel width. Every following status row, task detail, main-hand line, equipment grid, inventory grid, footer, panel height, and item hover hitbox derives the same extra line height, so a fourth or later interest remains visible without overlapping or clipping the rest of the panel.

If the detail string is empty, the overlay falls back to `ai.player_npc.looking_for_work` while the state is idle, otherwise it displays the translated AI state as the task text.

Inventory snapshots refresh through `PlayerNpcInspectorRequestPacket` every 20 game ticks. The server validates that the entity is still a live `PlayerNpcEntity` and that the player is within 64 blocks before sending a fresh snapshot; otherwise it sends a clear packet.

Formatted display text is cached for 500 ms. Keep this throttling for FPS-sensitive UI, similar to Minecraft's F3 debug screen pattern.

AI states are synchronized as translation keys and rendered through `Component.translatable(...)`. Every literal `ai.player_npc.*` state emitted or recognized by goal/entity code must have an `en_us.json` entry; a missing entry otherwise appears verbatim in both the inspector and scheduler-holder view. Task detail is deliberate server-authored diagnostic text rather than a translation key.

## Standalone Resource And Population Monitor

Shift-right-clicking air with the inspector item opens a standalone overall scheduler monitor. Normal right-clicking air retains its original clear/hide behavior. Shift-right-clicking an NPC retains the same NPC inspection behavior as an ordinary NPC click; it must not enter inspectator or open the overall monitor.

The standalone view uses `PlayerNpcInspectorPacket.OVERALL_ENTITY_ID` (`-2`) and `PlayerNpcInspectorPacket.overall(...)`, so it has no entity target and does not enter inspectator camera/mode. `PlayerNpcInspectorRequestPacket` recognizes that sentinel and refreshes the server-authored monitor every 20 ticks. The payload comes from `PlayerNpcAiWorkBudget.resourceSnapshot(...)`, `PlayerNpcNaturalSpawnCap.snapshot(...)`, and `PlayerNpcPerformanceMonitor`: TPS/MSPT, holder count, active/waiting/effective worker limit, and holder name/state/detail with worker/probe/expensive roles. A separate `NPC population <living> | natural max <effective> (auto/fixed)` line shows loaded and pending counts. Automatic mode adds a feedback line with trimmed baseline MSPT, probe mode/progress, and policy reason, followed by a limits line with explicitly advisory forecast, persisted learned-safe max, and CPU/heap exploration max. The explicit `natural max` is the enforced natural-spawn limit and must not be described as an AI worker limit; the forecast is not enforced. Population includes command/egg NPCs because they consume later natural-spawn capacity even though their own creation bypasses admission.

The payload is capped at eight holders and each name/state/detail field at 96 characters. Probe-only UUID resolution uses direct per-level UUID lookup only within that cap; do not add per-tick entity scans. The overall and single-NPC render branches are mutually exclusive.

The standalone panel must preserve every server-authored diagnostic field. Each logical payload line wraps at word boundaries to the current panel width; continuation rows retain the source indentation and add another two-space indent. The panel grows to the available GUI height. If the wrapped content is taller than the screen, Up/Down scroll through it and the footer shows the visible line range. Do not replace this with per-line ellipsis or a fixed rendered-line cap: population policy, probe progress, and advisory limits are commonly wider than the panel.

Receiving the overall sentinel must stop any active inspectator session, clear trace/requirements UI state, replace the selected NPC panel, and render only the standalone monitor. This makes Shift-right-click air a safe atomic view switch. Receiving the ordinary clear sentinel (`-1`) continues to close both views.

## Inspectator View

While the inspector overlay is open, holding E for the configured short hold threshold toggles inspectator view. The hold only triggers the toggle; the player does not need to keep holding E after mode changes. While the inspector overlay is active and E is being held for inspectator, vanilla inventory opening is canceled/drained so the hold can complete.

The trace toggle uses effective trace state. If `/smart_npc trace all on` is active, a currently inspected scheduler-resource holder renders the trace indicator as on; a waiting/non-holder does not, because trace-all is holder-only. Turning trace off on a holder disables trace-all. Turning trace on for a non-holder enables only that NPC's individual trace without broadening trace-all.

Entering inspectator view does all of these:

- stores the previous client camera type and camera entity
- sends `PlayerNpcInspectatorModePacket(true, entityId)` to the server
- sets the client camera entity to the local riding player
- sets the initial camera type to `CameraType.THIRD_PERSON_FRONT`
- keeps/grabs the mouse so normal camera look rotates the player's head while riding the NPC

While active, the client tick keeps the camera attached to the riding player, which is server-forced to ride the inspected NPC, and suppresses movement/use/attack/drop/pick/jump/inventory inputs. Mouse look is not suppressed in third person, so the player can spin their head/camera. It does not force the camera type after entry; F5 remains free so the player can cycle first person, third-person back, and third-person front while following the NPC. A/D or left/right arrows cycle to another nearby Player NPC within 64 blocks.

The client allows `ChatScreen` to stay open during inspectator mode, so pressing T can open chat without exiting the mode. While chat is open, inspectator hotkeys are ignored so typing does not cycle NPCs or adjust zoom, but the camera remains attached and server-side riding/spectator state stays active.

Third-person front/back camera distance is adjustable with the physical up/down arrow keys while inspectator mode is active. `CameraMixin` patches the vanilla third-person camera setup distance by calling `PlayerNpcInspectorOverlay.getInspectatorCameraDistance(...)`. First-person mode returns vanilla distance and the client tick locks the local riding player's yaw/pitch back to the inspected NPC so first-person does not become a free-spinning orbit camera.

Because the camera entity is the local riding player, vanilla third-person rendering would normally draw the local player's own head/body in front of the inspectator camera. `PlayerRendererMixin` cancels rendering only for `minecraft.player` while inspectator mode is active. This preserves the inspected `PlayerNpcEntity` render and avoids showing the observer's head/body.

Exiting inspectator view restores the previous camera entity and camera type, grabs the mouse again when no screen is open, and sends `PlayerNpcInspectatorModePacket(false, -1)`.

## Server-Side Mode Patch

`PlayerNpcInspectatorModePacket` makes inspectator mode server-authoritative.

When starting, the server validates that the requested entity is a live `PlayerNpcEntity` within 96 blocks. It stores the player's original game mode in persistent data, sets the player to spectator, and forces the player to ride the NPC.

When restoring, it removes the inspectator active/original-game-mode persistent keys before calling `stopRiding()`. This ordering matters: `PlayerNpcInspectatorEvent` cancels normal dismounts while inspectator is active, so the active flag must be cleared before the intentional exit dismount.

`PlayerNpcInspectatorEvent.onEntityMount(...)` cancels dismount attempts when:

- the event is a dismount,
- the mounting entity is a `ServerPlayer`,
- the mounted entity is a `PlayerNpcEntity`,
- `PlayerNpcInspectatorModePacket.isInspectatorActive(serverPlayer)` is true.

This blocks vanilla Shift dismount while inspectator mode is active. `MovementInputUpdateEvent` and `suppressPlayerInput(...)` also clear client-side shift/movement state so the player cannot drive or dismount the NPC locally.

`RenderGuiOverlayEvent.Pre` cancels the vanilla jump bar overlay while inspectator mode is active. `GuiMixin` also cancels the vanilla `mount.onboard` overlay message only while inspectator mode is active, hiding the "Press LSHIFT to dismount" pop-up for this mode.

Logout calls `PlayerNpcInspectatorModePacket.restorePlayer(...)` so a player should not remain stuck in spectator after disconnecting during inspectator mode.

## Maintenance Notes

Keep `PlayerNpcInspectorPacket.clear()` connected to `stopInspectator(...)`; clearing the inspector must also leave inspectator view.

Do not convert inspectator view into a normal GUI screen unless the riding-player camera attachment, grabbed-mouse camera control, chat-screen allowance, F5 camera freedom, third-person zoom, input suppression, and server dismount guard are preserved.

Do not re-add a per-tick `CameraType.THIRD_PERSON_FRONT` force in active mode. The camera may start in third-person front, but F5 must remain user-controlled after activation.

If cycling between NPCs changes, keep the server refresh request and local `inspectedEntityId` update together so the overlay and camera target do not desync.
AI resource holder rows show the remaining NPC-level worker day shift as minutes/seconds. Holders span routine-goal transitions and normally rotate at overworld time 1000 (about 07:00), after the sleep/wake transition; invalid/dead/unloaded NPCs and excess holders after a limit reduction still release immediately.
# Multi-loader transport update

The Minecraft 1.20.1 multi-loader port uses shared packet classes under `common/src`.
Architectury NetworkManager registers explicit clientbound/serverbound channels with
protocol `8` in their IDs. The retained handlers still queue application to the proper
thread and validate senders, entity state and range. Loader event adapters call the
shared inspector lifecycle/input/HUD hooks. See `multiloader_1_20_1.md` for current
paths, runtime dependencies and verification limits.
