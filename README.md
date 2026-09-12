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

This increment implements the target only. Discovery/model integration,
capability artifacts, deterministic replay, runtime policy enforcement, human
handoff, authentication and session expiry are not implemented yet. Browser tests
are test evidence, not LLM discovery evidence. No API key is needed for this stage.
