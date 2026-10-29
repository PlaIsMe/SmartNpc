# TeamUpGoal

Source: `entity/goal/TeamUpGoal.java`, `util/PlayerNpcTeamUpManager.java`, and
`event/PlayerNpcTeamUpEvent.java` under `src/main/java/com/pla/smart_npc/`.

TEAMUP invitations are gestures. The inviter stops, looks at its exact intended player or
Player NPC, and performs a randomly chosen **5-10 complete crouch/stand cycles over 40-60 server
ticks (2-3 seconds)**. It then stands, keeps looking, and waits another 40-60 ticks for a reply.
The burst may include one harmless main-hand swing (35% chance) and one jump (20% chance,
grounded/dry and within two blocks so the jump cannot cross the invitation boundary). Neither
gesture calls attack, interaction, damage or item-use code. It does not send a chat request.
Candidate acquisition retains its five-second stagger and **four-block** range; no path
or candidate scan is added to the running per-tick gesture loop.

A human accepts by facing the inviter and making **two fresh crouch presses**, releasing between
them, during this short invitation. One edge per server tick is accepted: there is no four-tick
debounce that rejects fast tapping. Holding crouch before the invitation does not count; turning
away resets progress. Early replies during the burst are retained and accepted after the burst
finishes. Facing uses a view-vector dot product of at least 0.5, and the invitation requires line
of sight. Only the exact reserved recipient
can answer; unrelated nearby players or ordinary chat, including `ok`/`okay`, cannot accept.

An eligible NPC recipient looks back and starts its own visible four-tick crouch/stand phases
four ticks into the waiting phase. It completes two full cycles before acceptance; NPC facing
uses the actual head yaw, rather than a stale body rotation. Normal follower exclusion,
TEAMUP eligibility, player-led recruitment,
NPC-led team creation, and leader-team merge/persistence rules remain intact.

Invitations expire after the burst and short reply window (four to six seconds total), then retry
after 30-45 seconds. Moving beyond four blocks, losing visibility, combat/healing, death, unload,
logout, or either participant
being attacked cancels the invitation. The attack event runs on the server and cancels before
shield absorption can hide a hit; cautious NPCs do not need to acquire a retaliation target.
No swing or attack is a valid positive reply.

The manager reserves both participant UUIDs atomically for one exact pair. A second inviter
cannot replace an existing request, and a stale stop cannot clear a newer pair's ownership.
Every cancellation/success clears both reservations, NPC pending flags, and NPC crouches;
human crouch input is never changed. Session shutdown clears the transient registry. Pending
invitations are not saved. Reciprocal animation and multiplayer acceptance need in-game checks.
