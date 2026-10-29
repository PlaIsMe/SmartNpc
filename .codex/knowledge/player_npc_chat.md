# Player NPC Chat

## Source

- `src/main/java/com/pla/smart_npc/util/ChatUtil.java`
- `src/main/java/com/pla/smart_npc/util/PlayerNpcChatTemplateLoader.java`
- `src/main/java/com/pla/smart_npc/entity/PlayerNpcEntity.java`
- `src/main/resources/data/smart_npc/chat/en_us/*.json`
- `src/main/resources/assets/smart_npc/lang/en_us.json`
- `src/main/resources/assets/smart_npc/lang/zh_cn.json`

## Datapack Event Chat

Event lines are server data resources under:

```text
data/smart_npc/chat/<locale>/<event>.json
```

Supported event files are `call_help`, `teamup_request`, `warn_death`,
`missing_home_chest`, `broken_bed`, `killer_taunt`, `death_reaction`, `burn_item`,
and `throw_trash`. A datapack replaces the built-in file by overriding the same
`smart_npc:chat/<locale>/<event>.json` resource ID.

Each file is a JSON array of strings. Entries use one of these forms:

```json
[
  "message",
  "message|speakerSelector",
  "message|speakerSelector|targetSelector"
]
```

An omitted or empty selector is `DEFAULT`. More than three pipe-separated fields,
a blank message, or a non-string entry is malformed and is skipped with a warning.

`speakerSelector` accepts `DEFAULT`, `all`, or an NPC name. Name comparisons are
case-insensitive and include scoreboard name, ordinary/display name, and the configured
NPC skin/display names.

`targetSelector` accepts `DEFAULT`, `all`, an entity name, or a namespaced entity type
such as `annoyingvillagers:herobrine_clone`. Names and entity IDs are
case-insensitive. A named/type selector does not match an event with no target.

The message retains Minecraft's existing `%s`, positional `%1$s`, and `%%` semantics.
Component arguments such as entity and item display names remain Components instead of
being flattened into plain strings.

`npcChatLocale` in `smart_npc-server.toml` selects the locale folder. Locale names are
normalized to lowercase and hyphens become underscores. If the selected locale or one
of its event files is absent, that event falls back to `en_us`. If the localized event
file exists but none of its selectors match, the event stays silent and does not fall
back to an English line.

`turnOnNpcChat` remains the global chat switch. `showNpcChatPrefix` defaults to true and
adds a muted gray italic `[NPC] ` marker before the legacy `<name> message` form. Its
style is confined to that marker, so the NPC name and message keep their existing styles.
Disabling it retains `<name>` so the speaker remains identifiable.

The same optional marker prefixes the localized Player NPC joined-game and left-game system
messages, producing `[NPC] Ray has joined the game` and `[NPC] Ray has left the game` with the
lifecycle message retaining its existing yellow style. The prefix and lifecycle body are sibling
components, so gray italics never bleed into the NPC name or join/leave text.

Join, leave, death-summary, and `teamup_accept` strings remain ordinary client language
keys because they are outside the requested selector-aware event set.

TEAMUP now uses visible reciprocal crouching, not invitation/acceptance chat. The legacy
`teamup_request` template, `teamup_accept` keys and ChatUtil methods remain compatible data/API
but have no active invitation callers. `ok`/`okay` chat no longer accepts a request. See
`goals/team_up_goal.md` for gesture recognition and cancellation.
Invitations require at most four blocks: a 2-3 second burst of 5-10 crouches, then a 2-3 second
standing reply window. Face the inviter and tap crouch twice (release between presses); quick
replies made during the burst are retained. This creates ordinary player-led membership, so the
existing follow goal follows that player once farther than five blocks, stopping within 2.5 blocks.

## Ownership And Target Scope

`PlayerNpcEntity` owns death and kill chat through lifecycle callbacks:

- `awardKillScore` schedules `killer_taunt` for player-like victims, or for a non-player
  victim when that event has an explicit matching entity-type selector.
- `die` uses `warn_death` plus the delayed `death_reaction` for player-like killers.
  A non-player killer only opts into those custom lines when the corresponding event
  has an explicit matching target selector; otherwise vanilla death summary chat is kept.

`ChestAi` emits `missing_home_chest` when a loaded, explicitly tracked chest position no
longer contains a chest. It clears the retained position before emitting, so repeated chest
polls cannot repeat the same notification. An unloaded chest chunk is not treated as loss.

Do not re-add `PlayerNpcDeadEvent` for this behavior, or Player NPC versus Player NPC
deaths can double-fire. Keep chat strings in datapack resources and route event output
through `ChatUtil` so locale, selectors, formatting, prefix, and global enablement remain
consistent.
