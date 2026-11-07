# Unified Member Fund Operations

Route: `/admin/contribution-workspace?tab=member-operations`.

The workspace now has five primary entries: Overview, Member Fund Operations, Monthly Records, Yearly Records and Statements. The separate Share Withdrawal & Profit module is unchanged.

## UI refinement (2026-11-03)

This section records the latest refinement. The sections below it retain the original consolidation notes and their earlier verification results.

### Duplicate-rendering investigation

The working source, compiled root templates and prior Spring-rendered snapshots each contained one Member Fund Operations include, one monthly heading and one introductory paragraph. The loaded workspace JavaScript does not clone or inject a financial header. **The duplication described in the screenshot review could not be reproduced in the available code/rendered states; its live-instance root cause remains unconfirmed.** The running page URL was requested for comparison but was not supplied during this implementation.

The fragment now has the explicit name `memberFundOperationsPanel`, and the monthly section, title, description and financial table have unique IDs. This makes the inclusion unambiguous and testable; it is not evidence that the previous generic fragment name caused the reported screenshot problem. No CSS rule hides duplicate headers.

New integration and browser checks assert exactly one section, title and introduction after rendering and JavaScript execution. Nonempty views have one financial table header; empty views have no blank table/header. Checks cover all-employee, selected-employee, filtered, empty, unknown-employee and paginated states. The current verified package consistently renders the section once. A duplicate still seen in a separately running instance needs that instance's URL/rendered HTML to identify its cause.

### Improvements delivered

- Two-row filter design: employee ID/name and year/month in the first group; status, rows and actions in the second. All original fields remain, with responsive wrapping and semantic fieldsets.
- Applying filters provides immediate submission feedback plus a server-rendered applied-context message. Native Enter submission retains name, year, month, status and page size.
- The all-employee view has no member-specific summary or share forms. It provides a short explanation of how to select a member. A valid selection reveals the existing employee summary, portfolio and share actions; empty and invalid states remain explicit.
- Financial headings are shorter, with a single BDT currency label. Monetary cells remain right aligned with two decimal places and tabular figures. Employee links use FundAxis green and readable identification.
- Manage Payment is the primary action. The same Details/Edit links remain inline on desktop and move into a native expandable More Actions menu on mobile. Their destinations are checked against the row's business employee and contribution IDs.
- Numbered pagination shows matching record count, current/total pages, previous/next states, a bounded page window and first/last-page access. All employee/name/period/status/page-size filters remain in links. No financial total is derived from the displayed page.
- Empty results show a concise message and Reset Filters action, without a blank table. Name searches without matching employees have a separate useful message.

### Functionality preserved and verified

Selected-member tests verify OLD and NEW portfolios, allocation access, adjustment/rate forms bound to the selected holding, quantity history, rate history and valuation access. Existing effectivity rules and backend share calculations are unchanged. The full suite exercises share allocation, duplicate protection, adjustment, rate change and historical month boundaries.

Contribution filters, record retrieval, Details and Edit remain available; both full-page destinations returned HTTP 200 in MVC tests. Payment entry/reversal and member-credit receipt, application and reversals remain on their existing services/endpoints and passed the existing integration workflows. ADMIN authorization, CSRF, financial reconciliation and the separate withdrawal workflow were retained.

### Files changed in this refinement

- `src/main/resources/templates/admin/contribution-workspace.html` — explicit member fragment reference; main navigation retained.
- `src/main/resources/templates/admin/member-fund-operations.html` — filter groups, feedback, table presentation, contextual action menu, empty states and numbered pagination.
- `src/main/resources/static/css/member-fund-operations.css` — scoped layout and presentation refinements.
- `src/main/resources/static/js/member-fund-operations.js` — responsive action menus and filter-submit feedback.
- `src/test/java/com/fundaxis/reporting/ContributionReportingIntegrationTest.java` — rendering-count, share-access, Details/Edit and 205-member pagination checks.
- `src/test/js/reporting-browser-check.cjs` — eight UI states at four widths, header/summary counts, filter overlap, action destinations, mobile actions and native keyboard submission.
- `docs/member-fund-operations.md` — this report.

**No new source files, production Java changes, Java classes, database tables or schema changes were required for this refinement. No production financial records were changed.**

### Executed verification

