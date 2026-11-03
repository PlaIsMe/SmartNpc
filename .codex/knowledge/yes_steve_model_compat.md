# Yes Steve Model compatibility (Minecraft 1.20.1)

## Multi-loader update

The `common` / `fabric` / `forge` layout now shares model selection, filename matching,
saved appearance and emote behavior. Forge is pinned to `Zqooxsd2`; Fabric is pinned to
`LF0NYrRj`, both YSM 2.6.5 for 1.20.1. Obfuscated APIs differ between those releases:
typed server adapters and display renderers live in each loader module. Fabric exposes
only YSM's animation attachment through its injected capability interface; Forge keeps
the animation capability. Both render the explicit animation/display proxy and retain
the synchronized server-model-cache identity check.

The shared gate checks the exact version for the active loader. Missing or unsupported
YSM retains normal NPC rendering. `-PcompatRuntime=true` installs pinned optional mods
for development; ordinary builds do not bundle YSM. See `multiloader_1_20_1.md` and
README.md for current commands. The Forge-specific symbols and historical verification
below describe the original adapter and must not be reused as Fabric API names.

This checkout's actual build is Minecraft 1.20.1 / Forge 47.4.4 / Java 17, despite the
26.1.2 overview in AGENTS.md. The adapter targets the released **YSM
2.6.5-forge+mc1.20.1**, Modrinth version `Zqooxsd2`. Its distributed classes are
obfuscated; their exact names are intentionally isolated in `compat/ysm` and
`client/compat/ysm`. The unpublished upstream rewrite is not this API.

`build.gradle` uses Modrinth Maven and `compileOnly fg.deobf`, so YSM is neither bundled
nor installed by the runtime configuration. Metadata declares an optional dependency.
The generally loaded facade checks the exact release and native availability before
linking the typed adapter. Missing/unsupported YSM uses normal NPC rendering; API/linkage
failures warn once and disable the affected bridge. This does not repair failures in YSM's
own native library or make other YSM releases compatible.

## Server authority and identity

`config/smart_npc-compat.toml` contains:

```toml
[yes_steve_model]
modelChance = 1.0
```

This is a COMMON config; only the logical server consults its value. The range is
0.0-1.0. Supported, native-available YSM always supplies an appearance when its server
registry has the appropriate models:

- `0.0` uses `misc/2_steve` for wide skins and `misc/1_alex` for slim skins, including
  existing NPCs. It does not disable YSM rendering or emotes.
- `1.0` chooses another usable server model, including bundled models and server-installed
  packs. The two exact Steve/Alex IDs are excluded from that lottery. YSM's `default`
  model is a separate bundled character and remains eligible.
- Intermediate values roll once when an NPC first receives an appearance: success picks
  a custom model (honoring `YSM:<filename>`); failure uses Steve/Alex. Already assigned
  appearances do not reroll when changing between intermediate values. Moving to zero
  replaces an existing custom appearance with Steve/Alex; moving to one upgrades a base
  appearance to a custom choice. The former custom ID is not retained across that change.
- If custom selection has no usable result, Steve/Alex is the fallback. Base-only appearances
  revisit skin shape every staggered refresh, allowing asynchronous profile resolution
  to correct an initial wide/slim fallback. Custom choices temporarily using the base
  fallback also retry custom selection after registry changes.

`initializeSpawn` runs only on the server during completed initialization or the staggered
20-tick check. It waits for YSM's asynchronous registry publication before selecting.
`YsmChoiceMade`, `YsmModel`, `YsmTexture`, and `YsmRandomModel` keep their existing save
format. A nullable server-only `YsmCustomModel` Boolean now persists the chance result,
including a custom choice temporarily falling back to Steve/Alex. This prevents repeated
chance rolls when the registry is empty and allows recovery after a model reload. Old NPCs with an empty saved model now receive an appearance too; a legacy failed
chance or spawning before YSM installation no longer permanently excludes them.

The custom lottery gives each usable model equal weight and uses its first server-listed
texture. Base selection uses the chosen model's declared default texture or first available
texture. Skin shape comes from existing Authlib SKIN metadata; if absent, it matches
1.20.1 DefaultPlayerSkin's 18-entry UUID fallback without loading a client class. No new
network profile request is made by model selection.

