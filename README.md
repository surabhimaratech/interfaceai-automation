# InterfaceAI Automation

[Read the implementation report](REPORT.md) for architecture, evidence and limitations.

Java 21, Spring Boot 4.1.1, Playwright Java 1.62.0. Commands run from the
repository root. From its parent directory, use `cd interfaceai-automation`;
if you cloned under another name, use that directory instead.

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

Submit reversal is present to exercise automation-policy blocking. The target also
unconditionally rejects submission with `ACTION_BLOCKED` (HTTP 403); it never
changes a balance. This target-side backstop complements the implemented runtime
policy check, which rejects submit actions before execution.

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
behind an opt-in flag. A successful live discover → compile → replay demonstration
is documented in `evidence/README.md` under run UUID
`dc55aa1d-d34c-4735-bc46-b36bbc64f4b0`. Day 3 gate 1 adds replay diagnostics,
recoverability classification and opt-in privacy-safe diagnostic storage. Day 3
gate 2 adds bounded, pre-action same-session human handoff during deterministic replay.
Gate 3 adds explicit tenant-scoped target resolution and additive host-configured
diagnostic redaction. Day 4 exceptional replay evidence records the declared
`MEMBER_NOT_FOUND` branch with a stored sanitized diagnostic; see
[evidence/README.md](evidence/README.md).
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
only state/code/step count. No HTTP result endpoint or persistence of financial
details is provided.

Known not-found and validation messages return BUSINESS_OUTCOME without another
model call. REQUEST_HUMAN or an unknown alert pauses for the local operator when
the flow's handoff coordinator is attached. A runner without that coordinator
returns HUMAN_REQUIRED. Invalid/stale
decisions, step limits, deadline expiry and provider errors terminate explicitly.

Flow evidence uses unique `evidence/flow-<uuid>.jsonl` files with state transitions,
step counts and fixed result codes. Previous evidence is preserved. The original
Day 1 flow did not create reusable artifacts; Day 2 compilation is separately
opt-in, and replay is a separate entry point.

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
presented as a real human demonstration. CDP attachment is test-only; the replay
handoff tests below also use it to simulate an operator.

## Day 2 gate 1: capability artifact schema

The `artifact` Java package defines immutable records and the supported JSON
boundary, `ArtifactJson.read/write`. `schemaVersion: 1` selects the wire format;
positive `artifactVersion` independently versions a capability definition. The
hand-authored example is
`src/test/resources/artifacts/prepare-fee-reversal-review.v1.json`. It is a schema
fixture, **not authentic discovery evidence**. The original schema-only gate
added no evidence; later live compilation and replay evidence is linked below.

- Capability metadata includes a required display name (max 160 characters),
  description (max 1000), and structured `executionBoundary: {"mode":"REVIEW_ONLY"}`.
  Every input/output requires a description (max 500). Missing, blank, oversized
  or control-character-bearing metadata is rejected.
- `TargetSpec` contains only a logical `targetId` (max 80, letters/digits/underscore/
  hyphen, starting with a letter) and bounded entry path. Concrete origins and
  URL overrides are not accepted fields. Replay resolves the origin through trusted
  tenant/target runtime configuration; the artifact cannot supply it.
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
  expressions require the same contract type. Gate 1 defined these declarations;
  gate 2 implements their extraction and replay semantics. Outcomes must be non-empty, have unique
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
POST remains blocked. Historically, gates 1 and 2 supplied declarations and
policy-checked replay without live compilation. Gate 3 below adds execution-derived
compilation. No gate grants permissions based on artifact labels.

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
trusted tenant/target configuration (see Day 3 gate 3). The two-argument API
requires an explicit single-tenant `TargetRegistry` adapter. Invalid artifacts/parameters,
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
  `CHECKPOINT_FAILED`, `EXTRACTION_FAILED`, `TIMEOUT`, `UNEXPECTED_DIALOG`,
  `INTERRUPTED` or `BROWSER_FAILURE`. Later gates also add `UNKNOWN_TENANT`,
  `UNKNOWN_TENANT_TARGET`, `HUMAN_ACTION_REQUIRED`, `HANDOFF_TIMEOUT`,
  `HANDOFF_LIMIT` and `OWNERSHIP_DENIED`, detailed below.

