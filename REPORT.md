# Architecture

This prototype implements a review-only banking automation slice on Java 21,
Spring Boot 4.1.1 and Playwright Java 1.62.0. The synthetic legacy target uses
server-rendered forms, repeated Open links and nested account tables without
test IDs. Its records and balances are synthetic and immutable; submission is
rejected even if reached directly.

Discovery is an observe/decide/act loop using real OpenRouter requests to pinned
`anthropic/claude-sonnet-5`. Bounded observations describe visible controls and
context; model decisions refer to fresh observation/control IDs. A persistent
headed browser executes policy-approved actions on its owner thread. The model
does not supply trusted artifact locators, contracts or postconditions.

At the execution boundary, successful actions enter an in-memory trace. Only
verified checkpoint completion permits compilation into a validated capability
artifact. Explicit host opt-in enables atomic, create-new persistence. Replay is
a separate deterministic entry point with no LLM dependency. It returns typed
results, sensitive in-memory outputs and sanitized non-success diagnostics;
diagnostic storage is optional. Redacted discovery event logs provide separate
execution evidence.

The [evidence index](evidence/README.md) documents authentic discovery, compilation
and replay under UUID `dc55aa1d-d34c-4735-bc46-b36bbc64f4b0`: nine model decisions,
eight successful actions, and verified review completion. Offline integration
tests independently exercise compilation/replay with OpenRouter classes unavailable.

# Artifact schema

Separate integer `schemaVersion` and `artifactVersion` identify the wire schema
and capability revision. Records declare display metadata, descriptions, logical
target ID and entry path, typed inputs/outputs, ordered steps, outcomes, checkpoint
extractors, a structured `REVIEW_ONLY` boundary and source/trace provenance.
Concrete origins are absent.

`STRING` contracts bound length; `DECIMAL` contracts bound range and scale.
Actions are only `FILL` and `CLICK`. Fills require whole `${inputs.name}`
expressions, not literals or executable interpolation. Semantic locators specify
role, accessible name, exact-one cardinality and optional bounded row/form/fieldset
context. Step postconditions verify values or visibility. Expected outcomes use
visible status/alert conditions; checkpoint extractors cover every declared
output. `USD_DECIMAL` extraction produces `BigDecimal` through a strict format.

Validation rejects unsupported versions, duplicate IDs, invalid references,
constraints, expressions, missing extractors, unbounded text and malformed JSON.
Structural validity does not authorize an action. Compilation parameterizes known
fills, rejects unknown literals and unusable semantic context, and excludes failed
actions. WAIT, handoff or detected manual input makes a trace non-replayable;
COMPLETE is never persisted as a step.

The [hand-authored fixture](src/test/resources/artifacts/prepare-fee-reversal-review.v1.json)
is test material. The [compiled artifact](artifacts/capability-dc55aa1d-d34c-4735-bc46-b36bbc64f4b0.json)
has execution-derived steps, but predefined trusted contracts and heading/row
vocabulary. Neither arbitrary contracts nor application semantics are inferred.

# Determinism & error handling

Replay follows the artifact's ordered steps with typed invocation parameters and
trusted runtime configuration, never model decisions. Fresh semantic lookup must
resolve exactly one visible control; zero and ambiguous matches are distinct
failures. Policy is checked against the actual URL, control and destination.
Per-step and overall deadlines bound execution. Every postcondition must pass;
success requires checkpoint verification and validated extraction of all outputs.
Declared outcomes are checked before steps and immediately after actions, taking
precedence over missing next controls or failed success conditions.

Five dispositions distinguish operational meaning: `SUCCESS` means
`SUCCEEDED / CHECKPOINT_VERIFIED`; `EXPECTED_OUTCOME` means a declared business
branch; `RECOVERABLE` covers bounded execution timeout or human assistance needed
without a coordinator; `POLICY_BLOCK` means non-retryable permission denial;
`HARD_FAILURE` covers other invalid, ambiguous, verification, browser or terminal
handoff failures. Result construction rejects inconsistent status/code/disposition
combinations. Expected outcomes expose only validated artifact identifiers.

Recoverable does not mean automatically safe to repeat. There is no automatic
retry or rollback. Once `actionStarted` is true, dispatch may have affected the
target before timeout; cancellation cannot undo it. Diagnostics retain phase,
step, redacted artifact expectations, bounded match counts and fixed flags—not
exception messages or observed page content.

