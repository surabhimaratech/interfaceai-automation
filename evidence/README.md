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

This proves one live model-generated action only. It does not establish a complete
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

All 22 tests passed in a fresh `./gradlew test --rerun-tasks` run. The success log
is discovery evidence only; no reusable artifact or deterministic replay exists.
