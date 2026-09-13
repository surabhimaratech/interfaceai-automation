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

The target, one-action proof, and bounded discovery loop are implemented.
Minimal same-session human takeover is also implemented. Capability artifacts,
replay and real authentication are not implemented. Session expiry is an explicit
UI simulation. Offline browser/client tests are not live LLM evidence.

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

## Bounded discovery flow

With `OPENROUTER_API_KEY` available in the process environment:

```sh
./gradlew bootRun --args='--discovery.flow=true'
```

The runner starts at member search and requests individual model decisions until
the fee-reversal review checkpoint is verified or a stopping condition occurs.
Defaults are member `100042`, account `SAV-2048`, amount `25.00`, and reason
`Courtesy adjustment`. Override them with `discovery.member-id`,
`discovery.account-id`, `discovery.amount`, and `discovery.reason`.
Use `discovery.max-steps` (default 20, maximum 100) and
`discovery.timeout-seconds` (default 120, maximum 600) to bound a run.
Do not enable `discovery.proof` and `discovery.flow` together.

Each decision counts toward the limit, including WAIT and COMPLETE. The deadline
uses a monotonic clock from runner entry, covers observation/model/action work,
and is rechecked before executing any returned action. HTTP and browser calls
receive the remaining time budget. Browser construction precedes runner entry;
browser cleanup has its own bounded allowance. A late response cannot trigger
another action. WAIT pauses for at most 250 ms and obtains a fresh observation.

Observations contain at most 40 controls, names up to 160 characters, nearby
row/form/fieldset context up to 300 characters, and up to five visible status and
alert messages each (300 characters per message). Known token, email and SSN
patterns and control characters are sanitized. Filled-state flags expose whether
a field is populated, not its value. This is best-effort sanitization for the
synthetic target, not general PII detection. Context is untrusted data in the
model prompt. Context, page text and outputs are never copied into JSONL logs.

COMPLETE causes another fresh UI observation. The target-specific checkpoint
requires the review route/title, the not-submitted status, no alerts, exact
member/account/reason/amount, and consistent current/projected balances. The
runner returns `Result` with typed `ReviewCheckpoint.Details` only on verified
success. `DiscoveryFlow.result()` retains this result in memory; stdout prints
only state/code/step count. This increment does not expose a result API or persist
financial details.

Known not-found and validation messages return BUSINESS_OUTCOME without another
model call. REQUEST_HUMAN or an unknown alert pauses for the local operator when
the flow's handoff coordinator is attached. A runner without that coordinator
returns HUMAN_REQUIRED. Invalid/stale
decisions, step limits, deadline expiry and provider errors terminate explicitly.

Flow evidence uses unique `evidence/flow-<uuid>.jsonl` files with state transitions,
step counts and fixed result codes. Previous evidence is preserved. No reusable
artifact or replay engine is created.

## Repetition guard and human handoff

Each decision receives `PreviousAction`: action enum, fixed result code, and a
state-changed boolean. It contains no values, labels, selectors, URLs or page
text. Fresh observation IDs do not count as progress. The in-memory guard keeps
at most 16 state/action fingerprints and pauses before the third identical
state/action attempt, or after three actions without observed progress. Configure
`discovery.progress-limit` from 2 to 10 (default 3). The guard resets after human
resume, while the overall step/deadline limits remain in force.

For a manual demonstration, load the ignored `.env` into the current shell and run:

```sh
set -a
source .env
set +a
./gradlew bootRun --args='--discovery.flow=true --discovery.simulate-expiry=true --discovery.timeout-seconds=600 --discovery.handoff-timeout-seconds=300'
```

Only source your own trusted `.env` file. Open http://localhost:8080/operator in
your ordinary browser. The existing headed Chromium page shows **Simulated
session expired**. In that Chromium window click **Restore session**, enter the
synthetic member ID `100042`, and click **Search**. In the operator page click
**Resume automation**. The runner takes a fresh observation of Search Results
and continues with the model through verified review. Never submit a reversal.

Simulation adds a dismissible UI overlay; it does not implement real credentials
or server-side session expiration. Normal runs omit `discovery.simulate-expiry`.
Model REQUEST_HUMAN decisions and no-progress conditions use the same handoff.

Ownership is `AUTOMATION -> HUMAN -> RESUMING -> AUTOMATION`. The browser owner
thread drains prior work, invalidates the current observation, then grants human
control. Automated open/observe/execute/wait operations reject HUMAN or RESUMING
ownership. The pause loop only dispatches browser callbacks so manual navigation
works. Resume uses a single-use token; stale or duplicate signals are rejected.
After the owner thread reclaims control, the runner observes afresh and continues
or verifies completion. It never reuses the pre-handoff action.

The operator endpoints accept loopback requests only; state changes require POST
and the current token, with same-origin checks. They remain outside automation's
allowlist. This is a single local operator UI, not production authentication.
Ownership is cooperative: it cannot physically stop a person using the browser
after resuming automation. Navigation policy and the submit block remain active
during human operation too.

Each run allows at most three handoffs. Handoff timeout defaults to 180 seconds;
the overall run deadline also includes human waiting. On timeout or completion the
CLI closes the browser and records CLOSED. No model calls or automated UI actions
occur during HUMAN ownership. Control-transfer events record only reason enum,
step, epoch, ownership and aggregate manual input count; no human field values or
page data are captured. The count is a local audit signal, not identity proof.

`./gradlew test` includes state-machine, repetition, summary, stale-resume and
same-page integration tests. The integration test uses a second CDP client as a
**simulated** operator and pumps browser events like the real runner. It is not
presented as a real human demonstration. CDP is enabled only in that test.
