# Player Npc

<p align="center">
  <a href="https://www.youtube.com/@pla_is_me" title="YouTube" style="text-decoration:none;">
    <img src="https://res.cloudinary.com/dxehc2hn3/image/upload/v1761811663/youtube_icon_bcbanf.png" alt="YouTube" width="80" height="80">
  </a>
  &nbsp;&nbsp;&nbsp;&nbsp;
  <a href="https://discord.com/invite/EsvPsnqJpz" title="Discord" style="text-decoration:none;">
    <img src="https://res.cloudinary.com/dxehc2hn3/image/upload/v1761811736/discord_icon_bg_rp4omx.png" alt="Discord" width="80" height="80">
  </a>
  &nbsp;&nbsp;&nbsp;&nbsp;
  <a href="https://ko-fi.com/pla_is_me" title="Ko‑fi" style="text-decoration:none;">
    <img src="https://res.cloudinary.com/dxehc2hn3/image/upload/v1761815764/kofi_icon_bg_sy3foi.png" alt="Ko‑fi" width="80" height="80">
  </a>
  &nbsp;&nbsp;&nbsp;&nbsp;
  <a href="https://www.curseforge.com/minecraft/mc-mods/epicfight-clash-blade" title="curseforge" style="text-decoration:none;">
    <img src="https://res.cloudinary.com/dxehc2hn3/image/upload/v1761811729/cruseforge_icon_bg_gucfxc.png" alt="Curseforge" width="80" height="80">
  </a>
  &nbsp;&nbsp;&nbsp;&nbsp;
  <a href="https://modrinth.com/mod/epicfight-clash-blade" title="modrinth" style="text-decoration:none;">
    <img src="https://res.cloudinary.com/dxehc2hn3/image/upload/v1761815749/modrinth_icon_bg_q4jxrz.png" alt="Modrinth" width="80" height="80">
  </a>
</p>

## License & Use
GNU GENERAL PUBLIC LICENSE

## Notices
See `NOTICE.md` for credits and third-party notices.

## Minecraft 1.20.1: Fabric and Forge

The project follows the `common` / `fabric` / `forge` layout used by the Better Combat
1.20.1 reference. `common` contains NPC AI, entities, inventory, rendering, networking,
configs and shared assets. Loader entrypoints, event adapters, persistent-data access
and optional APIs live in `fabric` and `forge`. Epic Fight and Combat Evolution remain
Forge integrations. Better Combat and Yes Steve Model have optional integrations on
both loaders.

Install **JDK 17 and JDK 21**, and run Gradle with **JDK 21**. Compilation and game
development runs use **Java 17** for Minecraft **1.20.1**. The newer build JVM is
needed by the Loom tooling used by the pinned Fabric YSM release.

```powershell
./gradlew.bat compileJava
./gradlew.bat assemble
./gradlew.bat :fabric:runClient
./gradlew.bat :forge:runClient
./gradlew.bat :fabric:runServer
./gradlew.bat :forge:runServer
./gradlew.bat :forge:runData
```

Use `./gradlew` on Linux/macOS. Build a single loader with `:fabric:assemble` or
`:forge:assemble`. Production jars are remapped and written to the existing delivery
folder configured by `delivery_directory` in `gradle.properties`:

- `SmartNPC-fabric-1.20.1-1.0.3.jar`
- `SmartNPC-forge-1.20.1-1.0.3.jar`

Override the destination with `-Pdelivery_directory=build/delivery`. Jars under each
module's `build/devlibs` and `build/libs` with `dev` or `dev-shadow` classifiers are
development artifacts. The common jar is shared build input.
Install the jar for your loader. Fabric needs Fabric API; Architectury API 9.2.14 and
Forge Config API Port 8.0.3 are bundled inside the Fabric jar, so separate downloads
are unnecessary. The config port keeps the existing `smart_npc-*.toml` files and name-list
migration available on Fabric. Forge also bundles Architectury API 9.2.14; a separate
Architectury download is unnecessary on either loader.

The optional Fabric integrations are pinned for development to Better Combat
`1.9.0+1.20.1-fabric` and YSM `2.6.5-fabric+mc1.20.1`. YSM uses a separate typed adapter
for each loader because its released classes and animation attachments differ. YSM
model selection stays server-authoritative, including configured `YSM:<filename>`
preferences, saved models/textures and emotes. Unsupported YSM versions use normal NPC
skins. With supported YSM available, `smart_npc-compat.toml` / `modelChance` chooses
custom models versus YSM Steve/Alex: `0.0` always uses Steve/Alex matching the regular
skin shape; `1.0` chooses other usable server-installed or bundled models. Intermediate
values roll once per NPC. Steve/Alex is the fallback when no custom model is usable.
Install each optional mod's dependencies as well; these optional mods are not bundled.

For a development client with the pinned Better Combat, Player Animator, Cloth Config
and YSM versions installed, use `:fabric:runClient -PcompatRuntime=true` (or
`:forge:runClient -PcompatRuntime=true`). Ordinary development runs keep those mods
optional. Each loader has its own `run` directory.

The Forge data run retains the shared generated-resource destination at
`common/src/generated/resources`. Maven publishing retains the local `mcmodsrepo`
destination, with separate loader coordinates and remapped binary/source artifacts.

Compilation, packaging and client startup were checked on both loaders. Fabric also
starts with the pinned Better Combat/YSM dependencies installed. YSM's native library
reports `err: 54` in this development environment, so custom YSM rendering and in-world
NPC behavior still need gameplay verification.