Both display-player adapters expose the NPC's resolved GameProfile for YSM's Steve/Alex
skin resolver. Overriding only `getSkinTextureLocation` is insufficient: YSM reads the
profile through SkinManager directly. Display players retain their own entity UUID and
never join the world or player list.

Missing/unsupported/native-unavailable YSM still uses ordinary NPC rendering. Client-only
models never enter server selection. Synced entity data already carries the resulting
model, texture, active flag and emote state; this change requires no wire-format change.

## Per-name model preference (2026-11-03)

The names-config interest list accepts one optional `YSM:<filename>` directive:

```toml
[playerNpcNames]
Technoblade = ["EXPLORING", "HUNT_MONSTERS", "LOOTING", "TROLL_HIT", "YSM:GI_Paimon"]
```

`SmartNpcNamesConfig.NameEntry` keeps this appearance preference separate from the
`PlayerNpcInterest` enum. A real job is still required; empty or repeated YSM directives
are invalid. The `YSM:` prefix is case-insensitive, but filename spelling/case is preserved.
Both table conversion and legacy-list migration retain the directive. Spawn lookup uses
the Minecraft skin-name key (also for a `skinName:Display Name` entry), with the same
first-entry-wins handling of duplicate names as the identity pool.

After the usual one-time `modelChance` roll succeeds, an explicit preference resolves
against the loaded **server** registry. It uses that model's declared default texture,
or its first texture if the declared default is absent. An unavailable preference resolves
through YSM's server default-model API (`DefaultModelId` / `DefaultModelTexture`, normally
`default` / `default`); if necessary it tries the server's built-in `default` entry.
If no usable server default exists, it keeps the normal NPC skin. It never substitutes
a random model for a missing named preference. Names without a preference retain the
existing random-model selection. Absent/unavailable YSM still uses ordinary skins; a failed chance roll now selects YSM Steve/Alex.

Use the server filename **without `.ysm`**, e.g. `GI_Paimon.ysm` uses
`YSM:GI_Paimon`. `YsmModelFileIndex` builds filename aliases from the authoritative
YSM server registry keys, which retain the `.ysm` suffix in the pinned 2.6.5 release.
It removes that suffix and any parent path for the config alias, retaining exact case.
Different loaded IDs with the same basename are ambiguous and use the default.
Only `.ysm` entries qualify for filename aliases; folder-format built-ins remain
available to the random selection and default-model paths.

The index is cached by YSM registry-map identity, rebuilt on reload, and cleared across
server lifecycles. It has no filesystem scanning, header parsing, or asynchronous work.
YSM itself determines which model files successfully load on the server. Client-only
storage is never consulted. Success and fallback are logged once per filename/generation.
After adding/renaming model files, reload YSM or restart before spawning new NPCs.

The resolved model and texture, including a default fallback, are saved once. Config edits,
reloads, and later installation of the preferred model do not change existing choices.
Removing a previously config-assigned model retains the normal-skin fallback behavior
without replacing its saved identity. The directive does not alter entity/network layout.

## Rendering

`FakePlayerRenderer` attempts the optional renderer before its normal vanilla/Better
Combat/Epic Fight path. A display-only RemotePlayer supplies YSM's actual player animation
controllers with copied NPC pose, equipment and motion. It is never added to the world,
player list, scoreboard or server. The display exposes only YSM's animation capability.

The selected model must exist in YSM's connection-synchronized cache and resolve to that
exact entry; missing/not-yet-synchronized models fall back rather than drawing YSM's default
as a replacement. Local-model selection is never used. The original NPC renderer retains
name/inspector behavior. Observing the NPC through the inspector's body-camera mode uses
the normal body renderer to retain its head-hiding behavior.

## Emotes and lifecycle

See `goals/ysm_idle_emote_goal.md`. Emote names come from the selected server model's
roulette/default and grouped extra-animation maps, matching YSM's own maid server roulette
selection. Models without entries simply do not emote. SocialNoticeAi plays one randomly
chosen entry when its existing notice starts; notice timing/chance/retained work handling
remain authoritative. Completion or cancellation stops only that notice's sequence token,
so a previous owner cannot cancel a newer animation.

Transient emotes are not restored from NBT. A currently tracking or newly joining client
receives current animation state through synced data; remaining animation phase is not
network-synchronized. Idle cooldown is a saved entity integer decremented centrally.