The authentic [exceptional replay evidence](evidence/README.md) records
`EXPECTED_OUTCOME / MEMBER_NOT_FOUND`, disposition `EXPECTED_OUTCOME`, step 2,
and diagnostics `STORED`, using the exact compiled artifact without a provider
key. Exactly one sanitized diagnostic was produced. This is a business outcome,
not a technical failure, and no reversal was submitted.

# Heterogeneity & multi-tenant

The artifact names a logical surface, while the caller supplies a separate,
bounded `TenantId`. An immutable trusted registry resolves the composite
tenant-plus-target key before browser creation. Each configuration owns origin,
route/action/control permissions, optional semantic session-expiry marker and
diagnostic redaction policy. Artifacts cannot override these authorities.

There is no default-tenant or cross-tenant fallback. Unknown tenant and missing
target within a known tenant return distinct hard failures without launching a
browser. An explicit single-tenant adapter preserves the existing CLI, which
requires `REPLAY_TENANT_ID`. Two-server integration tests replay the same artifact
successfully at different origins and verify control/marker differences and
cross-origin blocking.

Redaction additions are immutable, bounded literal secrets or restricted patterns;
they affect diagnostics only, never execution values or typed outputs. Invocation
and tenant suppression uses case-insensitive `Locale.ROOT` comparisons. This is
an in-process configuration prototype, not a production tenant control plane or
proof that a caller is authorized to select a tenant.

# Escalation & handoff

Discovery and replay share the ownership protocol
`AUTOMATION -> HUMAN -> RESUMING -> AUTOMATION`. Browser, BrowserContext, Page
and cookies remain in the same session. Automation is prohibited during HUMAN
and RESUMING ownership. A single-use resume signal rejects stale or duplicate
attempts; discovery then takes a fresh observation, while replay discards the old
handle and resolves the current unstarted step again.

Discovery can request human assistance through model decisions, simulated expiry
or no-progress detection. Replay permits handoff only for a trusted semantic
expiry marker before dispatch, with policy-authorized current controls. Its
separate loopback operator surface preserves the discovery operator. Timeout,
count and overall-run bounds prevent indefinite waiting; resume rechecks safety
and ownership.

Replay currently supports retained-control/session-overlay expiry, not arbitrary
login redirects. A missing or ambiguous current control fails rather than invoking
handoff. Post-dispatch timeout, denied requests, dialogs, popups and submit-like
destinations never trigger a retry through human takeover. Same-session replay
repair is verified by offline simulated-operator tests, not claimed as a live
human demonstration. Human cooperation remains necessary after ownership returns.

# Safety

Origin, route, action and control allowlists constrain both automation modes.
Shared request interception also catches script-initiated denied requests.
Submit destinations remain blocked despite configured control names; the
`REVIEW_ONLY` declaration is enforced alongside runtime policy and the synthetic
target's submission backstop. Unexpected dialogs are dismissed defensively and
terminate as `UNEXPECTED_DIALOG`; popups fail closed. Policy denial is typed and
sticky, preventing later actions or successful trace recording.

An unremovable diagnostic baseline precedes additive host redaction. Sensitive
outputs remain in memory and printable results redact them. Stored diagnostics
exclude provider transcripts, customer values, credentials, selectors, URLs and
DOM/page captures. They may retain reviewed artifact expectations, such as a
declared outcome label; this is not observed page text. Discovery sends bounded
UI context to the provider in memory, so the demonstration uses synthetic data.

Persistence uses trusted directories and atomic create-new publication without
overwrite. Privacy scans and schema restrictions are bounded protections, not
universal secret detection. Redacted logs, UUID correlation and artifact digests
support inspection but do not cryptographically attest to execution, process
environment or absence of network calls.

# Cuts

Real authentication/SSO, arbitrary application or contract inference, automatic
recovery/retries/rollback, remote policy services, authorization databases and
tenant administration were intentionally excluded. General PII detection and
broad browser, desktop or mobile coverage are not implemented. Neither
screenshots, DOM dumps nor Playwright traces are retained. Production persistence,
retention management and cryptographic attestation remain outside scope.

These cuts prioritize a correct end-to-end vertical slice: authentic discovery,
verified compilation, deterministic review-only replay, explicit business outcomes,
bounded escalation and inspectable privacy-safe evidence, rather than unsupported
claims of general automation or production isolation.
