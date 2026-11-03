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

## Minecraft 1.21.1 builds

This branch uses Java 21 and separate `common`, `fabric`, and `neoforge` modules.
The shared gameplay starts from the existing 1.21.1 source. NeoForge remains pinned to
21.1.250; the Fabric development API is 0.116.17+1.21.1.

```powershell
./gradlew.bat compileJava
./gradlew.bat assemble
./gradlew.bat :fabric:runClient
./gradlew.bat :neoforge:runClient
./gradlew.bat :neoforge:runData
```

The builds write `SmartNPC-fabric-1.21.1-2.0.3.jar` and
`SmartNPC-neoforge-1.21.1-2.0.3.jar` to `C:/Users/Admin/Desktop/Minecraft/delivery`.
Override this with `-Pdelivery_directory=build/delivery`. Install the jar for your loader;
module jars marked `dev` or `dev-shadow` and the common jar are development inputs.

Fabric needs Fabric API separately. Architectury API 13.0.11 is bundled on both loaders,
and Fabric also bundles Forge Config API Port 21.1.6. Existing `smart_npc-*.toml` filenames,
name migration and registry IDs are retained.

Better Combat is optional on both loaders. YSM support is pinned to the matching
`2.6.5-fabric+mc1.21.1` or `2.6.5-neoforge+mc1.21.1` release. Epic Fight stays optional
on NeoForge; Fabric contains no Epic Fight adapter, mixins or animation assets.
Install optional mods and their own dependencies separately. For a development run with
those integrations, add `-PcompatRuntime=true`; NeoForge then includes Epic Fight too.

In `smart_npc-compat.toml`, YSM `modelChance = 0.0` uses Steve/Alex matching the regular
skin's wide/slim shape. `1.0` selects other usable server-installed or bundled models.
Intermediate values save one custom/base choice per NPC. Steve/Alex is the fallback when
no custom model is usable. Missing, unsupported or native-unavailable YSM uses regular
NPC rendering. See `.codex/knowledge/multiloader_1_21_1.md` for validation details.

## License & Use
GNU GENERAL PUBLIC LICENSE

## Notices
See `NOTICE.md` for credits and third-party notices.