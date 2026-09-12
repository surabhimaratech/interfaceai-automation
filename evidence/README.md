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
