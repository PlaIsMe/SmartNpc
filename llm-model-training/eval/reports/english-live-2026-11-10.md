# English model in-game check — 2026-11-10

Measured on the local Windows computer in a real rendered Fabric 1.20.1 client with an integrated server. The existing CurseForge 1.21.1 instance was not launched or edited. All eight English release files were verified against GitHub and the completed local export receipts before testing.

## Memory

Native peak is the extra **llama.cpp resident working set**, excluding Minecraft. Exact bytes are retained in the JSON report. Combined peak is a sampled sum of Minecraft + llama.cpp working sets, not an isolated measurement of the mod. Legacy allocates no model process; the game itself still uses RAM.

| Mode | Native peak (MiB) | Native peak (bytes) | Native private commit peak (MiB) | Minecraft + native sampled peak (MiB) | Ready/startup (s) |
| --- | ---: | ---: | ---: | ---: | ---: |
| legacy | 0.00 | 0 | 0.00 | 3023.73 | — |
| smollm2-360m-en_us | 451.83 | 473780224 | 235.00 | 3338.01 | 2.51 |
| qwen2.5-0.5b-en_us | 559.16 | 586326016 | 211.21 | 3437.82 | 2.36 |
| qwen3-0.6b-en_us | 915.45 | 959918080 | 585.62 | 3813.92 | 2.76 |
| qwen3.5-0.8b-en_us | 1092.88 | 1145970688 | 983.26 | 3961.85 | 3.31 |
| smollm2-360m-en_us-unfiltered | 457.50 | 479723520 | 240.52 | 3361.45 | 1.81 |
| qwen2.5-0.5b-en_us-unfiltered | 546.21 | 572743680 | 212.88 | 3450.92 | 2.31 |
| qwen3-0.6b-en_us-unfiltered | 991.22 | 1039368192 | 662.82 | 3902.67 | 2.61 |
| qwen3.5-0.8b-en_us-unfiltered | 1159.35 | 1215668224 | 1049.28 | 4038.01 | 3.66 |

Settings: llama.cpp b12228 Windows CPU x64, 2,048 context tokens, two CPU threads, zero GPU layers, temperature 0.6, maximum 160 output tokens. Minecraft heap cap 2,048 MiB, render/simulation distance five, frame cap 30. Intel i5-12450HX; about 19.71 GiB visible physical RAM. Windows GetProcessMemoryInfo was sampled every 500 ms. Each model used a new managed native process, including the normal startup compatibility probe.

These are observed short-run peaks, **not universal requirements**. Larger contexts, workloads, runtime builds, GPU settings and OS paging/cache change usage. Other applications were running; free RAM was about 1.2 GiB during the legacy stage. Minecraft reused the same process across all stages, so its heap/working-set drift is not a clean model-only comparison.

## Conversation and runtime results

The test driver submitted synthetic player messages through the normal server-thread AiChatService.chat entry point. It used the normal managed provider, model checksums, schema decoding, action checks, tone pacing and duplicate handling. It did not simulate keyboard typing or click through model picker/download dialogs.

### legacy

Status: `stopped; queued=0/8; busy=false; replies=0; dropped=0; failures=0; meanMs=0; echoes=0; repeats=0; invalid=0; retries=0; suppressed=0; unheard=0; waiting=0`

- unsolicited event / startup → Two heads, hopefully one good plan.

### smollm2-360m-en_us

Status: `ready: smollm2-360m-en_us; queued=0/8; busy=false; replies=4; dropped=0; failures=0; meanMs=3995; echoes=0; repeats=0; invalid=0; retries=0; suppressed=0; unheard=0; waiting=0; player_bread=0`

- unsolicited event / startup → I can spare 8 bread
- Skeppy hey, how are you? → 8 Bread, you said?
- Skeppy what are you doing? → Not rn, sorry
- Skeppy can I have two bread? → Fine, 8 bread please

### qwen2.5-0.5b-en_us

Status: `ready: qwen2.5-0.5b-en_us; queued=0/8; busy=false; replies=3; dropped=0; failures=0; meanMs=3433; echoes=0; repeats=0; invalid=0; retries=0; suppressed=0; unheard=0; waiting=0; player_bread=0`