Non-success results now also carry the bounded diagnostics described in Day 3
below; no exception details, input values, page text, URLs or partial outputs.
Successful outputs are sensitive
in-memory return data. Result/parameter `toString()` methods redact values, and
the CLI prints the redacted result with its effective code, including validated
artifact identifiers such as `MEMBER_NOT_FOUND` or `VALIDATION_REJECTED`, never
page text or output values.
No model requests or discovery compilation occur during replay. Diagnostic
persistence is off by default and requires explicit trusted host configuration.

Timeout defaults: 5 seconds per step and 60 seconds overall; configurable bounds
are 1 ms–30 seconds per step and 1 ms–5 minutes overall. Navigation and final
checkpoint/extraction receive their own per-step budget. The overall caller
deadline also bounds launch and cleanup. Cancellation prevents later steps and
cleanup runs on the owner thread; an already-dispatched request cannot be undone.
No click/fill is retried by the engine. Playwright may wait for actionability
within the remaining budget before dispatching a single action. Missing locator
and postcondition snapshots fail immediately; this gate does not add SPA polling,
automatic recovery or rollback. Day 3 gate 2 below adds only a bounded pre-action
human handoff; it does not retry dispatched actions.

### Exact local fixture command (not live evidence)

Start the synthetic server in one terminal:

```sh
cd interfaceai-automation
./gradlew bootRun --args='--discovery.flow=false --discovery.proof=false'
```

In a second terminal, invoke the hand-authored fixture without an API key:

```sh
cd interfaceai-automation
env -u OPENROUTER_API_KEY \
REPLAY_TENANT_ID=synthetic-local \
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
`REPLAY_TENANT_ID` is required: the CLI explicitly binds a single tenant, with no
default. `REPLAY_ORIGIN` is a host-side override, not artifact data. Optional environment
settings are `REPLAY_HEADLESS=true`, `REPLAY_STEP_TIMEOUT_MS`, and
`REPLAY_TIMEOUT_MS`. Do not source an API-key file for replay.

Run offline integration and full regression tests:

```sh
./gradlew test --tests '*replay.*'
./gradlew test --rerun-tasks
git diff --check
```

These use local synthetic UI and reviewed test fixtures/configuration. They are
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

The original gate 3 implementation was verified offline. A subsequent authorized
live discovery → compilation → replay demonstration completed successfully under
UUID `dc55aa1d-d34c-4735-bc46-b36bbc64f4b0`; see `evidence/README.md` for the
unchanged log/artifact linkage, redaction and attestation limitations. Replay used
the exact compiled artifact with `OPENROUTER_API_KEY` removed. No reversal was submitted.

### Offline integration contract (not authentic evidence)

```sh
cd interfaceai-automation
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
applications, automatic contract inference and human-action reconstruction remain
out of scope. The separately recorded live demonstration does not turn these
scripted tests into authentic model evidence.

## Day 3 gate 1: replay diagnostics and recoverability

`ReplayResult` preserves its status/code/outcome and sensitive in-memory outputs,
and adds a structured `disposition`:

| Disposition | Result | Meaning |
| --- | --- | --- |
| `SUCCESS` | `SUCCEEDED / CHECKPOINT_VERIFIED` | All checks passed; outputs unchanged. |
| `EXPECTED_OUTCOME` | `EXPECTED_OUTCOME / <validated outcome code>` | Declared business branch, not a technical failure. |
| `RECOVERABLE` | `FAILED / TIMEOUT` or `FAILED / HUMAN_ACTION_REQUIRED` | Bounded execution timeout, or trusted expiry requiring an operator when none is configured; never an automatic retry. |
| `POLICY_BLOCK` | `BLOCKED / POLICY_DENIED` | Non-retryable permission or intercepted-request denial. |
| `HARD_FAILURE` | Other failures | Includes invalid contracts/parameters, locator, postcondition, checkpoint, extraction and browser failures, `UNEXPECTED_DIALOG`, and `INTERRUPTED`. |

Recoverable is a classification, not a retry or resume instruction. No click/fill
is automatically retried. An action marked started may already have reached the
target; inspect/reset state before explicitly invoking another run. Policy blocks,
unexpected dialogs and unexpected pages take precedence over timeouts and are
non-retryable. Dialogs are dismissed defensively without retaining their text;
automation stops afterward. Unexpected pages are closed and fail as
`BROWSER_FAILURE` with an `unexpectedPage` flag. Cancellation is `INTERRUPTED`,
not an invitation to retry. No rollback or automatic recovery is added. The
subsequent gate 2 handoff below is restricted to an unstarted, policy-authorized action.

