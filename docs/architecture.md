# Architecture

Ensemble is about 2,500 lines of Kotlin in one module (plus ~650 lines of tests). This document covers the parts you might want to change or rely on.

```
Ensemble (facade, builder)
├── Conductor        minutes-scale jobs          ┐
├── Company          hours-scale projects        ┘ both talk to the team only through CrewDesk
├── CrewDesk         calls, lanes, failover, pause/abort, board absorption, failure reasons
├── Dispatch         the market that assigns work
├── Board            shared notes between teammates
├── Scoreboard       live success rate / latency / benching
├── StageHub         observable state for UIs (StateFlow)
├── tools/           Toolbox, CommandExec (validation → policy → approval → run)
├── aegis/           canonical JSON, policy, one-time approval tokens
├── ports/           HTTP ports (OpenAI-compatible, Anthropic), fn()
└── sim/             protocol-speaking simulated members
```

## The team

A `Crew` has five methods: list members, give a port for a lane, report an outcome, say whether a member is available, rate a member for a skill. `Ensemble` implements it with a `Scoreboard`. Tests implement it directly.

`CrewMember(key, name, vendor, lanes)`: `lanes` is how many requests the member takes at once. The desk keeps one semaphore per member and rotates over its ports round-robin.

## Dispatch

```
bid(member, task) = rate/10                 ability for the task's skill, 0..10, after live performance
                  + 0.12  if the planner named this member
                  − 0.22 × load / lanes     tasks already held in this assignment pass
                  − 0.40  if member is the center and the team has ≥ 3 members
```

With three or more members, the center is removed from the pool entirely. The penalty term still matters for failover, where the center is the last resort. The planner's naming is a hint worth about one skill point, never an order. The constants live in `Dispatch` and are deliberately few.

Skill guessing (`Skill.of`) is keyword-based in English and Korean. It decides which column of the vendor profile counts. A wrong guess costs a little ability, never correctness.

## Board protocol

Any reply may end with:

```
[board]
fact: …
decision: …
warning: …
question: …
```

`Board.split` takes the *last* such block. It accepts `[board]`, `board:` or `[칠판]` as the header, and English or Korean labels for the lines. Up to five notes per reply, duplicates are dropped, and the board holds 40 notes. The digest sorts warnings first, then decisions, facts and questions, and stays within a character budget. Every teammate who starts later receives it. Rival cross-checks write warnings directly.

## Prompt protocol

Every prompt starts with a tag: `[ensemble:plan]`, `task`, `check`, `gap`, `compose`, `review`, `revise`, `research`, `charter`, `ticket`, `rework`, `grade`, `standup`, `qa`, `report`, `reformat`. `Prompts.kindOf(prompt)` recovers the tag. Simulators and test doubles answer by role, and logs are easy to grep.

Structured replies are JSON objects. `JsonPick.obj` takes the first balanced object from a chatty reply: it ignores code fences and prose and handles braces inside strings, all without regex. If a plan or charter can't be read, the center is asked once to reformat. After that a playbook for the kind of goal (code, research or writing) takes over, so a run never dies on format.

## Failure handling

