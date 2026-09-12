# InterfaceAI Automation

Day 1 target application: Java 21, Spring Boot 4.1.1, Playwright Java 1.62.0.

## Run

```sh
./gradlew bootRun
```

Open http://localhost:8080/legacy. Search for **100042** (Morgan Lee), open savings
account **SAV-2048**, select **Prepare fee reversal**, enter **25.00** and
**Courtesy adjustment**, then select **Review reversal**. Current balance is
$1,842.73; the projected balance is $1,867.73. Stop at review.

The target uses ordinary server-rendered forms, repeated Open links, and a nested
account-summary table with no test IDs. All data is synthetic and immutable.
Search for **999999** to see `MEMBER_NOT_FOUND` (HTTP 200). Invalid review inputs
return `VALIDATION_REJECTED` (HTTP 422) and preserve the form. Amounts must be
0.01–100.00 with at most two decimal places; reason must be nonblank and at most
120 characters. Unknown record URLs return HTTP 404.

| Method | Route |
| --- | --- |
| GET | /legacy |
| POST | /legacy/members/search |
| GET | /legacy/members/{memberId} |
| GET | /legacy/accounts/{accountId} |
| GET | /legacy/accounts/{accountId}/fee-reversal |
| POST | /legacy/accounts/{accountId}/fee-reversal/review |
| POST | /legacy/accounts/{accountId}/fee-reversal/submit |

Submit reversal is present for the later automation-policy demo. The target also
unconditionally rejects submission with `ACTION_BLOCKED` (HTTP 403); it never
changes a balance. This target-side backstop does not replace the future runtime
policy check that must reject the action before execution.

## Test

Install the pinned Chromium browser once, then run all tests:

```sh
./gradlew playwright --args="install chromium"
./gradlew test
```

Tests include live Chromium navigation through the entire target workflow,
not-found and validation outcomes, input escaping, and blocked submission.
Direct HTTP calls in tests check server validation only; the workflow browser
test uses visible UI controls.

## Current scope

The target and a one-action discovery proof are implemented. Capability artifacts,
replay, a full discovery loop, human handoff, authentication and session expiry
are not implemented yet. Offline browser/client tests are not live LLM evidence.

## One live model action

Set `OPENROUTER_API_KEY` in the shell used to start Gradle. Do not put it in source,
command-line arguments or evidence. A `.env` file is not automatically loaded.
The client pins `anthropic/claude-sonnet-5`; there is no fallback model.

```sh
./gradlew bootRun --args='--discovery.proof=true'
```

This starts the target, opens headed Chromium at `/legacy`, sends the current
heading and visible control inventory to OpenRouter, and requests exactly one
tool action toward filling the synthetic member ID. The response must contain
one valid `ui_action` call referencing the current observation and control.
The browser session checks policy, performs the action, and verifies a fill by
reading the field value. It then closes Chromium; stop Spring Boot with Ctrl+C.
This proves one action, not completion of the full banking goal.

Every Playwright operation uses one dedicated thread. The same context/page
persists across observations and actions until explicitly closed. Observation
IDs invalidate old actions; control handles are transient and are not reusable
artifact locators. Only the current main-frame controls are supported.

Configuration in `application.properties` defines `discovery.allowed-origin`,
`discovery.allowed-routes` (comma-separated full-match regexes), and
`discovery.allowed-actions`. Unknown actions and click destinations fail closed.
Submit routes remain prohibited even if a broad route pattern is configured.
The browser also blocks out-of-policy requests, popups and service workers;
unexpected dialogs stop actions. This is scoped to the trusted local synthetic
target, not a security sandbox for arbitrary hostile websites.

The proof writes a unique `evidence/action-<uuid>.jsonl`. Override with
`--discovery.evidence=evidence/my-proof.jsonl`; existing files are never overwritten.
Events include timestamps, run ID, state, fixed action/result codes and the pinned
model. Field values, page content, URLs, prompts, keys, raw responses and exception
bodies are omitted. Visible labels/headings and the goal are sent to the provider
in memory; use synthetic data only. No screenshots or raw transcripts are saved.
`liveModel=true` is emitted only after a valid response from the live client;
`VALUE_VERIFIED` confirms the browser checked the resulting field value.

Provider authentication/availability errors are surfaced as sanitized error codes.
Requests have a 45-second timeout, no redirects and no automatic retries. Normal
`./gradlew test` uses a local HTTP stub for model protocol tests and requires no key.
