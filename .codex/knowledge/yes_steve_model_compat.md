> Historical 1.21.1 reference. This integration remains removed from the 1.21.11 multi-loader branch at the user's explicit request; the behavior below is not enabled here.

# Yes Steve Model compatibility (NeoForge 1.21.1)

## Current 1.21.1 multi-loader behavior

The current modules are `common`, `fabric`, and `neoforge`. Shared selection/filename
matching stays in common; released, typed YSM adapters and display renderers live in
each loader's `ysm.fabric` or `ysm.neoforge` package. The exact pins are Fabric
`eGOUtHWJ` and NeoForge `HZWaR0LY`, both 2.6.5 for Minecraft 1.21.1. Fabric's class/member
names were verified against that released jar, including its animation capability;
NeoForge retains its existing animation attachment. The generally loaded boundary
contains no YSM types. Neither distribution bundles YSM.

With supported, native-available YSM and a loaded server registry, every NPC receives
a usable YSM appearance. `modelChance` now means custom versus base:

- `0.0`: Steve (`misc/2_steve`) or Alex (`misc/1_alex`) according to the regular skin's
  wide/slim shape, including existing NPCs. YSM stays enabled.
- `1.0`: another usable server-installed or bundled model. The two base IDs are excluded;
  YSM's separate bundled `default` character remains eligible.
- Intermediate values: save one choice per NPC, honoring a named `YSM:<filename>` on
  the custom branch. A temporarily unavailable custom choice falls back to Steve/Alex
  and retries when models become available. A base choice is not repeatedly rerolled.

The server-only nullable `YsmCustomModel` Boolean records this decision. Existing model,
texture and assignment-provenance tags retain their format. Older empty choices now
receive a model. Zero replaces a custom appearance with the matching base; one upgrades
a base to custom. Former custom IDs are not retained across a move to zero. Changing
between intermediate chances does not reroll an existing decision. No wire-format change
is required.

Skin shape comes from Authlib 6 `getTextures(profile).skin()` metadata with the same
18-entry UUID fallback as vanilla 1.21.1. It is revisited after asynchronous profile
resolution. Both display players copy the NPC's PlayerSkin and expose its resolved
GameProfile for YSM's own skin resolver. Missing/unsupported/native-unavailable YSM still
uses the regular renderer. The historical zero-disables and empty-choice-is-final
statements below describe the former behavior and are superseded by this section.

For current build/runtime checks see `multiloader_1_21_1.md`. Older single-module ModDev
commands and old adapter locations below are historical; the active build uses Loom.


This branch targets Minecraft 1.21.1 / NeoForge 21.1.250 / Java 21, as verified in
`gradle.properties` and `build.gradle`. The adapter targets released **YSM
2.6.5-neoforge+mc1.21.1**, Modrinth version `HZWaR0LY`. Its obfuscated symbols differ
from the Forge 1.20.1 build; exact mappings remain isolated in `compat/ysm` and
`client/compat/ysm` and were inspected in the matching released JAR.

`build.gradle` uses Modrinth Maven and plain `compileOnly` under ModDev, so YSM is neither bundled
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

This is a COMMON config, following the project's existing config placement, but only the
logical server consults its value. Valid range is 0.0–1.0; default 1.0 selects every eligible
new spawn. Zero disables appearance and emotes for existing NPCs too, retaining their saved
choice for re-enabling. Other probability changes affect only new NPCs.

- `YesSteveModelCompat.initializeSpawn` executes on the server thread during completed
  spawn initialization or the first staggered server tick. A worker-created NPC does not
  access YSM's registry on its chunk-generation thread.
- YSM loads its server registry asynchronously. A HIGHEST-priority ServerAboutToStart
  observer captures the old registry map before YSM's NORMAL-priority reload begins.
  Selection waits for the pinned native callback to publish a replacement map on the
  server thread, even if that replacement is empty. This also prevents an integrated
  world from using the previous world's registry. Pending selection is saved and polled
  at most every 20 ticks. No chance roll occurs before publication.