- Skeppy hey, how are you? → No bro
- Skeppy what are you doing? → Yeah bro :P
- Skeppy can I have two bread? → 2 Bread, here you go

### qwen3-0.6b-en_us

Status: `ready: qwen3-0.6b-en_us; queued=0/8; busy=false; replies=4; dropped=0; failures=0; meanMs=3579; echoes=0; repeats=1; invalid=0; retries=1; suppressed=0; unheard=0; waiting=0; player_bread=0`

- Skeppy hey, how are you? → Alright, moving then
- Skeppy what are you doing? → Nah, not rn
- Skeppy can I have two bread? → Crouch twice, not rn

### qwen3.5-0.8b-en_us

Status: `ready: qwen3.5-0.8b-en_us; queued=0/8; busy=false; replies=4; dropped=0; failures=0; meanMs=3974; echoes=0; repeats=0; invalid=0; retries=1; suppressed=0; unheard=0; waiting=0; player_bread=0`

- Skeppy hey, how are you? → Got 8 bread, what's up?
- Skeppy what are you doing? → Alright, 8 bread
- Skeppy can I have two bread? → Not taking that request

### smollm2-360m-en_us-unfiltered

Status: `ready: smollm2-360m-en_us-unfiltered; queued=0/8; busy=false; replies=5; dropped=0; failures=0; meanMs=4236; echoes=0; repeats=0; invalid=0; retries=1; suppressed=1; unheard=0; waiting=0; player_bread=0`

- Skeppy hey, how are you? → No bro, thank you
- Skeppy what are you doing? → Still building bro
- Skeppy can I have two bread? → Fine, I'll get 2 bread

### qwen2.5-0.5b-en_us-unfiltered

Status: `ready: qwen2.5-0.5b-en_us-unfiltered; queued=0/8; busy=false; replies=5; dropped=0; failures=0; meanMs=4024; echoes=0; repeats=0; invalid=0; retries=1; suppressed=1; unheard=0; waiting=0; player_bread=0`

- unsolicited event / startup → I'd take 2 bread
- Skeppy hey, how are you? → Yeah, gonna pass it over
- Skeppy what are you doing? → Fuck off, I'm busy

### qwen3-0.6b-en_us-unfiltered

Status: `ready: qwen3-0.6b-en_us-unfiltered; queued=0/8; busy=false; replies=5; dropped=0; failures=0; meanMs=3877; echoes=0; repeats=0; invalid=0; retries=1; suppressed=1; unheard=0; waiting=0; player_bread=0`

- unsolicited event / startup → Yo, 2 bread please
- Skeppy hey, how are you? → Yo, how's it going?
- Skeppy can I have two bread? → Alright, don't lose my ass

### qwen3.5-0.8b-en_us-unfiltered

Status: `ready: qwen3.5-0.8b-en_us-unfiltered; queued=0/8; busy=false; replies=5; dropped=0; failures=0; meanMs=3949; echoes=0; repeats=1; invalid=0; retries=1; suppressed=0; unheard=0; waiting=0; player_bread=2`

- unsolicited event / startup → I'm keeping it rn
- Skeppy hey, how are you? → I'll pass bro
- Skeppy what are you doing? → Okay, I'll look
- Skeppy can I have two bread? → Fine, I'm getting 2 bread

## Findings and limits

- All completed stages must be judged on actual replies, not merely the ready status. Zero inference errors does not prove relevance or action correctness.
- Standard SmolLM2 answered a greeting with "8 Bread, you said?"; standard Qwen3 answered a bread request with "Crouch twice, not rn". These are off-topic despite valid generated output.
- Qwen2.5 standard said "2 Bread, here you go", but the recorded player bread count remained zero. This run did not demonstrate a successful gift transaction; text alone must not be reported as a verified transfer.
- The final Qwen3.5 Unfiltered stage increased the player bread count from zero to two. A two-bread transfer was verified in that stage.
- Only short greeting/work/item scenarios were exercised. Combat, damage, lifecycle replies, chest ownership, language quality and long-session memory behavior were not comprehensively tested.

Successful load and generation are confirmed for completed stages, but dialogue relevance and item transfer are not reliable enough to describe this as a full gameplay pass. Model weights and training datasets were not changed during this measurement task.