| Signal | Detected by | Reaction |
|---|---|---|
| empty reply, reply shorter than the minimum, refusal | `CrewDesk.ask` | same prompt to the next best idle member (up to three) |
| usage-limit notice (`Vendor.limitHit`) or `HTTP429` | port `via` + text | same, and the member is benched for 15 min |
| `HTTP401/403`, login or challenge pages | port `via` | same, benched |
| center failure | `askCenter` | next best planner takes the baton (`Move.CENTER` event) |
| tool failed or returned nothing | `Conductor.toolTask` | a research-strong member does it directly |
| reply repeats the prompt (a client read the user's own bubble) | `Gate.echo` in `CrewDesk.ask` and before saving | same prompt to someone else; the member is noted for `echoed the prompt` |
| reply stops mid-way (open quote, half a table row, unclosed code) | `Gate.truncated` | the author is asked to continue from where it stopped, up to twice; still cut → rejected |
| unreadable grade | `Company.grade` | a second rival grades; if neither is readable the file is kept as **ungraded**, never scored |
| unreadable or empty stand-up while work remains | `Company.standup` | tickets are planned from the file status: missing → ungraded → weakest files |

Failure reasons are recorded per member: `usage limit`, `signed out`, `blocked`, `server error`, `network error`, `empty reply`, `weak reply`. They appear in the project work log, so "did it refuse or could it not?" has an answer.

`Outcome.partial` is true when some work never came back and the deliverable was assembled from what did.

## The save gate

Every file passes `Gate.check` before it is graded or written: no echo of the instruction, no truncation, no leftover board block, and real substance. Files already in the workspace that fail the gate are treated as missing, so a bad version is never fed back to the next author as "the current version". The gate came from a real run in which four files that held only their own tickets each scored 8.0 because the reviewer returned nothing and an unreadable grade counted as the bar.

## Pause and abort

`JobControl.pause()` makes `pauseGate()`, which runs before every request, suspend until resume. Abort throws `CrewDesk.Aborted`, a plain `Exception` rather than a cancellation, from a watcher inside `guarded {}`. That cancels every sibling coroutine and its in-flight HTTP request, and the engine returns `ABORTED`.

## Company loop

```
kickoff ─► sprint 1 ─► retro ─┬─► stand-up ─► sprint n ─► retro ─┐
                              └──────────────── while time > reserve ┘
        ─► inspect (web apps) ─► final QA ─► (one polish sprint if < 8.0) ─► report ─► deliver
```

- **Time.** `reserve = max(6 min, 15% of budget)` is kept for QA and the report. The first sprint always runs. The loop ends early only when every planned file exists, quality is ≥ 9.0 and the PM says done.
- **Tickets.** One ticket is one file. Two tickets on the same file in one sprint are chained. A ticket may be attempted three times (once when time is short). The best-graded version is kept.
- **Reviewers.** A reviewer is always from a different vendor than the author, preferably not the PM, and the least busy among the best critics. Final QA uses two reviewers from two different vendors.
- **Checkpoint.** After every ticket, `snapshot()` is written through `save` (under a lock, so tickets finishing together never write a stale state). On restore, tickets that were half done restart.

Checkpoint (`.company.json`, v1):

```json
{ "v": 1, "minutes": 90, "sprint": 3, "elapsed": 2712000, "qa": 0, "center": "Claude",
  "charter": { "title": "…", "summary": "…", "audience": "…", "rubric": [{"name":"…","desc":"…"}],
               "files": [{"path":"01_research.md","purpose":"…"}], "milestones": ["…"] },
  "tickets": [{ "id":"T1", "file":"01_research.md", "state":"DONE", "who":"Gemini", "reviewer":"ChatGPT",
                "score": 8.6, "attempts": 1, "issues": [], "after": [], "inputs": [], "sprint": 1, "title":"…", "brief":"…" }],
  "decisions": ["S2 · …"], "history": [[1, 7.9], [2, 8.5]], "board": [{"who":"…","kind":"FACT","text":"…"}] }
```

## Approval gate (AEGIS)

1. `CommandExec` checks that the tool exists and its required arguments are present.
2. `Policy.decide`: payments are denied. A new recipient, with or without a link, an irreversible action, or any outside effect asks the human. Read and local actions run.
3. The approval request carries a summary built from a template, never from model text, and the digest of the canonical action.
4. An approval issues `Token(jti, digest, exp, HMAC)`. At execution time the call is normalized **again** and verified: the signature, the 10-minute expiry, replay, and a byte-for-byte digest match.

## Observability

- `AgentEvent`: `Started`, `Collab(move, who, text)`, `Note`, `Progress`, `ToolStart/End`, `NeedApproval`, `Approval`, `Done`. Each has a `line()` for logs.
- `StageHub.stages`: an immutable `Stage` per job: center, phase, cards (owner, vendor, status, what they are doing, timings, dependencies, peer check), board tail, and for projects the sprint, deadline, quality and files.
- `TokenLedger`: estimated tokens per member (about 4 characters per token for Latin text and about 1.6 for Hangul/CJK).
