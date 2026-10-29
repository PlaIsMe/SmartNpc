# PickupNearbyItemGoal

## Cross-goal interruption memory

`ItemApproachAi` shares a bounded, session-local item-identity route episode with
`BurnNearbyItemGoal`. A first actual start opens a 20-second absolute deadline; interruptions add
a two-second retry delay without resetting it. Failure/exhaustion blocks that item for 60 seconds
in both owners, preventing disposal from bypassing ordinary pickup's failed-item quarantine.
Ordinary worker-slot suspension keeps the existing retained pillar route and is not an interruption.
The shared memory has 24 slots, leaves other items/passive contact pickup available, and is not
persisted. Activation revalidates the memory after predecessor stop, because that stop can quarantine
the exact item selected earlier in the same goal-selector pass.

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/PickupNearbyItemGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Makes idle Player NPCs actively walk to nearby useful dropped items instead of relying only on the small passive collision pickup radius. This goal must stay opportunistic; it should not block log/material work for distant or delayed drops.

## Activation

- Server side only.
- NPC must be alive, not healing, not riding, not no-AI, and not fighting a live target.
- Searches an 8 block horizontal radius and 5 block vertical radius for ordinary nearby drops.
- While post-animal-kill loot priority is active, searches a wider local area around the animal death position.
- Active builder site preparation defers incidental pickup until preparation is ready; post-animal-kill loot priority remains eligible. Log demand alone does not block useful dropped supplies.
- Only targets `InventoryUtils.isInventoryBackedSupplyDrop(...)` items that can fit in the custom inventory.
- Starts only when an actual reachable dropped item entity exists. The priority death position is a search bias, not a standalone wait task.

## Behavior

The goal selects the nearest reachable useful item, sets AI state `ai.player_npc.collecting_item`, pathfinds to the item, looks at it, honors that item's pickup delay, and calls `PlayerNpcEntity.tryPickupItemEntity(...)` after the item enters the NPC's surrounding 3x3x3 pickup neighborhood. Successful pickup sends Minecraft's normal take-item packet so the item visibly flies into the NPC and plays the client pickup sound. It must not sit still waiting for the item to enter inventory.

It runs above worker/resource goals and above `BurnNearbyItemGoal`, so animal drops and other useful supplies are collected before the NPC starts another idle job or burns unwanted items.

When `PlayerNpcEntity` kills an animal or sees its current animal target die, it stores the death position for a short post-kill loot priority window, clears the animal target, and wakes idle work cooldowns. During that window `PickupNearbyItemGoal` uses the death position to widen and bias item search, then paths directly to the selected item. If no item entity exists, the goal does not start and clears the stale priority instead of walking to the old death position and waiting there.

The navigation stop radius only starts the close-approach phase; it does not authorize inventory transfer. Transfer is allowed when the dropped item's box intersects the NPC bounding box expanded by one block on X, Y, and Z, including items one block above or below. If the item is outside that neighborhood, the goal runs an active close-range approach: it keeps moving toward the item, applies a small direct push toward it, and jumps toward it when the item is above, on a ledge, or pathing reports done/stuck. After a short failed close-range approach it tries to mine a local obstruction on the item path.

`PlayerNpcSmartTargetGoal` and `HuntSheepForBedGoal` yield new animal/sheep targets while collectable supply drops are nearby and can fit in inventory. This prevents animal hunting from immediately retargeting another animal before the previous loot is picked up. Monster/player targeting is not blocked by this rule.

When direct item-entity pathing fails, `PickupNearbyItemGoal` tries reachable stand positions around the dropped item before giving up. Path checks require `Path.canReach()` so partial paths do not lock the goal forever. An independent watchdog tracks best distance toward the current item/pillar route target, rather than treating arbitrary horizontal push or jump jitter as progress. This catches paths that remain nominally `done=false`, `stuck=false`, and `canReach=true` while their next node never advances. The watchdog runs outside the close-approach early return, so repeatedly reissuing the same path cannot consume the entire pickup window without recovery.

Target acquisition is a shared-admission batch: sort useful drops by priority/distance, inspect only a bounded prefix, share one total path budget across item and stand paths, and reuse the selected path in `start()`. That one selection path uses `PathNavigationAi.createBoundedPath` with a `0.05F` visited-node multiplier and local loaded-chunk corridor, because a count of one does not make vanilla's default 48-block synchronous search cheap. Do not repath while a live navigation path is still active. When the watchdog/recovery window finally gives up, quarantine that exact dropped entity for one minute so the next activation can collect another reachable drop instead of immediately repeating the same failure loop.

If a nearby physical collision block is blocking the item/stand path, pickup keeps the original item target and delegates the short-capped clear to shared `ClearBlockAi` + `BreakingBlockAi`. Once best-distance progress stalls, it inspects the live body/head collision volume swept toward the next two accepted navigation nodes before falling back to the direct item corridor; this finds the real blocker even when vanilla path status still claims the path is running and reachable. Close-range pickup failure also performs the same physical-block check after a short grace period. This produces `clearing pickup path ...` detail, vanilla and Epic Fight mining swings, normal crack/sound/tool behavior, and guarded main-hand restoration. Plain `ItemName @ x y z` detail is item tracking and does not swing. Non-colliding plants and replaceable clutter are ignored. Current/target support blocks are excluded from the swept test, and the requested target plus any ray-retargeted blocker are revalidated against the pickup distance cap, protected home/build/owned-farm cells, temporary crafting tables and pillar supports, fluids, unbreakable blocks, block entities, and required pickaxe access before destruction. A missing route or unreachable item alone never authorizes clearing.

For an item at least two blocks above and within four horizontal blocks, pickup validates a reachable nearby pillar base, enough carried dirt/stone/eligible planks, and an open or locally clearable vertical body column. At the base it uses shared `PillarUpAi`, so centering, collision checks, placement timing, inventory consumption, temporary support tracking, and one-block-at-a-time climbing match other resource/return AI. The shared helper performs its player-paced delay before jumping and retains an overhead collision target across the airborne/fall cycle; pickup clears the reported blocker once and retries without targeting a temporary pillar, owned farm, home/build block, or temporary crafting table. A path that Minecraft marks complete just outside the tighter pillar-base radius is handled by the same position watchdog instead of endlessly reissuing the completed path.

If no real clear target exists, pillar planning/placement fails, or a distant/unreachable item path makes no position progress, pickup uses bounded `PathStuckFallbackAi` recovery. It first samples a bounded random safe reachable direction and lets the shared fallback choose/force a safe step-off, then retries item pathing. Recovery is limited to three attempts with a retry delay and remains under the existing 12-second pickup lifetime plus failed-pickup cooldown, preventing retry thrash. Expired/removed items are revalidated before recovery, and mere path failure never authorizes block destruction.

`BurnNearbyItemGoal` also reserves inventory-backed supply drops from burning when they cannot be picked up, so meat and animal drops are not disposed of just because the NPC has a burn tool.

## Useful Drop Categories

`InventoryUtils.isInventoryBackedSupplyDrop(...)` includes combat supplies, food, placeable blocks, utility materials, saplings, records, throwable potions, and animal loot such as wool, leather, feathers, eggs, rabbit drops, string, bones, ink sacs, phantom membrane, slime balls, scutes, goat horns, and honeycomb.