Every non-success engine result, including an expected business outcome, contains
a `ReplayDiagnostic`. Successful results have no diagnostic. It contains only:

- the fixed code, numeric step and bounded artifact step ID (null before a step);
- a fixed phase: validation, parameters, target resolution, launch, load, outcome,
  locator, action, postcondition, checkpoint, extraction or cleanup;
- the expected artifact role, name/match mode, optional context and cardinality;
- a bounded visible-match count and zero/ambiguous/capped indicators; `-1` means
  not measured, not zero, and `201` indicates the candidate cap was exceeded;
- fixed condition flags for action started, request/policy denial, dialog/page,
  timeout, postcondition/checkpoint failure and expected outcome detection.

Expectations are unexpanded artifact declarations, not observed accessible names.
Whole `${inputs.name}` expressions remain expressions. Known invocation values
and suspicious literal text (control characters, numeric identifiers, URLs,
email/secret-like text or markup) are replaced with `[REDACTED]`; identifiers are
also bounded and checked against invocation values. This conservative filter may
hide benign labels. It is not general-purpose PII detection for arbitrary artifact
prose: supply reviewed artifact declarations, never embed sensitive literals.
No page text, resolved input/output values, target URLs, HTML, selectors, dialog
text, exception details, screenshots or browser handles are collected in diagnostics.
Failed lookup context is preserved when outcome probes find no business outcome.
Diagnostics are snapshots, not DOM dumps or complete event histories.

`ReplayResult.toString()` prints status, disposition, safe effective code, step,
and `outputs=REDACTED`; it never prints the diagnostic body. When storage is enabled,
it also prints the fixed diagnostic persistence status, never a filename/path.

### Opt-in diagnostic storage (trusted host only)

Default: off. Java callers explicitly pass `new DiagnosticStore(trustedDirectory)`
to `ReplayEngine`; the CLI enables it only with `REPLAY_DIAGNOSTIC_DIRECTORY`.
The artifact/model cannot choose this directory or filename. For a separately
authorized local replay, after setting the existing `REPLAY_*` invocation values
and starting the synthetic target, deliberately request a missing member. The
member ID override applies only to this command, leaving the caller's value unchanged:

```sh
export REPLAY_DIAGNOSTIC_DIRECTORY="$(mktemp -d)"
env -u OPENROUTER_API_KEY REPLAY_TENANT_ID=synthetic-local REPLAY_MEMBER_ID=999999 ./gradlew replay --args='src/test/resources/artifacts/prepare-fee-reversal-review.v1.json'
unset REPLAY_DIAGNOSTIC_DIRECTORY
```

Expected: status `EXPECTED_OUTCOME`, code `MEMBER_NOT_FOUND`, disposition
`EXPECTED_OUTCOME`, and `diagnosticPersistence()` equal to `STORED` (printed as
`diagnostics=STORED`). This invocation creates one new sanitized diagnostic JSON
file in the configured directory. No reversal is submitted.

Only sanitized diagnostic JSON is persisted for non-success engine results, under
`diagnostic-<host-generated-uuid>.json`. Successful runs do not create a directory.
An in-directory temporary file is flushed and atomically hard-linked to the final
name with create-new semantics. Existing files are never overwritten; temporary
files are cleaned up. Unsupported hard links fail closed without an overwrite
fallback. `diagnosticPersistence()` is `DISABLED`, `NOT_APPLICABLE`, `STORED`,
`COLLISION` or `FAILED`; a storage error does not change the replay disposition/code.
Storage/cleanup are local I/O outside the bounded UI execution deadline. Trusted
host directories must not be writable by untrusted actors. CLI configuration/file
reading errors before engine invocation print a redacted result, but do not write
diagnostics. Retention/rotation, screenshots and cryptographic attestation are not provided.

Real-browser regression tests cover slow load and review steps without retries,
dialogs, missing/ambiguous locators, postcondition/checkpoint failures, policy
denial, unchanged successful outputs, sanitized JSON and atomic collisions. All
diagnostic test files use temporary directories and are **non-live test output**,
not repository evidence. Existing Day 2 evidence and artifacts are unchanged.

## Day 3 gate 2: bounded same-session replay handoff

