# Minecraft 1.21.11 multi-loader port

This branch retains Minecraft 1.21.11, NeoForge 21.11.48, Java 21 and mod version 2.0.0. The 1.21.1 multi-loader commit was cherry-picked with conflicts; resolve gameplay/rendering/persistence conflicts toward the existing 1.21.11 implementation, then adapt its platform calls. Do not restore 1.21.1 gameplay or the removed Epic Fight/YSM integrations. The user explicitly confirmed keeping Better Combat and leaving those integrations removed.

## Layout and tooling

- `common/src`: the branch's gameplay, configs, packet codecs/handlers, modern render states, shared assets/data and platform interfaces.
- `fabric/src`: Fabric bootstrap/events/mixins, platform implementations and vanilla bow movement adaptation.
- `neoforge/src`: native bootstrap/events/networking/platform implementations, biome modifier and native ticket accessors.

Gradle 9.2.1, Architectury plugin 3.4.164, Architectury Loom 1.14.476 and Shadow 9.2.2 replace the single-module ModDev build. Java toolchain/release stay 21. Root `compileJava`, `assemble`, `build` and `publish` delegate to modules. Use `:fabric:runClient`, `:neoforge:runClient`, the corresponding `runServer` tasks, and `:neoforge:runData` (client data generation). Server runs pass `--nogui`. Generated data belongs in `common/src/generated/resources`. There is no automated test suite; no tests were added or run. The old ModDev GameTest run configuration is not a ported test suite.

Final remapped installation jars go to the preserved original path `C:/Users/Admin/Desktop/Minecraft/delivery`:

- `SmartNPC-fabric-1.21.11-2.0.0.jar`
- `SmartNPC-neoforge-1.21.11-2.0.0.jar`

Use `-Pdelivery_directory=build/delivery` to override it. Common/dev/dev-shadow jars are development artifacts. Architectury API 19.0.1 is nested in both installation jars; Fabric additionally nests Forge Config API Port 21.11.1 and its Night Config libraries. Fabric API remains a separate required installation. Development pins Fabric Loader 0.18.4 and Fabric API 0.141.6+1.21.11. Config Port now uses **`fabric.api.v5.ConfigRegistry` / `ModConfigEvents`**, not the 1.21.1 `neoforge.v4` package. Explicit development Night Config dependencies match the port's bundled 3.8.3.

Better Combat 3.1.0 and Player Animation Library 1.1.11 remain optional. `-PcompatRuntime=true` adds matching Better Combat, PAL, Cloth Config, Tiny Config, MochaFloats 4.1.5 and Javassist 3.30.2-GA to each development runtime. Tiny Config is explicit because Loom does not consistently expose Better Combat's nested library in a development run. It remains Better Combat's own nested dependency in production; Smart NPC does not bundle optional combat mods. Runtime pins are recorded in module build files. External PAL classes stay behind the existing Better Combat client gate.

## Version-specific boundaries

Keep Identifier, Authlib 7 immutable profiles, ValueInput/ValueOutput, codec-based SavedDataType and SubmitNodeCollector/AvatarRenderState APIs. The 1.21.1 typed YSM adapters, configs/emotes, Epic Fight hooks/resources and old item predicate registration do not belong on this branch. Legacy `YSM:` roster tokens remain ignored without losing identities.

`NpcPlatform` retains NeoForge hooks for custom arrows, durability, experience, item attributes, equipment and teleportation. Fabric uses equivalent vanilla/data-component APIs and mixin-backed `NeoForgeData` persistence on entities/block entities. Do not replace native dynamic attribute resolution with a component-only lookup on NeoForge. Shared packet codecs and protocol 8 remain intact; native payload registration and Fabric typed transport share the existing validated handlers. NeoForge's send-to-server API now lives in `ClientPacketDistributor`.

Native event adapters retain original handler priority and cancellation semantics; shared handlers use loader-neutral event wrappers. Reload listeners preserve their named IDs and the new SharedState reload signature. Fabric registers equipment, chat and blueprint loaders through its resource API.

`ValueInput.keySet` and the three-argument SavedDataType constructor are NeoForge extensions. Shared code uses `SmartNpcNbt.contains(input, key)` with Codec.PASSTHROUGH and the vanilla four-argument SavedDataType constructor, preserving filenames and the native constructor's null data-fixer type. Shared item helpers dispatch dynamic modifiers through the platform boundary.

NeoForge keeps its TicketController and controller-owner inspection behind native implementations/accessors. Fabric registers its own nonpersistent loading/simulation ticket type during bootstrap, uses the same level-31 chunk admission, and tracks owners per level/chunk so one NPC cannot release an overlapping NPC's ticket. Existing saved NPC locations restore admission; there is no new competing world plan. ExternalChunkActivity ignores Smart NPC's own tickets on each platform. Fabric tab entries retain the inert display-only player/listener; the network Connection type must be qualified because ServerPlayer inherits a waypoint interface with another nested `Connection` type.

FakePlayerRenderState explicitly carries partialTick on both loaders; vanilla's base render state lacks NeoForge's extension field. Better Combat model/item layers read this field. The inspector's local-player submission filter targets LivingEntityRenderer's actual submit implementation and checks for an avatar state. AvatarRenderer inherits that method rather than declaring it. Fabric keyboard input uses ClientInput and no-argument tick; contextual HUD filtering wraps the current contextual-bar renderer calls instead of the removed jump-meter method.

Both item definitions under `assets/smart_npc/items` must be in common resources: Git's directory rename detection left those 1.21.11-only files under the old `src` path during the cherry-pick. The old shared pack.mcmeta is omitted: the loaders synthesize correct metadata for each pack type. Minecraft 1.21.11 uses different resource/data formats and legacy thresholds; one shared min/max range either requires supported_formats on the data side or rejects it on the resource side. Fabric's ModPackResourcesUtil supplies metadata using the active PackType.

## Validation

Compilation and assembly passed for both modules. Base Fabric and NeoForge clients reached resource/renderer initialization. An isolated Fabric dedicated world loaded all 67 blueprint layouts, 9 chat files and 39 equipment entries, accepted a named NPC summon, ran it without a ticking crash, and shut down saving all dimensions. The live NPC remained at 20 health and changed position. NeoForge also loaded the saved validation world with Better Combat/PAL/Cloth installed and active NPCs. Fabric loaded that world with the same optional integration stack and synchronized Better Combat weapon attributes. No user instance configs or existing worlds were changed.

Validation fixtures/logs and the pre-resolution worktree/index and original HEAD source snapshots are ignored under `build`. No automated tests were introduced. Client startup alone does not prove every combat animation, weapon pack, inspector interaction or long-running job; record further in-world checks here when completed.

Final `compileJava assemble` passed after all code/resource fixes. Both installation archives were checked for their exact loader/version metadata, nested libraries, 67 blueprints, both item definitions and refmaps, with no duplicate entries or removed compatibility classes. The final Fabric and NeoForge compatibility world runs had no missing-class failures, missing item models, pack-metadata errors or unexpected ticking exception. Both validation clients saved and closed normally. Git conflicts were resolved and the changes staged; no commit was created.

Known nonfatal development diagnostics include offline-session/profile-service warnings, the existing uppercase `chat/HOW_TO_USE.txt` resource warning, and Loom's native unprotect transformation-rate diagnostic. These runs are not evidence that every animation, weapon pack or long-running gameplay routine has been playtested.