- Focused integration tests: **8 passed**.
- Full Maven `verify`: **45 tests reported, 44 passed, 0 failures/errors, 1 opt-in SQL Server test skipped**. Includes 29 reporting/workspace tests, 14 withdrawal tests and the application context test.
- Pagination exercised **205 matching members**, with first/middle/last pages, 10 rows per page and five rows on the final page; filters and 21-page navigation were retained.
- Headless Chrome: **32 page/state/viewport checks passed** across eight views at 1440, 1280, 768 and 390 pixels. No duplicate monthly headings/introduction, page-wide horizontal overflow, overlapping filter controls or JavaScript exceptions were found.
- Additional browser checks passed for all four drawers at desktop/mobile widths, focus and Escape dismissal, mobile More Actions, correct row destinations, rendered CSRF tokens, stale-context protection, quantity validation, filter preservation and native Enter submission.
- Desktop all-employee and mobile paginated screenshots were visually inspected.
- Working source and the verification source copy matched by SHA-256. The runnable package contains the refined template/CSS/JavaScript: `target/fundaxis-member-operations.jar`.

Build log: `target/member-operations-refinement-build.log`. Focused log: `target/member-operations-refinement-focused.log`. Browser evidence: `target/ui-audit/member-operations/browser-results.json` and adjacent screenshots.

Tests used isolated H2 fixtures and actual Spring-rendered snapshots served through a local browser fixture server. **Live SQL Server integration, a production deployment and the reported duplicate in the user's running instance were not verified.** Running applications must use the rebuilt resources/package to show these refinements. The regression suite covers the selected reporting and withdrawal workflows; it does not constitute exhaustive testing of unrelated Loan or Employee modules.

## Interface and preserved workflows

One shared context bar controls business Employee ID, optional employee-name filter, reporting year/month, contribution status and page size. Leaving Employee ID blank shows all matching monthly records. Name searches also offer at most ten employees for explicit selection; the system never silently picks a name match.

After selecting a member, the workspace shows verified employee information, share quantities effective for the selected contribution month and expected contribution, stored obligations, net direct cash, applied member credit and outstanding balance. Current portfolio quantities/rates are clearly distinguished from period figures. Merely viewing a month never generates dues or records money.

Allocation, quantity adjustment, monthly-rate change and payment entry use focused Bootstrap offcanvas drawers. Existing field names, financial services, CSRF tokens, confirmations and submission guards are retained. Holding valuation/reference information, share audit tables and payment reversals remain accessible in expandable details. Member-credit operations load on demand and remain separate from contribution cash payments.

An initial share allocation may be effective in the past, today or a future date, subject to the employee's fund membership dates. A future allocation is recorded in the existing effective-dated share transaction history with zero current quantity until its effective date. Share quantity/rate changes apply to the full calendar month containing their effective date (no proration); a current-month future allocation remains scheduled until effective. Existing stored contribution obligations are never regenerated or overwritten by this calculation change. Future dates remain disallowed for payment, adjustment and rate-change actions under their current rules.

| Existing feature | Unified location |
| --- | --- |
| Employee ID lookup and verified membership information | Shared context and employee summary |
| Employee-name payment filter | Shared name filter, with explicit member candidates |
| OLD/NEW holdings and holdings filtering | Current Share Portfolio |
| Current quantities, monthly rates and contribution estimates | Portfolio table |
| Share value per share, total value and holding reference | Expandable portfolio valuation/reference table |
| Allocate shares and effective-date/value/rate inputs | Allocate Shares drawer |
| Quantity adjustments, effective date and reason | Holding action and adjustment drawer |
| Monthly-rate changes, effective date and reason | Holding action and rate drawer |
| Quantity transactions and rate history | Selected-holding expandable audit sections |
| Contribution year/month, employee and status filters | Shared context bar |
| All-employee monthly contributions and payments | Combined operational register with database pagination |
| Contribution details and edit | Register row actions, existing full-page workflows |
| Manage Payment | Contextual row action and payment drawer/details |
| Receive direct payment | Existing payment service and payment-entry drawer |
| Payment history and reversal | Expandable payment history and existing reversal forms |
| Additional collections | On-demand Member Credit & Additional Collections |
| Credit history, balances, applying credit and reversal | Existing member-credit forms and ledger, loaded on demand |
| Monthly generation and manual entry endpoints | Preserved; opening the register never invokes generation |

## Backend and route integration

No new Java files, entities, tables, dependencies or financial calculation engine were created. Existing services remain authoritative for shares, effective dates, contribution obligations, payments, reversals and member credits.