Replay may pause only for an exact, visible, trusted target-configured session
marker: `TargetRegistry` accepts an optional target-ID → `SessionExpiryMarker`
mapping. Markers allow only `ALERT` or `STATUS`, with a bounded literal accessible
name. They are not artifact fields, model decisions, substring searches or inferred
page prose. Duplicate markers fail closed. Without configured markers replay
behaves as before; an unrelated alert does not request handoff.

The browser-owner thread resolves the current action and checks its actual URL,
name and destination through the shared policy guard before considering handoff.
For this bounded gate, the current control must still be uniquely resolvable and
policy-authorized; a disabled control can be handed off, but a replaced login page
with no current-step control fails its locator check. Submit destinations are
denied before handoff or dispatch. The marker is checked again after actionability
reads, immediately before dispatch. No timeout or failed action triggers handoff.

`ReplayHandoff` reuses `HandoffCoordinator` without discovery logging, with ownership:
`AUTOMATION → HUMAN → RESUMING → AUTOMATION`. The action handle is disposed before
HUMAN ownership. The original Browser, BrowserContext, Page and cookie jar remain
open; there is no relaunch, new context, navigation reset or cookie copying. While
HUMAN owns the page, the owner thread only pumps browser callbacks and checks fixed
safety/deadline flags. It does not observe, resolve controls, extract or execute.

A valid single-use resume signal enters RESUMING. Automation is still prohibited
there. The browser-owner thread verifies ownership, the current URL, context/page
health and sticky policy/dialog/popup flags before reclaiming AUTOMATION. Only
then does it restart the **unstarted** current step with a fresh lookup, permission
check and per-step budget. It never reuses a pre-handoff handle. If the marker
remains, another bounded handoff may occur, not an action retry.

The handoff count is bounded to 1–10 and each wait to 1 ms–5 minutes. The existing
overall replay deadline includes human time and is not extended. A timeout during
handoff, including the overall deadline, terminates as `HANDOFF_TIMEOUT`. Terminal
results close ownership, invalidate resume signals and close the browser normally.
One coordinator belongs to one invocation; it cannot be reused for another run.
Handoff-enabled runs require headed mode.

New terminal results retain the validated `ReplayResult` status/code invariant:

| Status / code | Disposition | Meaning |
| --- | --- | --- |
| `FAILED / HUMAN_ACTION_REQUIRED` | `RECOVERABLE` | Trusted expiry before dispatch, but no coordinator is configured. |
| `FAILED / HANDOFF_TIMEOUT` | `HARD_FAILURE` | Human wait or its overall budget expired; no automatic resume. |
| `FAILED / HANDOFF_LIMIT` | `HARD_FAILURE` | Another handoff would exceed the configured count. |
| `FAILED / OWNERSHIP_DENIED` | `HARD_FAILURE` | Automation attempted outside its ownership state. |

An intercepted request, policy denial, unexpected dialog/page or already-started
action can never enter/re-enter handoff. Unsafe flags take precedence over timeout.
New diagnostics contain only fixed session-expiry/handoff flags, bounded count and
`SESSION_CHECK`, `HANDOFF` or `RESUMING` phases. Marker prose, human-entered values,
URLs, cookies, tokens and browser handles are never recorded. Existing redacted
CLI output and optional diagnostic persistence also cover the new terminal codes.

### Separate local operator surface

`ReplayOperatorServer` is a separate loopback-only HTTP listener, default CLI port
18082. It does not modify discovery's `/operator` controller. In an **external
operator browser**, open `http://127.0.0.1:18082/replay-operator`, repair the existing
headed automation window, then refresh the operator page and select **Resume replay**.
Do not navigate the automation page to the operator URL: that is outside target
policy. `GET /replay-operator/status` exposes only fixed ownership metadata.

The server uses exact Host/Origin checks, POST-only resume, `no-store`, restrictive
CSP and an HttpOnly/SameSite=Strict single-use cookie. The token is transport-only:
it is not in HTML, hidden fields, status JSON, diagnostic JSON or CLI output.
Stale, duplicate and wrong-epoch signals return 409; invalid origins return 403.
Local processes are trusted; this is not a remote/multi-user authentication service.
Do not log operator request/response headers, which necessarily transport the cookie.

Trusted CLI configuration (off by default):

- `REPLAY_SESSION_EXPIRY_NAME`: exact marker name; optional `REPLAY_SESSION_EXPIRY_ROLE`
  is `ALERT` by default or `STATUS`. Omit the name to disable marker detection.
