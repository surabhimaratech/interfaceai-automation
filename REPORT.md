# Architecture

This Java 21 prototype combines a Spring Boot 4.1.1 synthetic legacy banking
target with Playwright Java 1.62.0. Server-rendered forms and repeated controls
exercise real browser interaction. Synthetic records are immutable, and the target
rejects submission even if reached directly.

Discovery uses authentic OpenRouter observe/decide/act requests to pinned
`anthropic/claude-sonnet-5`. Bounded observations identify fresh controls; a
persistent headed browser executes policy-approved actions on its owner thread.
The model does not supply trusted contracts, locators or postconditions.
Successfully executed actions form an in-memory trace. Verified checkpoint
completion permits compilation, validation and explicitly enabled atomic
create-new artifact persistence.

Deterministic replay has no LLM dependency. It returns structured results,
sensitive in-memory outputs and optional sanitized diagnostic storage. The
[evidence index](evidence/README.md) distinguishes authentic discovery/compilation,
successful replay, exceptional replay and the subsequent approval lifecycle.
Offline tests independently verify the implementation; they are not live evidence.

# Artifact schema

Separate integer schema and artifact versions accompany display metadata,
descriptions, logical target and entry path, typed inputs/outputs, ordered steps,
outcomes, checkpoint extractors, provenance and a structured `REVIEW_ONLY`
boundary. Concrete origins are absent. STRING contracts bound length; DECIMAL
contracts bound range and scale.

Only FILL and CLICK are supported. Fills use whole `${inputs.name}` expressions,
never literal invocation values or executable interpolation. Bounded semantic
locators specify role, accessible name, exact-one cardinality and optional
row/form/fieldset context. Postconditions and checkpoint extractors verify results;
USD_DECIMAL extraction produces a strictly parsed BigDecimal.

Validation rejects malformed JSON, unsupported versions, invalid references,
constraints, expressions and unbounded text. Compilation rejects unknown literal
fills and unusable context, records only successful actions, and refuses traces
containing WAIT, handoff or detected manual input. COMPLETE is not a persisted step.

The hand-authored fixture is test material, not authentic evidence. The
[compiled artifact](artifacts/capability-dc55aa1d-d34c-4735-bc46-b36bbc64f4b0.json)
has execution-derived steps but predefined trusted contracts and semantic
vocabulary. Arbitrary application contracts are not inferred.

# Determinism & error handling

Replay uses fresh exact-one semantic resolution and checks actual URL, control
and destination against trusted policy. Zero and ambiguous matches fail
distinctly. Per-step and overall deadlines bound execution. Every postcondition,
the checkpoint and all typed outputs must verify. Declared business outcomes
are checked before steps and after actions, taking precedence over missing
next-step controls or failed success conditions.

Dispositions are SUCCESS for verified completion, EXPECTED_OUTCOME for declared
business branches, RECOVERABLE for bounded timeout or assistance needed,
POLICY_BLOCK for permission/approval denial, and HARD_FAILURE for other invalid,
verification, browser, terminal handoff or governance failures. Result invariants
reject inconsistent combinations. Recoverable never means automatic retry:
after actionStarted, dispatch may already have affected the target. There is no
rollback. Diagnostics contain fixed phases, flags, counts and redacted artifact
expectations, not observed page content or exception details.

The authentic MEMBER_NOT_FOUND demonstration is an expected business outcome,
not a technical failure. Its sole stored diagnostic is sanitized.

The confidence stretch goal derives observations from actual governed replay
results: success and expected outcomes count as handled; recoverable, policy and
hard failures enter the eligible denominator, with policy/hard failures also
counting as safety failures. Human-assisted runs are recorded only as assisted,
not qualification successes. The operator CLI cannot fabricate observations or
edit counters.

Qualification uses the floored Wilson lower bound with z=1.96, expressed in basis
points, not a claim of independently sampled production reliability. Default
approval requires five eligible runs, score at least 5000 and zero safety
failures. Five successes produce 5655; six produce 6096. Repeated synthetic
successes demonstrate the mechanism, not broad operational confidence.

# Heterogeneity & multi-tenant