## Verification and references

Java 17 `compileJava` and `assemble` (including `reobfJar`) passed with the pinned YSM API.
No automated test suite was added or run. Compilation and API/bytecode inspection do not
establish native runtime rendering.
In-game checks remain necessary: dedicated and integrated server, no YSM, client-only YSM,
server custom models, rejoin/restart, model removal/reload, chance zero/fraction/one, notice
preemption, idle/camp/home emotes, equipment and Better Combat/Epic Fight combinations.

- Mod/version: https://modrinth.com/mod/yes-steve-model/version/Zqooxsd2
- Official introduction: https://yesstevemodel.github.io/en/wiki/intro/
- Official configuration: https://yesstevemodel.github.io/en/wiki/config/
- Released JAR SHA-1: `151ac7b24da8beeca1a20864565743cfd77af286`.
- Touhou Little Maid 1.20 rendering integration was an API-design reference only; the
  implementation uses YSM's released symbols, with no dependency on TLM and no copied
  upstream implementation or embedded YSM assets/native binaries.

## Runtime diagnosis and corrections (2026-11-03)

The initial header-hash lookup was incorrect. A read-only snapshot of the running
TestMod1.20.1 server registry established:
- `GI_Paimon.ysm` is the actual registry ID, with texture `default`.
- Its runtime metadata hash is `54d112c4677c28b00b0a0882b0c65ab3`, whereas the legacy
  file's embedded payload MD5 is `72ef2b17cfc10021467d039e1e973226`.
- All 880 encrypted model entries in that 907-entry snapshot retain the `.ysm` suffix;
  the other 27 entries are folder-format built-ins.

Successfully parsing the old header did not prove the registry match. The correction
therefore replaces that mapping entirely with registry filename aliases as described
above. Do not reintroduce a file-header/runtime-hash equality assumption.

The right-hand animation problem had two verified causes:
- A live client snapshot showed NPCs with `swinging=true, swingTime=-1` and their display
  proxies with `swinging=true, swingTime=0`. The `FakePlayer -> PathfinderMob -> Mob`
  inheritance chain never calls `LivingEntity.updateSwingTime()`. `FakePlayer.aiStep`
  now advances it once on both logical sides before `super.aiStep()`, matching vanilla
  `Monster.aiStep` ordering. This lets ordinary swing packets finish and clears the flag.
- The display proxy also omitted `swingTime`. Pinned YSM's
  `OOooo0o00o0Oo0O0ooO0OoO0` hand controller resets when `swinging` is true and
  `swingTime == 0`. `DisplayState.copyFrom` now copies the NPC's timer beside
  `attackAnim`, `oAttackAnim`, `swinging`, and `swingingArm`.
Keep the proxy unticked; YSM's render-time controller updates guard their own timing.
The compiled filename helper was manually checked against the real 907-entry server
snapshot: it built 880 aliases and resolved `GI_Paimon` to the loaded `GI_Paimon.ysm`.

## Random model removal recovery (2026-11-03)

`YsmRandomModel` is a server-only persisted Boolean recording random versus configured
assignment at the original spawn decision. An absent tag means an older save: on its first
eligible server refresh, infer the source from whether the current identity has a `YSM:`
directive, then save it. Historic config changes cannot be reconstructed for those older
saves; after migration, config edits do not change recorded assignment provenance.

The existing staggered 20-tick `refreshAvailability` waits for the loaded server registry.
If a randomly assigned model/texture is no longer usable, `recoverServerModel` first repairs
only the texture when that model remains available; otherwise it selects a random usable
server model using the existing equal-per-model lottery. Stop the old model's emote,
synchronize the replacement ID/texture, and save it through the ordinary entity save path.
There is no new spawn chance roll and no replacement while the saved model is valid.

Config-assigned appearances do not enter the random replacement lottery. If their saved
model becomes unavailable, use Steve/Alex so YSM remains active; retry the configured
preference/default on subsequent refreshes while the custom decision remains selected. Random recovery also falls back to
Steve/Alex if no custom result remains. Zero overrides either origin with Steve/Alex.
Client-only model storage is never consulted. The provenance tag is not synchronized, so
no wire/protocol change is needed.
