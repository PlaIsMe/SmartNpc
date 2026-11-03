# Minecraft 1.20.1 multi-loader layout

The actual checkout is Smart NPC 1.0.3, Minecraft 1.20.1 and Java 17. The root AGENTS.md
overview of a newer NeoForge port is historical context, not this branch's build.

The root Gradle project includes `common`, `fabric` and `forge`, following the local
`reference/BetterCombat-1.20.1` structure with official Mojang mappings. Architectury
Loom remaps each loader's shaded common output. Run Gradle with JDK 21; compilation
uses the Java 17 toolchain. `assemble` builds both loaders without running tests.

Production output retains `C:/Users/Admin/Desktop/Minecraft/delivery`, configurable
with `delivery_directory`. Artifact base names are `SmartNPC-forge-1.20.1` and
`SmartNPC-fabric-1.20.1`, followed by the mod version. Use remapped production jars,
not the intermediate development jars.

Shared gameplay lives under `common/src/main/java/com/pla/smart_npc`. Registries use
Architectury deferred registration. `SmartNpc.init()` is called by one loader
entrypoint. `platform/NpcPlatform` owns loader extensions, including tool actions,
entity persistent data and tab-list proxy players. Existing ForgeData tags are kept
for saved entity plans and inspector state on Fabric.

`platform/event/NpcEvents` and `NpcClientEvents` are loader-neutral handler arguments;
they do not install another event bus. The explicit `NpcEventHooks` and client hooks
route lifecycle calls into the retained behavior classes. Forge events and Fabric /
Architectury callbacks feed those hooks. Fabric mixins supply persistent entity data,
living tick callbacks, inspector input/screen/HUD hooks and spawn placement access.
Dedicated-server hooks do not contain client types.

Packets use Architectury NetworkManager with explicit C2S/S2C registrations. Protocol
8 is included in packet identifiers. Retain queued server application and the existing
sender, target and distance validation when changing packets.

Forge Config API Port supplies the existing ForgeConfigSpec/ModConfig APIs on Fabric;
only the config package intentionally retains these shared API imports. Fabric declares
it as required and bundles it along with Architectury using Loom `include`. Fabric Loader
resolves these nested mods without separate downloads; keep the dependency declarations
so versions remain checked. Both loaders retain TOML names, defaults and name-list migration.

Better Combat's optional reflection bridge is shared, including attack/holding poses
and Player Animator sampling. `Platform.isModLoaded` replaces Forge ModList checks.
Fabric's pinned development version is Modrinth `OtwNg4r4` (1.9.0).

YSM is pinned independently to Forge `Zqooxsd2` and Fabric `LF0NYrRj` (2.6.5). Both
adapters use each release's actual obfuscated API, behind shared optional boundaries.
The server registry, filename matching, one-time selection, texture fallback and emote
logic are retained. Forge's display player exposes YSM's animation capability; Fabric's
display player exposes YSM's animation attachment through its injected interface.
The renderer receives the animation directly; display players never join the world.
Exact version gates and API-failure fallback remain in place.

Epic Fight, Combat Evolution, their optional mixins and Forge biome modifiers live in
`forge`. Fabric uses native biome modifications with the configured spawn settings.
`-PcompatRuntime=true` installs the pinned Better Combat/YSM development dependencies;
default runs do not require them. See README.md for build/run commands and dependencies.

Validation results are recorded after compilation, packaging and startup checks; source
presence alone does not establish gameplay or animation correctness.

## Development launch details and verification (2026-11-03)

- Minecraft development runs explicitly use JDK 17; the Gradle process uses JDK 21.
- Keep Forge classes in packages distinct from common classes. Forge's module-based
  development launcher rejects shared/split packages. The Forge renderer subscriber is
  in `forge.client`, and each YSM display renderer is in its loader's `ysm.forge` or
  `ysm.fabric` package.
- The common mixin config intentionally omits `refmap` in source. Architectury's runtime
  transformer needs named development selectors. Each loader's `remapJar` inherits the
  common `MixinRefmapService` options and injects the common refmap into the production
  config. The final Forge common refmap must contain `searge` mappings; Fabric must
  contain `named:intermediary`. Keep separate common and platform refmap filenames.
- Fabric copies the shared access widener into its own development resources because
  Fabric Loader resolves that file relative to the mod container. Forge converts it to
  an access transformer during production packaging.
