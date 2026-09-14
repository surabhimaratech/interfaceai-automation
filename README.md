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
Minimal same-session human takeover is also implemented. Day 2 includes the
versioned artifact model and isolated compiler seam (gate 1), plus deterministic
artifact replay (gate 2). Gate 3 connects successful browser execution to compilation
behind an opt-in flag; only scripted offline integration has been tested so far.
Real authentication is not implemented; session expiry is an explicit UI
simulation. Offline browser/client/replay tests are not live LLM evidence.

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

## Day 2 gate 1: capability artifact schema

The `artifact` Java package defines immutable records and the supported JSON
boundary, `ArtifactJson.read/write`. `schemaVersion: 1` selects the wire format;
positive `artifactVersion` independently versions a capability definition. The
hand-authored example is
`src/test/resources/artifacts/prepare-fee-reversal-review.v1.json`. It is a schema
fixture, **not authentic discovery evidence**; nothing was added to `evidence/`.

- Capability metadata includes a required display name (max 160 characters),
  description (max 1000), and structured `executionBoundary: {"mode":"REVIEW_ONLY"}`.
  Every input/output requires a description (max 500). Missing, blank, oversized
  or control-character-bearing metadata is rejected.
- `TargetSpec` contains only a logical `targetId` (max 80, letters/digits/underscore/
  hyphen, starting with a letter) and bounded entry path. Concrete origins and
  URL overrides are not accepted fields. The eventual executor must receive or
  resolve the origin through trusted runtime policy; the artifact cannot supply it.
  Named inputs are required, with no defaults.
  `STRING` contracts require min/max length (max 1000); `DECIMAL` contracts require
  inclusive min/max and maxScale (0–8). Inapplicable constraints are rejected.
- `StepSpec` supports only `FILL` and `CLICK`, with a required postcondition.
  Fill values must be whole expressions matching
  `\$\{inputs\.([A-Za-z][A-Za-z0-9_]*)\}`: for example `${inputs.amount}`.
  No literals, interpolation, evaluation, nested paths or implicit input creation.
- `LocatorSpec` has a semantic role, accessible name, name match mode and mandatory
  `EXACT_ONE` cardinality. Action names match exactly. Optional context selects the
  nearest row/form/fieldset whose text contains the supplied literal. Name/context
  can instead be a whole STRING input expression. Names are bounded to 160
  characters and contexts to 300, including the declared bound after substitution;
  control characters are rejected. No CSS/XPath, control IDs or browser handles.
- `PREFIX` name matching is limited to rows, for labels followed by dynamic values.
  The review checkpoint requires a heading marker and one extractor per output.
  `ROW_VALUE` means the single data cell of the unique matched row (multiple cells
  must fail); `TEXT` reads semantic target text. `TEXT` and `USD_DECIMAL` formats
  declare string or dollar-decimal output respectively. Optional expected-input
  expressions require the same contract type. These are declarations, not an
  implemented extractor or replay engine. Outcomes must be non-empty, have unique
  codes, and declare visible named status/alert conditions. The fixture declares
  `MEMBER_NOT_FOUND` (status named “Member not found”) and `VALIDATION_REJECTED`
  (alert named “VALIDATION_REJECTED”). The target supplies accessible names via
  `aria-labelledby`. Browser tests verify each branch is mutually distinct and
  absent at review success; neither error branch has the review checkpoint.
- `ArtifactValidator` fails on the first error with a structured `ValidationCode`
  and a schema path only. Unsupported schema versions, duplicate step IDs,
  unresolved references, invalid expressions/constraints/action structures, missing
  checkpoint/extractors and unbounded text all fail closed. JSON also rejects
  unknown fields/enums, duplicate keys, coercion, trailing content and oversized
  documents. Error messages never echo rejected values or parser excerpts.

Artifact validation is application-neutral: it checks supported action structure,
semantic locators, exact-one cardinality, references, postconditions and the
`REVIEW_ONLY` declaration, not a list of application labels. An unfamiliar benign
label such as “Preview request” can be structurally valid. Structural validity
does not authorize even a known label, and the boundary is intent, not proof of
an element's effects. Runtime `ActionPolicy` remains authoritative for origin,
routes, action types, actual destinations and control names.

