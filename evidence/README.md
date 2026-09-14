# One-action live discovery proof

On 2026-09-12, the OpenRouter client requested one UI action from pinned model
`anthropic/claude-sonnet-5` against the running synthetic target at `/legacy`.
Headed Chromium was used. The model returned a FILL tool call; the policy allowed
it, Playwright executed it, and reading the field back verified the value.

The authentic structured event log is
`action-4db866db-f506-4768-a152-d92f5c549666.jsonl`. It records OBSERVING,
DECIDING, ACTING, SUCCEEDED (`VALUE_VERIFIED`), and CLOSED under one run ID.
Values, prompts, API keys, page contents and raw provider responses are omitted.
No provider receipt or raw transcript is retained; these are runtime event logs.

The run used port 18080 to avoid interfering with an existing local target:

```sh
./gradlew --no-daemon bootRun --args='--discovery.proof=true --server.port=18080 --discovery.allowed-origin=http://localhost:18080'
```

`OPENROUTER_API_KEY` was loaded into the process environment from the ignored
local `.env` file without printing it. The temporary server was stopped afterward.

This historical run proves one live model-generated action only. It does not establish a complete
discovery workflow, a capability artifact, or deterministic replay. Offline tests
are separate from this live evidence.

## Bounded-flow attempts (2026-09-12 Pacific / 2026-09-13 UTC)

- `flow-0fb44a86-ca7d-4255-9116-32dc9895a9c1.jsonl`: five live model-selected
  actions succeeded; decision six stopped with MODEL_FAILURE. This run predates
  the finer-grained provider HTTP error codes, so its exact provider status was
  not retained. It is not a completed review flow.
- `flow-14a3b2d3-3e58-4b5b-b9e0-b0d4b3898f4a.jsonl`: retry stopped on its first
  model request with MODEL_HTTP_402 (payment required). No UI action was executed
  in this retry. The local server was stopped afterward.

Both used the pinned model and the live `/legacy` UI. Neither is represented as a
successful full discovery run. They remain unchanged as authentic failure evidence.

## Verified bounded discovery success

After credits were restored, `flow-c4c642f3-a5eb-4a37-9bfb-e56cceec7910.jsonl`
records a new live run on 2026-09-12 Pacific (2026-09-13 UTC), using
`anthropic/claude-sonnet-5`. It ended with SUCCEEDED / CHECKPOINT_VERIFIED after
16 model decisions, within the 20-decision / 120-second bounds. The checkpoint
re-read the visible review table and verified the requested inputs, not-submitted
status and balance arithmetic. No submission occurred.

A temporary local Java harness invoked the existing DiscoveryFlow, retrieved
its typed in-memory result from the Spring context, displayed that synthetic
result to the operator, and closed the application. It did not script UI actions
or replace any model decision. Review values were not persisted in JSONL.
The ordinary `--discovery.flow=true` command runs the same discovery code.

At that stage, all 22 tests passed in a fresh `./gradlew test --rerun-tasks` run.
That success log is discovery evidence only; reusable artifacts and deterministic
replay had not yet been implemented. The Day 2 demonstration below is separate.

## Day 2 live discover → compile → replay success

Run UUID: `dc55aa1d-d34c-4735-bc46-b36bbc64f4b0`.
Discovery occurred on 2026-09-13 Pacific (2026-09-14 UTC).
Paths below are relative to the repository root.

- `evidence/flow-dc55aa1d-d34c-4735-bc46-b36bbc64f4b0.jsonl` records authentic
  OpenRouter discovery using pinned `anthropic/claude-sonnet-5`: nine model
  decisions, eight successful UI actions, and `SUCCEEDED / CHECKPOINT_VERIFIED`.
  The ninth decision is COMPLETE, not a persisted UI action.
- `artifacts/capability-dc55aa1d-d34c-4735-bc46-b36bbc64f4b0.json` is the
  execution-derived eight-step artifact, with provenance source `COMPILED_TRACE`
  and the same trace UUID. Its trusted contracts and vocabulary are predefined;
  the ordered steps derive from successful browser execution, not model-supplied
  locators or postconditions.
- `evidence/replay-dc55aa1d-d34c-4735-bc46-b36bbc64f4b0.txt` records deterministic
  replay of that exact artifact with `OPENROUTER_API_KEY` explicitly removed
  from the replay process environment. It ends
  `SUCCEEDED / CHECKPOINT_VERIFIED / step=8`, with `outputs=REDACTED`.

The artifact stores `${inputs.memberId}`, `${inputs.amount}` and `${inputs.reason}`
expressions rather than invocation values. It contains no runtime control or
observation IDs, browser handles, raw CSS/XPath selectors, model transcript, or
submit action. Its durable locators use semantic roles, accessible names and,
for the ambiguous Open link, the bounded Savings row context. Its execution
boundary is `REVIEW_ONLY`. No reversal was submitted during discovery or replay.

The automation logs omit inputs, extracted outputs, page content, target URLs,
exception details and secrets. The replay file also contains ordinary Gradle
build output, including a public Gradle documentation URL, not a target URL.
Replay output is intentionally redacted: exact typed outputs are verified by
the checkpoint and automated integration tests, not exposed in this log.
Those offline tests remain distinct from this live demonstration.

UUID correlation links the discovery log, artifact provenance and replay filename;
it is evidence linkage, not cryptographic attestation. The redacted replay result
does not itself attest to the process environment or artifact bytes, and no raw
provider transcript or receipt is retained.
