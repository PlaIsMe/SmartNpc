# RespondToNpcAlertGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/RespondToNpcAlertGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets nearby Player NPCs react to help/death alerts from other Player NPCs.

## Activation

- NPC must be alive, able to move, not healing, and have no target. A usable alert response
  may interrupt routine work; merely finding an alert is not sufficient for avoidance.
- Requires a nearby active alert from `PlayerNpcAlertManager`.

## Behavior

The responder compares its own health and gear score with the alerted threat. Stronger responders target the threat and assist. Weaker responders avoid the threat by pathing away. Cautious NPCs always choose avoidance because their entity-level target setter rejects combat targets.

Avoid movement uses speed `1.0D`. Keep flee/avoid speeds at `1.0D`; do not raise them to `1.25D+`.

Alert lookup uses a randomized 20-30 server-tick activation cadence. Avoidance must acquire the
shared optional navigation permit, select a loaded local stand, and prove a useful path in
`canUse()` **before** it can preempt work. Selection checks at most twelve stands (three short
away directions with four height offsets) and creates at most one path, with the existing
`0.03F` node budget (`0.01F` while overloaded). Path creation uses the shared loaded-corridor
guard. An acceptable path must reach its selected destination, end at least two horizontal
blocks from the NPC, and increase horizontal distance from the threat by at least one block.
A nonempty partial path, particularly a one-node path ending at the current feet, is not proof.

`start()` revalidates and reuses that selected path rather than recreating it. The running goal
only updates look direction and stops when the path finishes, becomes stuck, or reaches its
80-server-tick deadline. It never repeatedly replaces a healthy route. A completed response
backs off for 40-60 ticks; failed or motionless avoidance backs off for 100-120 ticks for that
threat. The cooldown survives `stop()`, so a failed alert cannot immediately interrupt the same
mining/clearing action again. Other threats retain independent admission, and immediate danger,
healing and direct retaliation goals remain outside this alert retry history.

The randomized assist/avoid decision is retained for 200 server ticks per threat rather than
rerolled at every failed activation. Four entries maximum are kept per NPC, with bounded expiry;
route backoff cannot expire before its deadline. Immediate low-health/cautious constraints can
still force avoidance. A rejected combat target assignment receives the failed-response delay.
The high-danger compatibility roll is cached once per decision, but compared against current
danger classification and attack probability at each activation, preserving changed safety
settings without repeatedly rolling a denied threat into an attack.
These entries are transient goal history, not saved cooldown fields or global state.

The 2026-10-29 MrBeast trace showed priority-one alert episodes interrupting burn-path clearing
with `canReach=false` routes ending zero to two blocks away, including single-node completed
paths. Those paths now fail preflight without taking movement from the work goal. Fresh runtime
traces are still required to verify movement and integration under multiplayer load.

Death/griefing alerts are raised only for player-like killers: vanilla `Player` and `PlayerNpcEntity`. Zombies, creepers, and other monsters should not trigger the "everyone be careful, <name> is griefing" style alert.