- `REPLAY_HANDOFF=true`: enable the coordinator and local operator server. Requires
  a configured marker and `REPLAY_HEADLESS=false`.
- `REPLAY_HANDOFF_TIMEOUT_MS=60000`, `REPLAY_HANDOFF_LIMIT=2`,
  `REPLAY_OPERATOR_PORT=18082`: host-controlled bounds and listener port.

The production synthetic target does not inject expiry on its own. This gate's
tests install a test-only marker and recovery control; it adds no live simulation
command. No live demonstration was performed for this gate.

### Exact offline verification

```sh
cd interfaceai-automation
./gradlew test --tests '*ReplayHandoffIntegrationTest' --tests '*ReplayOperatorTest' --rerun-tasks
./gradlew test --rerun-tasks
git diff --check
```

Scripted operator tests use a separate test-only CDP attachment to the existing
headed page, replace the old control during repair, and verify all seven exact
outputs, unchanged browser/context/page identity and cookie delivery afterward.
They cover stale/duplicate signals, both non-automation ownership states, timeout
and count bounds, resume-time URL denial, non-semantic/duplicate markers, and
post-dispatch timeout/submit/dialog/popup/request-denial exclusions. Operator and
diagnostic redaction checks use temporary test data only, not repository evidence.

Limitations: only the retained-control expiry shape above is supported, not arbitrary
login/SSO redirects or missing/ambiguous controls. Human behavior is cooperative;
the request guard still intercepts denied destinations during manual operation,
but cannot undo already-dispatched work. No LLM, action retry, rollback, screenshot,
DOM capture, artifact compilation or human-input recording is added. No reversal
is submitted by replay or its operator tests.

## Day 3 gate 3: tenant-scoped targets and diagnostic redaction

Resolution is explicit and case-sensitive, with no default or cross-tenant fallback:

```text
caller TenantId + artifact logical targetId
  -> exact trusted TenantTargetRegistry entry (before browser creation)
  -> ActionPolicy + optional SessionExpiryMarker + DiagnosticRedactionPolicy
  -> deterministic UI execution
  -> baseline redaction + tenant suppression + host additions -> optional diagnostics
```

A tenant ID is 1–64 ASCII characters matching `[A-Za-z][A-Za-z0-9_-]{0,63}`.
It is separate from invocation parameters and is not an authentication credential.
The immutable registry owns each tenant's target map, concrete origin, allowed
routes/actions/control names, marker and redaction policy. Artifacts retain only
logical target ID and entry path; strict JSON rejects configuration injection.
The existing shared action policy and request interception remain authoritative.

Example trusted Java configuration (synthetic IDs; `policyA` and `policyB` are
host-created `ActionPolicy` instances with distinct origins and explicit route
and control allowlists):

```java
var tenantA = new TenantId("labTenantA");
var tenantB = new TenantId("labTenantB");
var registry = new TenantTargetRegistry(Map.of(
    tenantA, Map.of("legacy-banking", new TenantTargetConfiguration(
        policyA, null, new DiagnosticRedactionPolicy(
            List.of("synthetic-secret"), List.of("CASE-[0-9]{1,8}")))),
    tenantB, Map.of("legacy-banking", new TenantTargetConfiguration(
        policyB, null, DiagnosticRedactionPolicy.baseline()))));
var engine = new ReplayEngine(registry, options);
var resultA = engine.run(tenantA, artifactJson, parameters);
var resultB = engine.run(tenantB, artifactJson, parameters);
```

Markers can differ per tenant/target. The same artifact and typed parameters
execute unchanged; redaction never rewrites execution values or typed outputs.
Unknown tenants return `FAILED / UNKNOWN_TENANT`; missing targets within a known
tenant return `FAILED / UNKNOWN_TENANT_TARGET`. Both are `HARD_FAILURE`, resolve
before browser launch and support sanitized diagnostic persistence. Missing explicit
tenant input fails closed as `INVALID_PARAMETERS`; invalid ID construction rejects
with a fixed error.

The CLI and older tests use the explicit compatibility adapter
`TargetRegistry.singleTenant(new TenantId("synthetic-local"), policies, markers)`
(the marker map is optional). Its two-argument replay API retains the legacy
`UNKNOWN_TARGET` code. Native tenant-aware calls use the distinct codes above.
The CLI requires `REPLAY_TENANT_ID`; it does not infer identity from inputs or the
artifact. Custom redaction is configured through the trusted Java registry, not
artifact fields or model output.