- Only `O0Ooo000O0ooO00Oooo00oOO`'s server model map is enumerated. Each usable model gets
  equal weight; its first server-listed texture is selected. Client model folders,
  client preferences and player-selected skins never participate.
- `YsmChoiceMade`, `YsmModel`, and `YsmTexture` persist on the NPC. Negative chance results,
  no usable models, and spawning without YSM are final too. Loading old NPCs without these
  tags does not reroll their appearance; unfinished spawn initialization remains eligible.
  Vanilla `/summon` also invokes NBT loading: fresh command NBT without a saved UUID remains
  eligible, while a legacy saved UUID retains its existing identity. An explicit
  `YsmChoiceMade` tag always takes precedence. This covers both ordinary summons and custom
  command NBT that bypasses `finalizeSpawn`.
- Randomly assigned appearances keep their saved model while it is usable. After model
  removal and YSM registry publication, choose and save another usable server model without
  repeating the spawn chance roll. A removed texture uses the same model's declared default
  or first texture instead. Config-assigned appearances retain their saved identity.
  Death followed by a genuinely new world spawn gets a fresh decision.
- Synched entity data carries the selected model/texture, server-approved active flag,
  emote and sequence. Clients never select a model or enable the bridge independently.
  The network protocol is bumped from 7 to 8 because entity data layout changed.

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
existing random-model selection. Disabled/absent YSM and failed chance rolls still win.

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
player list, scoreboard or server. The display owns a YSM animation attachment (`OoooO0OO0000O00oo0Oo00OO`'s static
`AttachmentType`) set through NeoForge `setData`, matching YSM's player lookup.
Its skins/capes use the 1.21 `PlayerSkin` API. Equipment copying is limited to the six
humanoid slots; the 1.21 `BODY` slot is animal armor.

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

Java 21 `compileJava` and `assemble` passed with the pinned YSM API.
No automated test suite was added or run. Compilation and API/bytecode inspection do not
establish native runtime rendering.
In-game checks remain necessary: dedicated and integrated server, no YSM, client-only YSM,
server custom models, rejoin/restart, model removal/reload, chance zero/fraction/one, notice
preemption, idle/camp/home emotes, equipment and Better Combat/Epic Fight combinations.

- Mod/version: https://modrinth.com/mod/yes-steve-model/version/HZWaR0LY
- Official introduction: https://yesstevemodel.github.io/en/wiki/intro/
- Official configuration: https://yesstevemodel.github.io/en/wiki/config/
- Released JAR SHA-1: `18bec74a5b7f3778ce162ddbbbd5555aff0eda2e`.
- Touhou Little Maid 1.20 rendering integration was an API-design reference only; the
  implementation uses YSM's released symbols, with no dependency on TLM and no copied
  upstream implementation or embedded YSM assets/native binaries.

## Forge 1.20.1 runtime diagnosis carried forward (2026-11-03)

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

Config-assigned models (including their saved default fallback), NPCs without any saved
YSM model, absent/unsupported YSM, and chance zero do not enter random recovery. If no usable
server models exist, retain the last saved choice and show the normal skin until a later
refresh can recover. Client-only model storage is never consulted. The new provenance tag
is not synchronized to clients, so no wire/protocol change is needed.

## 1.21.1 cherry-pick port (2026-11-03)

Ported commit `355199eb5acfb123ac26d382f422c76bbb36247f` with the NeoForge branch's
bootstrap, payload registration, inventory registry lookup and SynchedEntityData.Builder.
Config registration uses ModContainer and ModConfigSpec. YSM remains optional through the
metadata template's `type="optional"`, and no Forge Gradle/deobf/mixin plugin configuration
was introduced. Both server and client typed adapters use the matching NeoForge YSM API;
server default-model resolution and async registry publication match the inspected release.

The historical live snapshots above were obtained on Forge 1.20.1. They are diagnostic
history rather than in-game validation of the NeoForge renderer. The port retains random
model replacement, assignment provenance, persistent choices, idle/notice emotes and both
swing-timer fixes. Release version and cherry-pick message are `2.0.3`.