Runtime control permissions are constructor-supplied per action type and loaded
for both discovery modes from `discovery.allowed-fill-controls` and
`discovery.allowed-click-controls` in `application.properties` (comma-separated,
exact names). Missing control configuration denies all controls of that type.
These application defaults exist only in runtime configuration, not in the
artifact validator. Even configured names cannot authorize a submit destination:
the URL policy rejects submit path segments, encoded paths and origin/route
escapes. The existing target's review POST remains permitted; its final submit
POST remains blocked. Gate 1 supplies declarations only; gate 2 below implements
runtime target resolution, policy-checked execution and verification. Neither
gate grants permissions based on artifact labels or integrates live compilation.

`DiscoveryTrace.recordSuccessful` accepts only executed FILL/CLICK actions with
matching successful result codes and explicit semantic descriptors supplied by
the caller. Runtime IDs/observations are not retained. Fill values stay in memory;
the trace is not a persistence DTO and has redacted `toString` output.
`ArtifactCompiler.compile(draft, trace, parameters)` consumes a draft with empty
steps, propagates metadata, logical target, boundary and outcomes, produces ordered
steps, and validates the complete artifact. This lower-level seam accepts explicit
descriptors but does not by itself authenticate them. Gate 3 below privately owns
the trace and derives descriptors at the browser execution boundary. Provenance
stores only a source enum and the shared run/trace UUID, not transcripts.

Parameter binding uses exact whole-string equality. The sole canonicalization is
plain-decimal numeric equality (`25.00` can bind a BigDecimal `25`); exponent,
currency, grouping and whitespace forms are not normalized. Unknown literal fill
values and ambiguous matches are rejected. The compiler rejects durable text
(including new descriptions) containing supplied parameter/fill values rather
than doing substring replacement.
This deliberately conservative check may reject coincidental matches. Contract
bounds are author-supplied schema metadata, never inferred from discovery values.

Run the focused offline gate or the complete suite (no OpenRouter calls):

```sh
./gradlew test --tests '*artifact.ArtifactTest'
./gradlew test --rerun-tasks
git diff --check
```

## Day 2 gate 2: deterministic artifact replay

`ReplayEngine.run(artifactJson, InvocationParameters)` validates the artifact,
requires exactly the declared invocation keys, and accepts only Java `String`
and `BigDecimal` values satisfying their contracts. It resolves `targetId` via a
trusted `TargetRegistry` mapping to an `ActionPolicy`. Invalid artifacts/parameters,
unknown targets and denied entry paths return before browser launch. Concrete
origins never come from the artifact.

Each invocation owns one Chromium session on one thread. Discovery and replay
share `BrowserPolicyGuard` for request interception and last-moment authorization
through the existing `ActionPolicy`. Every action checks the actual current URL,
control name and link/form destination, then operates on that same element handle.
Service workers are blocked; unexpected dialogs/popups fail closed. Denied
script-initiated requests also stop replay. The existing stylesheet exception is
shared, not a separate replay policy.

Role/name locators use exact or literal-prefix accessible-name matching and
visible exact-one cardinality. Row/form/fieldset context applies to the nearest
matching ancestor's whitespace-normalized text (bounded to 2000 characters).
Whole `${inputs.name}` expressions are resolved without interpolation, evaluation
or fuzzy matching. Declared outcomes are checked before every step and immediately
after each action, before success postconditions. An outcome also takes precedence
when a step lookup or success check fails. Multiple simultaneously matching outcome
conditions fail as ambiguous, rather than choosing an arbitrary branch.

All step postconditions must pass. Completion checks the heading checkpoint,
extracts every output, validates its type/constraints and any expected-input
binding, then rechecks outcomes/checkpoint. `ROW_VALUE` requires exactly one
direct visible data cell. `TEXT` preserves rendered text, without trimming.
`USD_DECIMAL` accepts only an ASCII dollar sign, optional minus, ungrouped integer
and exactly two fractional digits (for example `$1842.73`), producing `BigDecimal`.
Grouping, exponent notation, surrounding whitespace and locale coercion are rejected.

The typed `ReplayResult` returns:

- `SUCCEEDED / CHECKPOINT_VERIFIED`, with the declared typed output map.
- `EXPECTED_OUTCOME`, with the artifact's `outcomeCode`; the built-in code is
  `BUSINESS_OUTCOME`, and `effectiveCode()` returns the declared business code.
- `BLOCKED / POLICY_DENIED`.
- `FAILED` with `INVALID_ARTIFACT`, `INVALID_PARAMETERS`, `UNKNOWN_TARGET`,
  `ZERO_LOCATOR`, `AMBIGUOUS_LOCATOR`, `POSTCONDITION_FAILED`,
  `CHECKPOINT_FAILED`, `EXTRACTION_FAILED`, `TIMEOUT` or `BROWSER_FAILURE`.

