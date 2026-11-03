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

## Minecraft 1.21.11 builds

This branch uses Java 21, Minecraft 1.21.11, NeoForge 21.11.48 and separate
`common`, `fabric`, and `neoforge` modules. The shared source retains the existing
1.21.11 gameplay, persistence and rendering APIs.

```powershell
./gradlew.bat compileJava
./gradlew.bat assemble
./gradlew.bat :fabric:runClient
./gradlew.bat :neoforge:runClient
./gradlew.bat :neoforge:runData
```

Distributables are `SmartNPC-fabric-1.21.11-2.0.0.jar` and
`SmartNPC-neoforge-1.21.11-2.0.0.jar`, written to
`C:/Users/Admin/Desktop/Minecraft/delivery`. Override with
`-Pdelivery_directory=build/delivery`. Install the jar matching your loader;
common, dev and dev-shadow jars are development inputs.

Fabric needs Fabric API separately. Architectury API 19.0.1 is bundled on both
loaders; Fabric also bundles Forge Config API Port 21.11.1. Existing
`smart_npc-*.toml` filenames, name migration, saved-data names and registry IDs
are retained.

Better Combat 3.1.0 and Player Animation Library 1.1.11 remain optional on both
loaders. Install them and their dependencies separately. Add
`-PcompatRuntime=true` to a development run to include Better Combat, PAL and
Cloth Config. Epic Fight and YSM remain removed on this branch; legacy YSM name
entries are still readable and their model tokens are ignored.

See `.codex/knowledge/multiloader_1_21_11.md` for version-specific details and
validation scope.

## License & Use
GNU GENERAL PUBLIC LICENSE

## Notices
See `NOTICE.md` for credits and third-party notices.