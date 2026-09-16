# Authentic local approval lifecycle — Gate 3B

Date: 2026-09-15 America/Los_Angeles; final verification 2026-09-16T01:33:09Z.
Evidence correlation UUID: f902431f-2c1c-499e-9c12-793eb1078588.
Application revision: 639504e4a783d3879c28f291844122fa5259cfa3.

Artifact: `artifacts/capability-dc55aa1d-d34c-4735-bc46-b36bbc64f4b0.json`.
SHA-256: `3ef4049d0fc2346c564fd6b97bb86b5824fd616b127a3fe855e3c50c42587f59`.
Original discovery/compilation run: dc55aa1d-d34c-4735-bc46-b36bbc64f4b0.

This is a curated redacted account of authentic local CLI execution, not a raw
transcript or an offline scripted-decision test. A fresh isolated journal and
explicit synthetic tenant scoped the unchanged artifact. The local synthetic
Spring target had discovery and proof disabled; UI execution used headless
Chromium. Raw tenant identity and connection details are deliberately omitted.

REGISTER: code=OK, lifecycle=DRAFT, handled=0, eligible=0, assisted=0,
safetyFailures=0, scoreBasisPoints=0, eligibleForApproval=false.

Qualification results:
1. SUCCEEDED / CHECKPOINT_VERIFIED / step=8.
2. SUCCEEDED / CHECKPOINT_VERIFIED / step=8.
3. SUCCEEDED / CHECKPOINT_VERIFIED / step=8.
4. SUCCEEDED / CHECKPOINT_VERIFIED / step=8.
5. SUCCEEDED / CHECKPOINT_VERIFIED / step=8.

Provenance qualification: the first verifier incorrectly expected an omitted CLI
field and stopped after one run. Its raw result was not retained; SUCCESS is
corroborated by the journal and the engine's SUCCESS mapping to verified
checkpoint completion. Step 8 follows the unchanged eight-step artifact, rather
than a retained first-run printout. After explicit authorization, exactly four
additional validations completed; each exact CLI success and single appended
SUCCESS event was checked. No compensating success runs were added.

Pre-approval status: lifecycle=DRAFT, handled=5, eligible=5, assisted=0,
safetyFailures=0, scoreBasisPoints=5655, eligibleForApproval=true.

The project author manually executed the explicit APPROVE command. The resulting
APPROVED state and seventh event were independently read back. Stored criteria:
minimumEligibleRuns=5, minimumScoreBasisPoints=5000,
requireZeroSafetyFailures=true. Actor identity is not reproduced.

Exact-artifact UNATTENDED result:
status=SUCCEEDED, disposition=SUCCESS, code=CHECKPOINT_VERIFIED, step=8.
Exactly one SUCCESS observation was appended; lifecycle remained APPROVED,
handled=6, eligible=6, assisted=0, safetyFailures=0, scoreBasisPoints=6096.
Historical approval criteria were unchanged.

A valid JSON copy differed only by trailing whitespace. Its UNATTENDED result:
status=BLOCKED, disposition=POLICY_BLOCK, code=APPROVAL_REQUIRED, step=0.
No event was appended, and the original identity remained APPROVED. There was
no retry or fallback to the approved digest.

The project author manually executed explicit suspension with OPERATOR_WITHDRAWAL.
The final exact-artifact UNATTENDED result was:
status=BLOCKED, disposition=POLICY_BLOCK, code=APPROVAL_SUSPENDED, step=0.
No event was appended. Earlier authorized suspended-denial checks returned the
same result; this final check did not repeat successful UI execution. Denial
returns before browser-owner creation in ReplayEngine; zero browser launches
are also covered by integration tests, not by a retained process trace here.

Final sequence (nine events):
REGISTER -> OBSERVE SUCCESS x5 -> APPROVE -> OBSERVE SUCCESS ->
SUSPEND OPERATOR_WITHDRAWAL.

Final status: lifecycle=SUSPENDED, handled=6, eligible=6, assisted=0,
safetyFailures=0, scoreBasisPoints=6096, eligibleForApproval=false.
approvedUnder: minimumEligibleRuns=5, minimumScoreBasisPoints=5000,
requireZeroSafetyFailures=true. suspensionReason=OPERATOR_WITHDRAWAL.

The target completed graceful shutdown after final verification. No reversal was
submitted. Demonstration processes used clean environments without an OpenRouter
key; no OpenRouter call was made. No screenshots, DOM or page content were
retained. This file excludes raw tenant/actor identities, journal identity hashes,
invocation values, extracted outputs, connection URLs, temporary filesystem paths,
credentials and exception details. The raw journal was not copied into the
repository. Fixed CLI fields and journal contents underwent bounded privacy checks.

The artifact digest and evidence UUID support correlation, not cryptographic
attestation of execution, human identity, environment or network absence. Journal
hashes are pseudonyms, not encryption. The local append-only store provides
neither authentication nor tamper-proof history, execution leases or multi-process
coordination. This summary does not claim a live browser handoff demonstration.
