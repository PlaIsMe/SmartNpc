# YSM idle emote goal

`YsmIdleEmoteGoal` is a flagless, startup/worker-gated priority-8 animation observer.
It never takes navigation, look, jump, equipment, inventory or camp/home plan ownership.
It may run during stationary home LOOK/walk-pause activity, actual night-camp LOOK activity,
stationary exploration/waiting-stroll pauses, or otherwise unoccupied idle time.

Eligibility uses actual unwrapped running goals and explicit owner predicates, not inspector
labels. Home requires arrival at the selected spot; night camp explicitly reports its LOOK
activity, so furnace work, lighting, travel and recovery do not qualify. Any other active
owner prevents the idle emote. The NPC must be on dry ground, motionless, not crouching,
healthy enough not to be healing, and free of combat, injury, item use, sleep, passenger
state, recruitment and escape work. Social notice wins and supplies its own animation.

Activation is staggered at >=20 ticks and uses a persisted 200–600 tick cooldown, including
models with no roulette entries (no busy retry loop). Emotes last 60–160 ticks and stop on
lost eligibility or owner interruption. `PlayerNpcEntity` centrally ticks transient animation
duration and the persistent cooldown. An integer sequence token provides exact cancellation
ownership, protecting newer social animations from a stopped older idle goal.

No model or animation is selected on a client. The server reads only the chosen model's
installed roulette entries. See `../yes_steve_model_compat.md` for dependency, config,
save/reload and missing-model semantics. Validation: compilation/source inspection;
native rendered results require in-game checks.