### Additive diagnostic redaction

The existing conservative baseline cannot be disabled. Host additions mask an
entire matching diagnostic field, never restore baseline-blocked data. They apply
to step IDs and expected artifact role/name/context strings; fixed enum roles and
cardinality contain no observed data. All registered tenant identifiers are
suppressed from diagnostic text. Result presentation and UUID diagnostic filenames
contain no tenant metadata. Artifact outcome identifiers containing a registered
tenant identifier in any case are rejected rather than reflected in redacted
result output. Invocation-value and tenant suppression use `Locale.ROOT`
case-insensitive comparisons; tenant registry lookup itself remains case-sensitive.

Policies defensively copy their configuration. At most 32 literal secrets and
16 patterns are accepted; each must be nonblank, control-character-free and at
most 160 characters. Literal matching is case-insensitive. Patterns use a small
case-sensitive language, not general Java regex: printable ASCII literals,
approved classes `[A-Z]`, `[a-z]`, `[0-9]`, `[A-Za-z]`, `[A-Za-z0-9]`,
`[A-Z0-9]`, and optional `{n}` or `{min,max}` repetition (1–32). Regex
metacharacters, alternation, lookaround, backreferences and unbounded repetition
are rejected. Rules have at most 32 atoms and maximum expanded length 256.
Matching uses bounded dynamic programming on at most 300 characters, without
backtracking. Malformed/oversized configuration fails during host construction,
before browser launch.

### Offline verification and limitations

```sh
cd interfaceai-automation
./gradlew test --tests '*TenantReplayIntegrationTest' --tests '*DiagnosticRedactionPolicyTest' --rerun-tasks
./gradlew test --rerun-tasks
git diff --check
```

Tests run two real local synthetic target servers, verify independent successful
seven-output replays, origin/control/marker isolation, pre-launch failures,
redaction-only behavior and sanitized persistence in temporary directories.
These tests are not live model evidence and make no OpenRouter calls.

Configuration is trusted and in-process: there is no remote policy service,
authentication, authorization database or tenant administration UI. The caller
must establish authorization upstream; supplying a tenant ID alone does not do
that. Host administrators remain responsible for correct mappings and reviewed
artifact vocabulary. Additive patterns are deliberately restrictive, not a
general-purpose PII detector. Conservative tenant-name collisions can reject an
outcome identifier or mask benign metadata. No automatic retry, rollback, new
live evidence or reversal submission is introduced.

## Optional stretch: confidence & approval — gate 1

The isolated `approval` package defines governance metadata, **not replay
enforcement**. Neither `ReplayEngine` nor the CLI consults approval state yet.
There is no new live evidence, approval endpoint or operator UI. The host is
responsible for supplying truthful observations and an identified human approver;
the module does not authenticate either assertion.

`ArtifactIdentity.from(TenantId, byte[])` first strictly decodes UTF-8 and validates
with `ArtifactJson`, then hashes the exact bytes with SHA-256. Identity comprises
capability ID, logical target ID, schema/artifact versions, artifact digest and
tenant-scope hash. Whitespace changes are deliberately new identities; no JSON
canonicalization or inherited approval occurs. Tenant hashes use UTF-8 bytes of
`interfaceai:approval:tenant:v1`, a NUL separator, and the exact tenant identifier.
Actor hashes use the distinct `interfaceai:approval:actor:v1` domain. All digests
are lowercase 64-character hexadecimal. Raw tenant/actor IDs are never journal
fields; displays redact identities. Artifact identifiers containing the tenant
identifier case-insensitively are rejected to avoid copying it into metadata.
Hashes are pseudonyms, not encryption: small identifier spaces remain guessable.

Confidence counts `SUCCESS` and `EXPECTED_OUTCOME` as handled automated runs:
correctly recognizing a declared business branch is successful automation even
when the requested business operation cannot proceed. `RECOVERABLE_FAILURE`
adds an unsuccessful eligible run. `POLICY_BLOCK` and `HARD_FAILURE` also add
safety failures. `HUMAN_ASSISTED` increments a separate count but neither automated
numerator nor denominator: human repair does not establish unattended reliability.
Only these fixed observations are stored, never replay results, diagnostics,
invocation values, URLs, outputs or page content.

