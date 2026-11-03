# Minecraft 1.21.1 multi-loader layout

## Baseline and build

This checkout was migrated from the working Minecraft 1.21.1 / NeoForge 21.1.250 / Java 21 source, version 2.0.3. The root AGENTS.md's 1.21.11 description does not describe this branch. Shared gameplay was retained from this branch; the local BetterCombat-1.21.1 and SmartNpc-1.20.1 references supplied the multi-loader pattern and selected platform fixes, not replacement gameplay.

- `common`: gameplay, configs, identities, shared renderer logic, packet codecs/handlers and platform interfaces.
- `fabric`: Fabric entry points/events/mixins, vanilla API adaptations and the Fabric YSM 2.6.5 typed adapter/renderer.
- `neoforge`: NeoForge entry points/events/network registration, native platform implementations, NeoForge YSM and existing Epic Fight integration/resources.

The wrapper is Gradle 8.14.3 with Architectury plugin 3.4.164, Loom 1.11.458, Shadow 8.1.1 and Java 21. Root `compileJava`, `assemble` and `build` delegate to the modules. Use `:fabric:runClient`, `:neoforge:runClient`, or their `runServer` tasks. `:neoforge:runData` writes common generated resources. No automated test suite was introduced or run for this migration.

Distributables remain in the original delivery directory `C:/Users/Admin/Desktop/Minecraft/delivery`:

- `SmartNPC-fabric-1.21.1-2.0.3.jar`
- `SmartNPC-neoforge-1.21.1-2.0.3.jar`

Override with `-Pdelivery_directory=build/delivery`. Common, dev and dev-shadow jars are build inputs, not installation artifacts. Fabric requires Fabric API separately. Architectury 13.0.11 is nested in both outputs; Fabric also nests Forge Config API Port 21.1.6. Users need not download those two libraries separately. Fabric development pins Loader 0.16.14 and API 0.116.17+1.21.1; the production check used Loader 0.20.1.

## Platform boundaries and preservation

Native callbacks preserve each original NeoForge handler's event priority and `receiveCanceled` setting. Shared event wrappers carry initial cancellation through to the native event. Fabric dispatches equivalent hooks through Fabric API, Architectury and small mixins. Loader implementation packages are distinct to avoid NeoForge module split-package conflicts.

NeoForge retains the original native custom-payload registration, packet codecs and protocol 8. Fabric uses Architectury's typed payload transport with the same codecs and shared handlers. Server enqueue/range/ownership validation remains in those handlers. Names/config filenames, registry IDs and saved-data keys remain unchanged. Fabric entity/block-entity persistence uses the `NeoForgeData` tag so shared plans still use the same persistent-data structure. Config Port 21.1 uses its **NeoForge v4** registry/events API, not the older Forge v2 API.

`NpcPlatform` owns extension APIs for persistent data, damage/durability, custom arrows, experience, attributes, item abilities, teleportation, equipment slots and saved-data factories. Fabric's bow movement adapter follows the 1.21.1 ranged bow goal, which vanilla restricts to monsters; NeoForge delegates to its native generalized goal. Native extension hooks remain in the NeoForge implementation.

Common access widening exposes the projectile constructor, Mob selectors and the clamped `ItemProperties.register` overload. Fabric ships that access widener; NeoForge ships matching access transformers. Exposing the item-property overload on only the common compile classpath causes a NeoForge client IllegalAccessError. The packet-listener send mixin targets 1.21.1's `ClientCommonPacketListenerImpl`.

Fabric tab entries use a display-only ServerPlayer with an inert packet listener. It never joins the world/player list or opens a connection. A plain ServerPlayer has a null listener and crashes 1.21.1 `PlayerInfoUpdatePacket.Entry`; NeoForge continues using its native FakePlayerFactory. Force-tick initialization ignores unresolved identities and guards reentry during world generation. ClearBlockAi and farming ray contexts carry the NPC instead of null, preserving Fabric vanilla collision-context requirements.

## Optional compatibility

Better Combat remains optional on both loaders. Development pins its 2.4.0 releases, matching Player Animator and Cloth Config. `-PcompatRuntime=true` adds those integrations plus YSM to development runtime, and Epic Fight on NeoForge. Tiny Config is explicit in those development configurations because nested Better Combat libraries are not always present on Loom's remapped development classpath. Production Better Combat resolves its own nested library normally.

Epic Fight sources, mixins and animation resources exist only in `neoforge`. Fabric uses its fallback/no-op platform boundary. Do not add Epic Fight types to common initialization or Fabric's classpath.

YSM adapters are pinned to `2.6.5-fabric+mc1.21.1` (Modrinth eGOUtHWJ) and `2.6.5-neoforge+mc1.21.1` (HZWaR0LY). Their obfuscated classes/methods and animation capability/attachment APIs differ; use the matching adapter. Missing, unsupported or native-unavailable YSM falls back to ordinary rendering.

When supported YSM is available, NPCs always receive a model:

- `modelChance=0.0`: `misc/2_steve` or `misc/1_alex`, chosen from the NPC skin's wide/slim metadata, with the matching vanilla UUID fallback.
- `modelChance=1.0`: a usable non-Steve/Alex model from bundled/server-installed models. The separately bundled `default` character remains eligible.
- Intermediate chance: persist one custom/base decision per NPC. `YsmCustomModel` is the new optional boolean; existing model/texture/random fields remain readable. Endpoints reconcile previous selections. Missing custom choices fall back to base and can retry later.

Authlib 6 profile textures use the session service skin metadata API. Both YSM display players copy the actual NPC profile and resolved skin/model shape. See `yes_steve_model_compat.md` for retained historical details, which the current-version section supersedes.

## Validation and limits

The original source compiled before restructuring. Both modules subsequently compiled and assembled successfully. NeoForge development client startup passed both with base dependencies and with Better Combat/Epic Fight installed. This checks startup/linkage, not every native combat behavior. YSM's native library returned `err:54` in the NeoForge development run, so that run does not establish successful native YSM rendering.

A standalone **production Fabric client** loaded the deliverable with Fabric Loader 0.20.1, Fabric API, Better Combat, Player Animator, Cloth Config and YSM 2.6.5. A separate generated world ran with an active named NPC, Better Combat registry/config synchronization, ongoing gathering and no adapter/ticking crash. Saved NPC inventory contained harvested logs/planks/saplings. A live ClearBlockAi ray preflight also succeeded. No original user world was modified; validation fixtures and source backups are ignored under `build`.

Saved-state checks in that production world established both YSM endpoints on the same NPC: chance zero saved `misc/2_steve`, `YsmCustomModel=false`; changing to one saved `wine_fox/04_kongfu`, `YsmCustomModel=true`. Production Fabric YSM initialized successfully, unlike the NeoForge development native-library limitation. These are focused checks, not exhaustive gameplay/weapon-pack or multiplayer coverage.

Attaching a JDWP-enabled production Fabric launch also triggers YSM `err:54`; use an ordinary production launch when validating its native runtime. The successful non-debug logs still contain nonfatal missing entity data-fixer notices, the existing uppercase `chat/HOW_TO_USE.txt` resource-path warning, and Config Port's optional Night Config `IoUtils` mixin-target warning. Do not describe those runs as completely warning-free.
