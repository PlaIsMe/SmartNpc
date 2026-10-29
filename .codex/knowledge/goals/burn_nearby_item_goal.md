# BurnNearbyItemGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/BurnNearbyItemGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()` as a priority-5 routine work goal
  with speed `1.0D` and radius `10.0D`.

## Purpose

Lets idle Player NPCs dispose of unwanted dropped items without directly deleting every item in a loop.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Uses the normal startup and adaptive worker scheduler through `addWorkGoal`; priority-3
  nearby-item pickup gets the first chance to collect useful drops.
- Chooses one random grounded item in range.
- Eligibility and route discovery run on staggered >=20-tick cadences with shared expensive-work
  admission. Inspect at most eight randomly rotated item candidates; each route batch creates at
  most two bounded local paths. Reuse live navigation, and never let done/stuck navigation bypass
  the route retry deadline. A successful obstruction break yields before discovering another route.
  Obstruction selection does not advance mining; only the running tick advances progress, avoiding
  a double step when the selector calls `start()` and `tick()` in the same server tick.
- Can pick up/equip useful dropped items without a burn tool.
- Burns unwanted dropped items only when the NPC has flint and steel or a lava bucket in its inventory.

## Behavior

Walks to the item and first tries to pick it up, equip it, or store it if useful. If the item is unwanted and the NPC has a burn tool, it temporarily equips the tool and places fire or lava in the grounded ItemEntity's exact current block cell. Vanilla fire/lava contact owns damage and destruction; the goal never selects and directly discards an ItemEntity.

Flint and steel is preferred when fire can survive in that exact cell. If fire cannot be placed or the NPC has no flint and steel, the goal can use a lava bucket in the same cell. Lava bucket use consumes the lava bucket and returns an empty bucket, matching `UseLavaBucketGoal`.

Ignition is commit-time revalidated against the still-alive grounded target, loaded chunks, world bounds, the world border, Player NPC home/build footprints, and owned farm/work/entrance blocks. It does not use an above-item or neighboring fallback that could ignite a different position.

The ignition cell cannot overlap the acting NPC or another living entity. Fire-resistant
item stacks are not selected for disposal because vanilla fire/lava damage intentionally
leaves them alive. Lava is reclaimed before its first Nether fluid tick can spread; ordinary
fire retains the existing short burn window.

Temporary fire/lava is removed after the short burn window or any goal interruption so the goal does not leave uncontrolled hazards behind. Burn chat describes the successfully placed ignition attempt and continues through the selector-aware `burn_item` datapack event.

## Interrupted item routes

The October collecting-item trace actually alternated `BurnNearbyItemGoal` and
`RespondToNpcAlertGoal`, with occasional ordinary pickup. Disposal was collecting a useful dirt
drop and repeatedly restarted clearing before reaching 15/18 mining ticks. Its old stop cleared
every failure counter, so the advertised failure cooldown never applied.

`ItemApproachAi` now supplies one session-local per-NPC item-route history shared by disposal and
pickup. The first actual start opens a 20-second absolute episode. Alert/preemption stop preserves
that deadline and adds a two-second retry delay. Exhaustion/failure quarantines that exact item for
60 seconds across both goal owners; other drops and passive contact pickup remain available. Memory
is capped at 24 identities and is not saved to NBT. Normal worker-slot suspension does not count as
a pickup interruption. Successful world-item collection still uses the existing inventory/gear
and disposal rules. No blanket log-priority ban was added.

The disposal goal also keeps its own bounded 20-second activation and a real-server-tick 15-second
failure backoff. Interruption cleanup still restores tools and removes temporary fire. Obstruction
discovery/commit skips unloaded chunks and protected farm infrastructure. Inspector details identify
the disposal owner and distinguish clearing an item-collection route from a burn route; they no
longer advertise a hypothetical cooldown as if it were active.