- `tab=shares`, `tab=contributions` and `tab=payments` resolve to the unified workspace.
- Legacy `recordEmployeeId`, `recordStatus`, `payEmployeeId`, `payEmployeeName` and `payStatus` parameters remain understood.
- A contribution-specific legacy link derives its actual employee/month when no explicit context was supplied. A conflicting explicit employee/month is rejected rather than carried into another member's work.
- Existing POST URLs and public API endpoints are retained. Unified forms preserve selected employee, month and status after submission; credit actions reopen their ledger context.
- Server validation rejects a holding that belongs to another employee, stale payment/member context, invalid periods/status/page settings and unknown employees.
- Existing ADMIN authorization and CSRF remain enabled. Client-side context edits disable stale financial forms until the server loads the new selection.

Monthly contributions are filtered and paginated in the database, with fixed safe employee-ID ordering and page sizes 10/25/50/100. Payment and applied-credit data are fetched in batches for the displayed page using the existing canonical reporting-line calculation. No page subtotal is presented as a whole-dataset total. Detailed share histories load only for a selected holding; member-credit history loads only when requested.

Net cash excludes reversed payments; member-credit allocations remain separate. Outstanding follows the shared reporting logic, including cancellation and reconciliation checks. Share history retains the established first-day-of-month rule, and existing generated obligations are not rewritten.

## Files changed for this task

Modified Java:

- `src/main/java/com/fundaxis/controller/admin/AdminContributionWorkspaceController.java` — unified routing/context, view model, existing action integration and validation.
- `src/main/java/com/fundaxis/repository/ContributionRepository.java` — bounded employee/month lookup and paginated filtered operational query.
- `src/main/java/com/fundaxis/repository/EmployeeRepository.java` — bounded name-search support through existing repository.
- `src/main/java/com/fundaxis/service/ContributionReportingService.java` — batch operational-page adapter to existing financial line calculations.

Modified HTML: `src/main/resources/templates/admin/contribution-workspace.html` — five main navigation entries and unified-fragment integration. Old duplicated search/layout panels were replaced after preserving their nine unique share/payment/credit write actions in the new fragment.

New frontend files, exactly three:

- `src/main/resources/templates/admin/member-fund-operations.html`
- `src/main/resources/static/css/member-fund-operations.css`
- `src/main/resources/static/js/member-fund-operations.js`

Modified existing tests:

- `src/test/java/com/fundaxis/reporting/ContributionReportingIntegrationTest.java`
- `src/test/js/reporting-browser-check.cjs`

This report is `docs/member-fund-operations.md`. Other pre-existing working-tree changes were preserved and are not attributed to this consolidation.

## Validation evidence and limits

Build and browser evidence is retained under `target/member-operations-*.log` and `target/ui-audit/member-operations/`.

Executed results:

- Full Maven `verify`: **43 tests reported, 42 passed, zero failures/errors, one opt-in SQL Server test skipped**. This includes 27 reporting/workspace tests, 14 withdrawal tests and the application context test.
- After final rendering, stylesheet-order and legacy status-normalization adjustments, focused Maven `verify` on the final workspace source: **all six unified-workspace integration tests passed**, and the executable package was rebuilt successfully.
- The focused tests exercise legacy tab/filter aliases, five-tab navigation, separate cash/credit totals, paginated all-employee operations, name/status filtering, invalid parameters, member/payment context isolation, reconciliation error rendering, authorization, CSRF, duplicate allocation, share allocation/adjustment/rate changes, payment receipt/reversal and all four member-credit write workflows.
- Actual MVC snapshots cover employee, all-employee, selected-holding and member-credit views. Headless Chrome checked all four at 1440, 1280, 768 and 390 pixels: **16 page/viewport checks passed**, with no page overflow or JavaScript exceptions.
- All four drawers opened and closed with Escape at desktop and mobile sizes, with focus in the drawer. Quantity preview/validation, stale-context blocking and rendered CSRF tokens were checked. Browser screenshot capture waits for drawer transitions; browser cleanup has a bounded wait.
- The final desktop view and mobile payment drawer were visually inspected. Screenshots contain isolated test records, not production financial figures.
- A static inventory confirmed that all nine original share/payment/member-credit write form actions and their named inputs were preserved; evidence is `target/member-operations/action-inventory.json`.
- SHA-256 comparisons confirmed the verification workspace source matches the working source. Package contents include the new fragment, CSS and JavaScript. Runnable artifact: `target/fundaxis-member-operations.jar`.

Commands executed from the exact source copy at `target/withdrawal-verification-workspace`:

```powershell
.\mvnw.cmd -B -o '-Dmaven.repo.local=C:/Users/This/.m2/repository' '-Dspring.profiles.active=reporting-test' verify
.\mvnw.cmd -B -o '-Dmaven.repo.local=C:/Users/This/.m2/repository' '-Dspring.profiles.active=reporting-test' '-Dtest=ContributionReportingIntegrationTest#unified*' verify
```

Browser command from the project root:

```powershell
node src/test/js/reporting-browser-check.cjs member-operations
```

Full-suite log: `target/member-operations-build.log`. Final focused build log: `target/member-operations-final.log`. Browser evidence: `target/ui-audit/member-operations/browser-results.json` and adjacent screenshots. Existing Maven configuration/dependency versions and Java target were preserved; no dependency upgrade was performed.

The database tests use isolated H2 fixtures. Browser checks use actual Spring-rendered test snapshots, local fixture serving and real headless Chrome. They do not authenticate against or modify production. Live SQL Server query execution and a full manual acceptance pass on production data remain unverified. Existing complex contribution Details/Edit pages remain full-page workflows. No claim is made that every unrelated Loan, Employee or other module was exhaustively tested.

## Payment drawer and member-credit refinement

This follow-up keeps the existing workspace and financial endpoints. The payment drawer now shows the selected employee/month and the canonical contribution due, net cash received, applied member credit, and outstanding amount. “Pay Full Outstanding” copies the server-rendered decimal amount into the payment input only. The payment date defaults to Bangladesh's current date, allows prior dates, and is capped at today. The form has one scroll area and a persistent action footer.

Member Credit now starts with an available-balance summary, a bounded recent-collection list, and contextual actions. The full ledger and allocation history are shown only when requested. Apply, collection reversal, allocation reversal, and payment reversal reuse the existing write endpoints through one bound form per action; the selected employee/contribution and CSRF context are retained. Ledger rows no longer contain editable financial forms. Reversals retain confirmation, reason/date validation, existing eligibility restrictions, and audit history. Reversed cash is explicitly marked as excluded from net receipts.

The available-credit balance uses a database aggregate over the same existing eligible credit statuses instead of loading the member's full ledger. Recent collection display is capped at three records. Contribution balances, payment limits, and credit allocations remain calculated and validated by the existing reporting and service logic; no client-side settlement formula was added.

A regression test also caught a stale persistence-context case: if a contribution was loaded as `PENDING`, then canceled in a separate transaction, a subsequent payment attempt could inspect the old in-memory status. The payment service now refreshes the contribution after obtaining its pessimistic row lock. The test checks both rejection and the expected rollback of that transaction. This does not alter contribution or payment records outside the isolated H2 tests.

No new Java classes, entities, tables, or dependencies were added. Relevant files for this follow-up:

- `src/main/java/com/fundaxis/service/ContributionPaymentService.java`
- `src/main/java/com/fundaxis/service/MemberCreditService.java`
- `src/main/java/com/fundaxis/repository/MemberCreditRepository.java`
- `src/main/java/com/fundaxis/controller/admin/AdminContributionWorkspaceController.java`
- `src/main/resources/templates/admin/member-fund-operations.html`
- `src/main/resources/static/css/member-fund-operations.css`
- `src/main/resources/static/js/member-fund-operations.js`
- `src/test/java/com/fundaxis/reporting/ContributionReportingIntegrationTest.java`
- `src/test/js/reporting-browser-check.cjs`

Verification for this follow-up:

- Full Maven `verify` on the final source copy: **49 tests, zero failures/errors, one opt-in SQL Server smoke test skipped**. The full suite includes the 13 payment/member-operations reporting tests and 14 share-withdrawal regressions.
- Focused payment/member-operations run: **13 tests passed**. The stale canceled-contribution test also passed alone after explicitly asserting the expected transaction rollback.
- Headless Chrome: **34 page and interaction checks passed** across desktop, tablet, and mobile layouts; no JavaScript exceptions. Checks include settlement balances, pay-full non-submission, CSRF, drawer footer, contextual credit action binding/limits, reversal eligibility, responsive page overflow, and existing share drawer behavior.
- `node --check` passed for the browser harness. The copied verification source tree matched all **238 source files** by SHA-256.
- Runnable artifact: `target/fundaxis-member-operations.jar`.

H2 and browser fixtures are isolated; the skipped opt-in check means live SQL Server query execution and production manual acceptance remain unverified. No production financial data was changed. Existing form posts still use the application's standard POST/redirect flow, which refreshes the resulting financial state; opening drawers and expanding the ledger do not navigate away or reload the page.