For handled count `h`, eligible count `n`, `p=h/n`, and fixed `z=1.96`, the score is:

```text
lower = (p + z²/(2n) - z*sqrt(p*(1-p)/n + z²/(4n²))) / (1 + z²/n)
basisPoints = floor(10000 * clamp(lower, 0, 1))
```

No eligible runs gives zero. Java strict floating-point operations and `StrictMath`
produce a deterministic result; flooring deliberately rounds down, not to nearest.
Independent high-precision fixtures are 0/0 → 0, 5/5 → 5655, 4/5 → 3755,
9/10 → 5958, and 10/10 → 7224. State exposes handled, eligible, assisted,
safety-failure counts and basis points. These statistical scores do not establish
independence of runs, general application coverage or authority to execute.

`ApprovalPolicy` bounds minimum eligible runs to 1–100 and score to 0–10000.
Defaults are five eligible runs and 5000 basis points; zero safety failures is
mandatory and cannot be disabled. Eligibility never transitions lifecycle:
`DRAFT -> APPROVED -> SUSPENDED`. `ApprovalService.approve` requires an explicit
nonblank, bounded human identifier and records only its hash plus injected-clock
time. Unknown identities, ineligible drafts, any pre-approval safety failure,
repeat approval and approval after suspension are rejected with fixed codes.
Suspension requires APPROVED and a fixed `SuspensionReason`, never free-form prose.
SUSPENDED accepts no further observations or approval in v1. A repaired artifact
needs a new version/digest and starts DRAFT. Later observations do not silently
approve or suspend an existing identity; suspension remains explicit.

Host-only example (metadata operations, not a replay or authentication flow):

```java
var store = new InMemoryGovernanceStore();
var governance = new ApprovalService(store, ApprovalPolicy.defaults(), clock);
var draft = governance.register(new TenantId("synthetic-local"), exactArtifactBytes);
// Record fixed observations only after the host has established their provenance.
// Once eligible, an identified human must explicitly call approve(identity, actor).
```

`GovernanceStore` has immutable-snapshot in-memory and opt-in `FileGovernanceStore`
implementations. The latter accepts a trusted directory and optional UUID supplier;
the service accepts an injected `Clock`. Each event carries journal schema v1,
identity metadata, type, applicable fixed observation/reason, global revision,
timestamp, and actor hash plus immutable approval criteria only for approval.
The criteria explicitly record minimum eligible runs, minimum score and the
mandatory zero-safety-failures rule used at decision time. State is rebuilt, not
trusted from a serialized score: each historical approval is validated against
its own stored criteria and the preceding reconstructed reliability counts.
Current policy governs only future DRAFT eligibility and new approvals; tightening
it does not invalidate older approvals or block unrelated identities. Withdrawing
an older approval requires explicit suspension. `ApprovalState.approvedUnder`
remains available after approval, later observations and suspension; it is null
for DRAFT. Criteria provide audit context, not authentication or tamper attestation.
Journal schema remains v1 because this gate is uncommitted and unpublished.
Pre-correction journals lacking the required field are rejected, not silently
upgraded; no legacy migration or inferred historical policy is provided.

Files are `event-<10-digit-revision>-<uuid>.json`, with no identifying metadata in
filenames. Publication flushes a same-directory temporary file and atomically
creates a hard link; collision or unsupported hard links never fall back to
overwrite. Temporary files are cleaned up. The journal rejects invalid JSON,
unknown fields, missing/duplicate/out-of-order revisions, repeated UUIDs,
backward timestamps, conflicting identity metadata and illegal transitions.
Unexpected files, including crash-left temporary files, fail closed. Limits are
100000 events per journal and 8192 bytes per event. Existing bytes are never
rewritten. In-process mutation locks serialize updates, including separate store
instances resolving to the same real directory; concurrent calls cannot lose events.

This is trusted local append-only storage, not tamper-evident persistence.
Multi-process writers are unsupported. Administrators must protect the directory;
valid malicious rewrites, complete deletion or tail truncation cannot be proven
absent without an external trust anchor. There is no remote approval service,
authentication, key management, signatures, retention/rotation, automatic recovery,
or rollback. A filesystem failure after publication can leave a committed event
despite an error response; inspect history rather than retrying blindly.

Offline verification (temporary test journals only, no model calls):

```sh
./gradlew test --tests '*approval.*' --rerun-tasks
./gradlew test --rerun-tasks
git diff --check
```
