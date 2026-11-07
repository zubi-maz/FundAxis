# Share Withdrawal & Profit Allocation — initial working version

Implemented workspace: `/admin/share-withdrawal-profit`, with Overview, Share Withdrawals, Profit Allocation, and History & Reports. Existing Contribution workspace tabs were preserved.

## Delivered behavior

- Business Employee ID lookup loads verified employee information, membership dates, OLD/NEW holdings, reservations and available quantities. Recorded holding values are explicitly not redemption prices.
- Compact responsive request form validates positive integer quantities, membership, holding ownership and available shares on the server. Submission creates PENDING requests without deducting shares or assigning monetary amounts.
- UUID submission tokens provide retry safety. Audit identities come from authentication; submitted usernames, statuses and monetary values cannot control the workflow.
- Approval, rejection, maker cancellation and separate share posting are implemented behind policy gates. Approval/rejection require an independent administrator; unknown legacy makers cannot be silently approved.
- Pending and approved unposted requests reserve shares. Rejected/cancelled requests release reservations; posted requests are not subtracted twice.
- Posting locks the holding and refreshes locked state, validates the request version and records one DECREASE transaction with before/after quantities. The withdrawal has a unique direct transaction relationship. Repeated posting does not deduct twice.
- Generic share reductions also respect reservations when the module is enabled. Existing first-day-of-month historical quantity/rate calculations remain in use; existing contribution obligations are not rewritten.
- Paginated history supports business Employee ID, request-date range, category, status and allowlisted sorting. Details show actual lifecycle timestamps and actors.
- Overview prioritizes pending work, quick actions and recent requests. Unavailable money remains unavailable rather than being invented as zero.
- Profit Allocation explains missing policy and accounting prerequisites. It does not calculate or post fictional profit. No nonfunctional export or settlement buttons were added.

## Production activation and outstanding policy

The application properties deliberately remain:

```properties
fundaxis.withdrawals.enabled=false
fundaxis.withdrawals.approval-enabled=false
fundaxis.withdrawals.posting-enabled=false
```

Review and apply the manual schema script to the actual SQL Server schema before enabling requests. Confirm reservation/membership policy and any loan or credit eligibility checks first. Enable approval only after confirming independent-review authority. Enable posting only after confirming the separate posting event, effective date and authority. Posting currently uses the current Dhaka date, not an administrator-supplied historical date.

Monetary disbursement is intentionally blocked even if these flags are enabled. Approved redemption valuation, deductions, loan/credit treatment, payment confirmation and settlement authorization are still required. Current holding value is not treated as a redemption price. Profit eligibility, allocation period, formula, OLD/NEW treatment and accounting persistence also remain unapproved. Withdrawal Excel reports are deferred until reliable financial integration, as requested in the phased delivery.

## Database

Reused Employee, ShareHolding, ShareTransaction, ShareRateHistory and ShareWithdrawal. No duplicate financial entity or speculative profit table was introduced.

`src/main/resources/db/manual/share-withdrawal-workflow.sql` is manual and has **not** been executed. It adds eight audit/link columns to the existing withdrawal table, a transaction foreign key, filtered unique indexes and a reservation index. If the already-mapped withdrawal table does not yet exist, it creates that table. It does not rewrite historical records or fabricate legacy audit identities. Production Hibernate remains `ddl-auto=none`.

Transactions, holding locks, refreshed state, optimistic versions, unique request tokens and unique transaction links protect financial transitions. H2 concurrency tests passed; SQL Server locking and migration still require verification on a safe SQL Server database.

## File inventory

Three new production Java files, each with a separate responsibility:

- `src/main/java/com/fundaxis/controller/admin/AdminShareWithdrawalProfitController.java` — workspace, employee lookup, validated actions and safe feedback.
- `src/main/java/com/fundaxis/service/ShareWithdrawalService.java` — withdrawal lifecycle, reservations, audit and transactional posting.
- `src/main/java/com/fundaxis/repository/ShareWithdrawalRepository.java` — persistence, filtered history, aggregates and reservation queries.

Modified existing production Java:

