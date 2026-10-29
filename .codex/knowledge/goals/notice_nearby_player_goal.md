# Notice Nearby Player Goal

`NoticeNearbyPlayerGoal` is a flagless priority-zero social owner. `SocialNoticeAi` supplies bounded
selection, retained attention and rotation; the entity validates safety before AI and applies final
head/body rotation afterward. It does not preempt a work goal's MOVE/LOOK ownership.

- Every 20-25 ticks, inspect at most 16 nearby player/Player NPC candidates in a six-block range.
  Require visibility, eye line of sight and a forward 120-degree acquisition cone. Spectators,
  invisible entities and targets more than three blocks above/below are ignored.
- Roll once per encounter, not every scan for a stationary neighbor. `NOTICE_CHANCE` currently
  preserves the user's 100% diagnostic override (`1.0F`); the original intended chance is `0.30F`.
  Keep at most 32 recent identities; another roll requires at least five seconds outside the visible front region. A
  completed glance also has a randomized 10-20 second cooldown. NPCs therefore need not respond
  to one another, and cannot maintain an endless mutual stare.
- Track encounter presence separately from whether its chance was attempted. Observing a new
  neighbor during another glance or its cooldown must not consume their opportunity. Keep
  presence updated at the same bounded cadence during active glances. The distance comparison
  uses `NOTICE_RANGE * NOTICE_RANGE`: assigning `4` directly to a squared range had limited
  detection to two blocks despite the six-block candidate scan.
- Look for 60-100 server ticks (three to five seconds), ending immediately for death/removal,
  distance, lost sight, target acquisition, injury, healing, item use, invitation or unsafe movement.
  Attention stays inside a 150-degree cone anchored to the starting body heading. The stationary
  body turns at most 25 degrees, with gradual head/pitch motion; navigation-facing exploration
  keeps its body direction. Rotation accumulates from the notice's own prior output, so a fishing
  goal's normal water-facing look cannot reset the glance every frame.
- Combat and other nonroutine personality/safety owners take precedence. `getTarget()!=null`
  excludes active player hunting and attacks on any entity; possessing HUNT_PLAYERS alone does not
  suppress a peaceful worker's social behavior. Sleeping and pillar cleanup are not interrupted.
- Optional AI-budget waiting strolls also permit noticing. The disposable stroll yields its
  navigation while attention is active and cannot restart until the glance ends; social attention
  itself does not require a routine worker slot. Emergency and combat owners remain excluded.

`StartupWorkGatedGoal` pauses retained routine work during a glance. Only an already-running
`PlayerNpcFishingGoal` or `ExploreAroundGoal` continues ticking. New work does not start until the
glance ends. Paused jobs retain their goal instance, tools, block progress, path and worker ownership;
their action tick counters do not advance. The wrapper bypasses path-completion continuation checks
while paused, but still checks team/startup/worker admission and immediate life/combat/healing safety.
Higher-priority emergency goals can still preempt and run the normal cleanup. Existing absolute
safety deadlines remain bounded; the pause does not extend them indefinitely.

Resume restores the retained path before delegate continuation checks, without creating a new
path. Shared `BreakingBlockAi.pause()` clears cracks, mining sneak and Epic Fight digging while
retaining progress. A synchronized pause flag suppresses residual vanilla attack rendering, and
cached vanilla/Better Combat swing ticks are cleared. Work resumes through its normal validation.

Validation: Java 25 assembly plus source review; in-game third-person animation, multiplayer
visibility/cancellation, and interrupted build/farm/mining resume still require playtesting.