A bounded tenant identifier is supplied separately from artifact inputs. Trusted
in-process configuration resolves tenant plus logical target before browser
creation, owning origin, route/action/control permissions, semantic expiry markers
and additive diagnostic redaction. There is no default or cross-tenant fallback.
Unknown tenant and missing target are distinct failures. Two-server tests exercise
different origins, controls and markers using the same logical artifact.

Governance identity combines tenant scope, exact artifact-byte digest and validated
artifact metadata. Strict UTF-8 decoding precedes parsing; even trailing JSON
whitespace selects a different identity. Unattended replay never falls back to
another tenant or digest. Tenant/actor values are hashed for journal metadata;
hashes are pseudonyms, not encryption or authorization.

The explicit VALIDATION mode can gather observations for a registered identity.
UNATTENDED requires that exact identity to be APPROVED and healthy under its stored
approval criteria before browser creation. Unknown/DRAFT and suspended identities
receive distinct policy blocks; corrupt or unavailable governance fails closed.
This remains trusted host configuration, not a production tenant control plane.

# Escalation & handoff

Discovery and replay preserve Browser, BrowserContext, Page and cookies through
AUTOMATION -> HUMAN -> RESUMING -> AUTOMATION ownership. Automation cannot operate
during human ownership or resumption. Single-use signals reject stale/duplicate
resumes; discovery takes a fresh observation and replay resolves the unstarted step
again. Timeout/count limits and renewed safety checks bound handoff.

Replay supports trusted retained-control/session-overlay expiry before dispatch,
not arbitrary login redirects. Missing controls, post-action timeout, denied
requests, dialogs, popups or submit destinations do not trigger retry through
handoff. Same-session replay repair has offline simulated-operator verification,
not a live human browser-handoff claim.

Approval is a separate explicit human decision through register/status/approve/
suspend CLI commands. The [authentic lifecycle demonstration](evidence/approval-lifecycle-f902431f-2c1c-499e-9c12-793eb1078588.md)
qualified five runs; the project author manually approved; one unattended replay
succeeded; changed bytes were blocked; the author explicitly withdrew approval;
suspended replay was blocked. Nine events remained, with six successes. The
evidence qualifies an initial verifier error and subsequent blocked checks.

# Safety

Shared origin/route/action/control policy and request interception block prohibited
requests, including background submissions. REVIEW_ONLY intent never substitutes
for runtime authorization. Dialogs are defensively dismissed then terminate;
popups fail closed. No reversal was submitted in the demonstrations.

APPROVE events snapshot bounded criteria, mandatory zero-safety enforcement and
pseudonymous actor metadata. History reconstructs reliability immediately before
each approval and validates against those historical criteria. Tightening current
host policy governs future eligibility, not retroactive corruption or withdrawal;
older approval requires explicit suspension.

After an approved identity receives an observation, safety failure or falling
below historical criteria triggers an explicit suspension. Unattended preflight
independently rejects unhealthy approval even if suspension persistence failed.
Observation and suspension are separate append-only writes; a storage failure
returns a fixed governance failure but cannot undo completed actions.

The journal uses trusted directories and atomic create-new publication. Immutable
snapshots and strict event parsing support audit inspection, not tamper-proof
storage. Preflight is a point-in-time check, not an execution lease; concurrent
work may outlive suspension. There is no multi-process coordination.

Baseline redaction cannot be disabled; bounded host additions only redact more,
without altering execution. Sensitive outputs remain in memory. Stored diagnostics
exclude credentials, customer values, transcripts, selectors, URLs and page/DOM
content. Privacy scans are bounded, not general PII detection. Correlation UUIDs,
digests and curated evidence do not cryptographically attest to human identity,
execution, process environment or absence of network calls.

# Cuts

Authentication/SSO, arbitrary application/contract inference, automatic recovery,
retries and rollback were intentionally excluded, alongside remote policy services,
authorization databases and tenant administration. Broad browser/desktop/mobile
coverage, screenshot/DOM/Playwright trace retention, production persistence and
retention management, execution leases, multi-process locking and cryptographic
attestation remain outside scope.

These cuts prioritize an inspectable end-to-end vertical slice: authentic
discovery, verified compilation, deterministic review-only execution, bounded
handoff and explicit confidence-based human approval, without claiming production
isolation or general automation.