Errors carry only fixed codes and a numeric step; no exception details, input
values, page text, URLs or partial outputs. Successful outputs are sensitive
in-memory return data. Result/parameter `toString()` methods redact values, and
the CLI prints the redacted result with its effective code, including validated
artifact identifiers such as `MEMBER_NOT_FOUND` or `VALIDATION_REJECTED`, never
page text or output values.
No evidence files, model requests or discovery compilation occur.

Timeout defaults: 5 seconds per step and 60 seconds overall; configurable bounds
are 1 ms–30 seconds per step and 1 ms–5 minutes overall. Navigation and final
checkpoint/extraction receive their own per-step budget. The overall caller
deadline also bounds launch and cleanup. Cancellation prevents later steps and
cleanup runs on the owner thread; an already-dispatched request cannot be undone.
No click/fill is retried by the engine. Playwright may wait for actionability
within the remaining budget before dispatching a single action. Missing locator
and postcondition snapshots fail immediately; this gate does not add SPA polling,
recovery, human handoff during replay, or rollback.

### Exact local fixture command (not live evidence)

Start the synthetic server in one terminal:

```sh
cd /Users/surabhimarathe/interfaceai-automation
./gradlew bootRun --args='--discovery.flow=false --discovery.proof=false'
```

In a second terminal, invoke the hand-authored fixture without an API key:

```sh
cd /Users/surabhimarathe/interfaceai-automation
REPLAY_ORIGIN=http://localhost:8080 \
REPLAY_MEMBER_ID=100042 \
REPLAY_AMOUNT=25.00 \
REPLAY_REASON='Courtesy adjustment' \
./gradlew replay --args='src/test/resources/artifacts/prepare-fee-reversal-review.v1.json'
```

This opens headed Chromium, stops at verified review, closes the session and prints
a redacted result. The CLI is a fee-review invocation adapter; Java callers can
supply other typed contracts and trusted target registries. `replay.target-id`
and the existing `discovery.allowed-*` properties configure the trusted target.
`REPLAY_ORIGIN` is a host-side override, not artifact data. Optional environment
settings are `REPLAY_HEADLESS=true`, `REPLAY_STEP_TIMEOUT_MS`, and
`REPLAY_TIMEOUT_MS`. Do not source an API-key file for replay.

Run offline integration and full regression tests:

```sh
./gradlew test --tests '*replay.*'
./gradlew test --rerun-tasks
git diff --check
```

These use local synthetic UI and the hand-authored fixture only. They are
**non-live test execution, not authentic discovery or replay evidence**. The tests
include a successful run with model classes unavailable, both business branches,
ambiguous/missing controls, postcondition/extraction/checkpoint failures, configured
submit controls, intercepted script requests, deadline bounds, and pre-launch
rejection. Existing committed discovery evidence is not rewritten or extended.

## Day 2 gate 3: execution-derived compilation

Compilation is optional and does not change the discovery policy or decision
interface. Host code calls `BrowserSession.enableCompilation(definition,
parameters, runId, outputDirectory)` **before opening the target**. The runner
binds its run ID to that session, then notifies it after the terminal result.
The model still supplies only ordinary `UiAction` decisions. It never supplies
trusted artifact locators, postconditions, metadata or output paths.

`FeeReviewCapability.definition()` is a **predefined trusted capability contract**:
display metadata, input/output contracts, logical target, review-only boundary,
outcomes, checkpoint/extractors and an empty step list. It also supplies a small
trusted vocabulary of page headings and stable row tokens. These declarations
are not discovered by the model. The **ordered steps**, their observed controls,
needed row context and resulting heading postconditions come from actual
successful automation execution. The hand-authored gate 1 fixture is not used as
a recorded step plan or relabeled as live evidence.

Capture occurs inside `BrowserSession.execute`, using the same element handle
that passes policy checks and receives the action. Before acting, the recorder
verifies its role/exact accessible name against the trusted control vocabulary
and verifies exact-one resolution back to that handle. Duplicate controls use
bounded nearest-row cell text. Only an observed whole cell equal to a trusted
stable token (currently `Savings`) or exactly one STRING invocation value can
supply context; the latter becomes a whole `${inputs.name}` expression. Whole
row text, names, balances and dynamic account identifiers are never copied.
Unknown, ambiguous or unusable contexts disqualify compilation, not silently
fall back to a selector. An already unique control needs no row context.