- `src/main/java/com/fundaxis/entity/ShareWithdrawal.java`
- `src/main/java/com/fundaxis/repository/ShareHoldingRepository.java`
- `src/main/java/com/fundaxis/service/ShareService.java`

New frontend and setup files:

- `src/main/resources/templates/admin/share-withdrawal-profit.html`
- `src/main/resources/static/css/share-withdrawal-profit.css`
- `src/main/resources/static/js/share-withdrawal-profit.js`
- `src/main/resources/db/manual/share-withdrawal-workflow.sql`

Modified configuration: `src/main/resources/application.properties` (documented opt-in flags only).

Tests: new `src/test/java/com/fundaxis/withdrawal/ShareWithdrawalIntegrationTest.java`; modified `src/test/java/com/fundaxis/reporting/ContributionReportingIntegrationTest.java` and `src/test/js/reporting-browser-check.cjs`. Existing yearly export assertions now locate financial columns/rows semantically so they continue checking numeric amounts and complete datasets after the existing workbook layout changes. Yearly/monthly exporter production classes were not changed for this task.

Navigation-only additions to existing `src/main/resources/templates/admin/` files:

`add-excel-employee.html`, `contribution-details.html`, `contribution-form.html`, `contribution-workspace.html`, `contributions.html`, `dashboard.html`, `employee-details.html`, `employee-edit-form.html`, `employee-form.html`, `employees.html`, `loan-details.html`, `loan-disbursements-details.html`, `loan-disbursements-form.html`, `loan-disbursements.html`, `loan-extension-details.html`, `loan-extension-form.html`, `loan-extensions.html`, `loan-form.html`, `loans.html`, `repayment-details.html`, `repayment-form.html`, `repayment-schedule-details.html`, `repayment-schedule-form.html`, `repayment-schedules.html`, `repayments.html`, `role-details.html`, `role-form.html`, `roles.html`, `user-account-details.html`, `user-account-form.html`, `user-accounts.html`.

Their duplicated existing sidebars received the same new module link; unrelated page behavior was not redesigned.

## Executed verification

Maven verify succeeded using an isolated exact source copy under `target/withdrawal-verification-workspace` to avoid interference with the root build output. SHA-256 comparisons confirmed the copied source matches the working source.

```powershell
.\mvnw.cmd -B -o '-Dmaven.repo.local=C:/Users/This/.m2/repository' '-Dspring.profiles.active=reporting-test' verify
```

- 37 tests reported: **36 passed, 0 failures/errors, 1 opt-in SQL Server test skipped**.
- Includes 14 new withdrawal integration tests, 21 existing contribution reporting tests and the application context test.
- Executed withdrawal checks cover OLD/NEW requests, retry safety, invalid quantities/membership/ownership/version, independent approval, cancellation/rejection, reservations, generic adjustment protection, posting audit and idempotency, concurrent requests/posts, historical month-boundary contribution behavior, CSRF/role protection, forged request fields, policy gates, MVC templates, employee lookup and history filters.
- Existing reporting suite exercises contribution reporting and exports. This is selected regression coverage, not a claim that every unrelated financial module was tested.
- Headless Chrome checked all four tabs at 1440, 1280, 768 and 390 pixels: **16 page/viewport checks passed**, no page overflow or JavaScript exceptions. Quantity validation, clearing stale employee details, AJAX lookup and duplicate-submit guard passed.
- Browser checks used actual Spring-rendered isolated H2 snapshots with local fixture endpoints, not a live production login. Screenshots contain test data and test-enabled policies. Desktop overview and mobile withdrawal screenshots were visually inspected.
- Runnable package contents checked for all three classes and three frontend assets. Build artifact: `target/fundaxis-withdrawal-initial.jar`.

Evidence: `target/withdrawal-module-build.log`, `target/ui-audit/withdrawal/browser-results.json`, and screenshots in `target/ui-audit/withdrawal/`.

## Not verified / intentionally unavailable

Live SQL Server connection, SQL Server migration/locking behavior, production authentication workflow, complete unrelated-module regression, production financial reconciliation and real monetary disbursement were not verified. No production financial data was changed. Monetary settlement, profit calculation/posting and withdrawal Excel exports remain unavailable pending the business and integration requirements described above.