- MixinExtras Forge 0.5.0 nests its common bootstrap. The Forge development classpath
  also needs the plain common implementation dependency. The platform jar remains
  included for production.
- `compileJava` and `assemble` passed. Final jar inspection checks loader metadata,
  common/platform refmaps, access widening and absence of duplicate zip entries.
- Forge and Fabric client startup reached resource/renderer initialization without the
  optional integrations. Fabric also reached that stage with Better Combat 1.9.0,
  Player Animator, Cloth Config and YSM 2.6.5 installed, on Java 17 and Java 21.
- YSM itself failed native initialization with `err: 54` on this development machine.
  Its adapter compiles against the actual release, but native custom-model rendering,
  NPC animations and gameplay were not verified in-world. Do not describe this as full
  YSM runtime certification. No automated test suite was created or run.

## Bundled Fabric dependencies and YSM chance update (2026-11-03)

Fabric's `include` configuration now nests unmodified Architectury API 9.2.14 and Forge
Config API Port 8.0.3 alongside MixinExtras. Keep their `fabric.mod.json` dependency
entries: Fabric Loader resolves the nested copies and still checks versions. Players
need only Fabric API separately. Forge now also includes its Architectury API jar via
Loom `include`, so no separate Architectury installation is required on either loader.
Keep Forge's mandatory Architectury metadata dependency: the nested jar satisfies it
and Forge can select a compatible separately installed version when present.
The embedded libraries' licenses/metadata are retained; NOTICE.md
identifies their upstream sources.

Forge packaging verification: `:forge:assemble` passed and the final jar contains
`META-INF/jars/architectury-forge-9.2.14.jar`, with a matching `dev.architectury` /
`architectury-forge` entry and version `[9.2.14,)` in `META-INF/jarjar/metadata.json`.
The nested jar retains its Forge mod metadata. Resolve the current delivery path from
`gradle.properties` when inspecting results; an older jar at the previous location is
not the current build.

The production Fabric jar was launched in `build/production-fabric-check` with Loader
0.20.1, Java 17 and only Fabric API beside Smart NPC in `mods`. The loader listed both
libraries as children of Smart NPC, initialized its configs/resources, and entered an
integrated world where NPCs spawned. The client shut down and saved normally. The
isolated launch uses the exact ASM/Mixin versions from 0.20.1's `fabric-installer.json`;
mixing that loader with the older development classpath's Mixin library is invalid.

Both loader adapters now exclude the exact bundled Steve/Alex IDs from custom selection
and can explicitly select either base model. YSM always supplies a usable appearance:
chance zero uses wide/slim Steve/Alex, chance one uses other server/bundled models, and
intermediate values persist one decision in the server-only `YsmCustomModel` NBT tag.
Unavailable custom choices fall back to Steve/Alex and can recover after registry
reloads. See `yes_steve_model_compat.md` for migration and skin-profile handling. This
does not remove the native-availability limitation recorded above.

## Block-clearing ray context on Fabric (2026-11-03)

Vanilla/Fabric 1.20.1's entity-based `ClipContext` constructor calls
`CollisionContext.of(entity)`, which requires a real entity. Passing `null`, tolerated
by the earlier Forge path, crashes inside `EntityCollisionContext` while an NPC works.
This affects runtime AI; reaching world entry alone does not cover it.

`ClearBlockAi` now carries the owning NPC through both current-position and hypothetical
stand ray helpers into `clipBreakRay`. Stand eye coordinates and target selection remain
explicit; the NPC supplies the collision context. `FarmSetupGoal`'s stand-clear ray also
uses its NPC. All 13 `new ClipContext` call sites across the modules were reviewed; none
now passes an explicit null, and the bow helper's owner is supplied by its shooter.

`compileJava` and `assemble` passed for both loaders. The packaged Fabric jar was run on
Java 17 / Loader 0.20.1 / Fabric API 0.92.12 with a separate copy of the reported world.
A JDB method breakpoint observed the exact failing `GatherLogsGoal.tryStartClearBlock`
through `findBreakRayBlocker` call chain, confirmed a non-null `PlayerNpcEntity` argument,
and stepped out of `clipBreakRay` successfully. This directly exercises the reported
runtime path; farming's equivalent correction was reviewed and compiled. Debugger pauses
can cause temporary "Can't keep up" messages in this diagnostic run. No test suite was
added or run, and the original world/configs were not modified.