After the action succeeds, clicks require one visible, uniquely named, approved
H1 heading in the resulting UI. No model-provided postcondition is accepted.
Only then is the successful action appended to the private in-memory trace.
Rejected/failed actions, WAIT and COMPLETE do not become artifact steps.
Until the schema supports deterministic waits, any WAIT permanently marks a
compilation-enabled run `TRACE_NOT_REPLAYABLE`, even if its checkpoint later verifies.
The shared request guard is sticky: an intercepted denied request blocks entry,
observation and subsequent actions with `POLICY_DENIED`. Checks after action/UI
inspection precede success and trace append; intercepted background submit requests
cannot be recorded as successful actions or produce an artifact.
The existing compiler parameterizes fills and rejects unknown literal values.
Capture failures leave ordinary discovery running but prevent an artifact.

Compilation is permitted only after `SUCCEEDED / CHECKPOINT_VERIFIED`. Failed,
blocked, timed-out, incomplete and business-outcome discoveries produce no
artifact. Any handoff, simulated expiry or detected unrecorded native input/
navigation permanently marks the trace `TRACE_NOT_REPLAYABLE`, including runs
that subsequently resume and reach the checkpoint. Browser instrumentation tracks
trusted input/click/pointer/key events outside the exact expected automation
target/event allowance; it transmits only a boolean, never event text or values.
This is cooperative instrumentation, not tamper-resistant attribution: use an
exclusively automation-controlled browser for compilable runs. No manual action
is inferred or reconstructed.

The same UUID appears in `DiscoveryRunner.Result.runId`, every redacted event,
the new default `flow-{runId}.jsonl` filename, the private trace and compiled
`Provenance.traceId`. Compilation rejects mismatched IDs or filenames without
the run UUID. Existing evidence files are never rewritten. Custom
`discovery.evidence` paths can include the literal `{runId}` placeholder.
`COMPILED_TRACE` means execution-derived, not proof of a live model call; correlate
the UUID with the event stream's `liveModel` flag. UUID correlation is not a
cryptographic attestation.

The separate, redacted `CompilationResult` reports `INCOMPLETE`, `COMPILED`,
`PERSISTED`, `DISCOVERY_NOT_VERIFIED`, `TRACE_NOT_REPLAYABLE`,
`SEMANTIC_CAPTURE_FAILED`, `COMPILATION_REJECTED`, `OUTPUT_COLLISION` or
`PERSISTENCE_FAILED`. Only COMPILED/PERSISTED carry an artifact in memory.
Its display contains only code, UUID and recorded successful-action count,
not discovery values, result details, descriptors or exception messages.

### Persistence is explicit and disabled by default

The existing discovery flow accepts trusted host configuration:

- `discovery.compile=true`: enable capture and return the compiled artifact
  in memory after verified completion. Default: false.
- `discovery.persist-artifact=true`: additionally publish the validated
  artifact. Requires compilation to be enabled. Default: false.
- `discovery.artifact-directory`: trusted host output directory (default
  `artifacts`); neither the model nor the artifact controls this path.

The only final filename is `capability-<runId>.json`. The store validates first,
writes and flushes a temporary file in the trusted directory, then atomically
creates a hard link at the final name. Existing destinations—including
concurrent collisions—are never replaced. There is no unsafe move/overwrite
fallback if the filesystem does not support hard links. Temporary files are
cleaned up; rejected runs do not create the artifact directory. No input values,
extracted details, runtime IDs, raw HTML, selectors, transcripts or handles are
persisted. Only bounded approved semantic labels and contract expressions survive.

These flags are documented for a separately authorized future run; no live
discovery or persistence demonstration was performed for this gate.

### Offline integration contract (not authentic evidence)

```sh
cd /Users/surabhimarathe/interfaceai-automation
./gradlew test --tests '*DiscoveryCompilationTest'
./gradlew test --rerun-tasks
git diff --check
```

Tests use scripted decisions through the real BrowserSession and synthetic target,
then replay the compiled artifact and check all seven typed outputs. They cover
stable/parameterized row context, unusable/PII-only contexts, ambiguous headings,
partial/failed/blocked/timed-out/outcome runs, same-browser manual input, resumed
handoff, provenance correlation and atomic collision handling. A classloader
isolation test runs discovery, compilation and replay with all OpenRouter classes
unavailable. The provider-specific exception was decoupled from the runner so
scripted execution does not load a model client.

Redacted log fixtures and persisted artifacts produced by these tests live only
in temporary test directories. **They are non-live test output, not authentic
LLM discovery evidence.** No new repository evidence is generated. Scope remains
the predefined fee-review contract and approved heading/row vocabulary; arbitrary
applications, automatic contract inference, human-action reconstruction, and a
live model-to-artifact demonstration remain out of scope.
