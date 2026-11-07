# FundAxis: Yearly Records and Statements

## Implemented behavior

Both screens remain inside `/admin/contribution-workspace`, with the original
`tab=yearly-records` and `tab=statement` parameters. Existing shell, navigation,
first five tabs, dependencies, security configuration and financial write paths
are preserved. No production entity, table or database schema was added or changed.
The actual Maven Java release remains 17, as configured in the existing pom.xml.

### Yearly Records

- Calendar-year selector and six summary cards.
- Combined business Employee ID, employee name and derived annual-status filters.
- Database pagination (10/25/50/100), validated sorting and deterministic employee-ID tie-breaks.
- Distinct employee register including employees without eligible obligations.
- Complete filtered summaries and twelve-month expected-versus-cash chart; native
  HTML meters avoid an additional chart dependency or client-side money calculations.
- Bootstrap employee-detail modal with all twelve months, including NO RECORD,
  CANCELED, PENDING, PARTIALLY PAID and PAID.
- Three-sheet annual XLSX: Annual Summary, Employee Annual Register, Monthly Breakdown.
  All filtered employees and months are exported, independently of the current page.

### Statements

- Existing admin employee lookup validates business IDs and shows the employee name.
- Annual and custom reporting periods; custom ranges must comprise complete calendar
  months, because the existing accounting system stores obligations by contribution month.
  Ranges are limited to the existing 120-month reporting limit; invalid dates are rejected.
- Employee ID/name/designation, fund joining/exit dates when recorded, period and Dhaka timestamp.
- Expected, cash received, credit applied, total settled and outstanding; successful
  payment, pending obligation and reversal counts.
- Separate obligation and linked-payment tables: each obligation is counted once.
  Payment reference/method/date and original reversed amount/date are retained for audit.
- Two-sheet XLSX containing employee metadata, numeric financial totals, all obligation
  months and linked payment history. Filenames use sanitized business IDs and periods.
- Scoped native print layout with A4 landscape paper, repeating column headings,
  page-break rules and exclusion of sidebar, forms, workspace controls and account menu.
  Browser Print / Save as PDF is offered explicitly. There is no server PDF endpoint
  or button claiming a generated PDF download.

## Accounting basis

The existing ContributionReportingService member/line/payment logic is reused.
Annual database aggregates implement the same tested settlement rules, independently
aggregating payments and credit allocations before joining to avoid cross multiplication.

- Expected = stored eligible obligations; CANCELED obligations contribute zero.
- Cash received = non-reversed direct payments on those obligations.
- Applied credit = non-reversed allocations, distinct from new cash receipts.
- Total settled = cash + applied credit; outstanding = expected - total settled.
- SQL aggregates are cast to decimal(34,2) before monetary arithmetic to retain cents
  under [SQL Server precision/scale rules](https://learn.microsoft.com/en-us/sql/t-sql/data-types/precision-scale-and-length-transact-sql).
- Cash collection rate = cash / expected x 100, with zero-safe BigDecimal division.
  No artificial 100% cap is added.
- Reports use contribution-period attribution with current settlement state. A payment
  made in a later year still settles its original contribution month. These reports
  are not receipt-date cash-flow statements or historical as-of snapshots.
- Canceled contributions with active settlement, negative settlement and over-settlement
  are rejected for reconciliation instead of hiding financial anomalies.
- Annual PAID means eligible obligations exist and outstanding is zero; PARTIAL means
  some settlement and some outstanding; PENDING means outstanding without settlement;
  NO_RECORDS means no eligible obligations, including canceled-only histories.
- Total Employees is the number of distinct employees matching all filters. Active
  Contributors counts currently ACTIVE employees in that result with eligible annual
  obligations; it is explicitly not a historical active-membership assertion.
- No opening/closing capital, profit, historical share balances or bank approvals are fabricated.

## Query and security behavior

Annual screen requests retrieve database aggregates and one employee page, not every
employee's payment history. Employee details are loaded on demand. Exports load only
the selected calendar year's obligations, and payment/credit IN queries are batched
in groups of 500 to stay below SQL Server parameter limits. Member statements use a
bulk credit-allocation lookup. Existing reporting payment queries prefetch the linked
contribution to avoid a per-payment lazy-fetch query.

All added routes live under the existing admin controller and `/admin/**` security
rules and retain its ADMIN method authorization. Export responses use `Cache-Control:
no-store`; inputs use allowlists and bound repository parameters. Export IDs are
sanitized only for filenames, never changed for employee lookup. No CSRF or authentication
settings were weakened.

## Modified existing files

- `src/main/java/com/fundaxis/controller/admin/AdminContributionWorkspaceController.java`
- `src/main/java/com/fundaxis/service/ContributionReportingService.java`
- `src/main/java/com/fundaxis/service/MemberCreditService.java`
- `src/main/java/com/fundaxis/repository/ContributionReportingRepository.java`
- `src/main/java/com/fundaxis/repository/ContributionPaymentReportingRepository.java`
- `src/main/java/com/fundaxis/service/excel/MonthlyContributionRegisterExcelService.java`
- `src/main/resources/templates/admin/contribution-workspace.html`

Existing workspace JavaScript and shared CSS are unchanged. Existing user modifications
in the working tree have been preserved; the repository already contained numerous
staged and unstaged edits before this implementation.

## Created files

- `src/main/resources/templates/admin/contribution-yearly-records.html`
- `src/main/resources/templates/admin/contribution-statements.html`
- `src/main/resources/static/css/yearly-records.css`
- `src/main/resources/static/css/statements.css`
- `src/main/resources/static/js/yearly-records.js`
- `src/main/resources/static/js/statements.js`
- `src/test/java/com/fundaxis/reporting/ContributionReportingIntegrationTest.java`
- `src/test/java/com/fundaxis/reporting/SqlServerReportingSmokeTest.java`
- `src/test/resources/reporting-schema.sql`
- `src/test/resources/application-reporting-test.properties`
- `src/test/resources/reporting-startup-fixtures.sql`
- `docs/fundaxis-contribution-reports.md`

No new production Java class/file was created. Nested reporting records and repository
projections stay in existing classes. The new Java integration test class verifies
financial reconciliation, actual Thymeleaf rendering, protected routes and real XLSX
contents against isolated database fixtures. The second, opt-in Java test class verifies actual SQL Server reporting through a database-only context with no startup seeders or DDL.

The test-only SQL fixture compensates for a pre-existing Employee mapping that declares
unique(email) without a mapped email field. It does not alter production Employee.java
or the SQL Server schema. Startup account/role seeders are mocked only in the new
reporting tests so reporting fixtures remain isolated and production defaults are not required.

The test-only `reporting-test` profile supplies the core roles and EMP003 required by
existing startup seeders, so the original context-load test can run on H2 without
changing, skipping or disabling its startup functionality. These fixtures are never
loaded in production and contain no financial transactions. The original startup
test uses standard H2 nullable-unique semantics for unlinked admin/support accounts;
the reporting integration tests explicitly retain H2 MSSQLServer compatibility mode.

## Reproducing verification

```powershell
.\mvnw.cmd -B '-Dspring.profiles.active=reporting-test' verify
.\mvnw.cmd -B '-Dtest=SqlServerReportingSmokeTest' '-Dfundaxis.sqlserver.smoke=true' test
node --check src/main/resources/static/js/yearly-records.js
node --check src/main/resources/static/js/statements.js
```

The second command intentionally uses the existing configured SQL Server. It is
opt-in, read-only, and loads only the reporting/credit services and repositories,
with schema operations and all application startup seeders excluded.

## Verification

### Implemented and tested

- Main Java compilation succeeds with the existing release-17 configuration on the
  available JDK 26; no dependency or release version was changed.
- Full Maven `verify` succeeds using the isolated `reporting-test` profile: 14 tests
  reported, 13 executed successfully, 0 failures/errors, 1 opt-in SQL Server test skipped.
  This includes the original application context/startup test with its account seeders
  enabled and 12 reporting integration tests.
- Real Spring MVC/Thymeleaf responses render Yearly Records, Statements, employee modal
  fragments, validation errors, and all five existing workspace tabs successfully.
- Reconciliation covers multiple/partial payments, a payment in the following year,
  reversals, canceled obligations, applied and reversed credit allocations, pending-only
  members, no-history members, fractional amounts, monthly chart sums and annual totals.
  It compares the actual Monthly Records controller model, reporting service and statements.
- Combined ID/name/status filters, escaped search wildcards, all permitted sort fields,
  database pagination, zero-safe collection rates and invalid input handling are tested.
- Annual exports contain all filtered employees beyond page one and all twelve months
  per employee. POI reopens the actual generated workbooks and verifies numeric money,
  summaries, linked payments and totals; protected download endpoints return real XLSX.
- Anonymous/non-admin requests cannot access the added reporting/detail/export routes.
- Reporting leaves persisted payment transaction counts unchanged in the integration test.
- Both added JavaScript files pass `node --check`; scoped modified files pass `git diff --check`.

- Final targeted Maven `verify` after the Excel filter-range correction also succeeds:
  all 12 reporting integration tests pass, including the actual statement auto-filter
  range and numeric cells. The corrected JAR is rebuilt successfully.
- The packaged `target/fundaxis-0.0.1-SNAPSHOT.jar` is inspected: both HTML fragments,
  both CSS files, both JavaScript files and workspace inclusion references are present.
  Test classes, test SQL fixtures, the test datasource profile and H2 are excluded.

### Implemented but not verified against the live environment

- The opt-in SQL Server test was actually attempted using the existing configuration.
  The server rejected login because this session does not provide `DB_USERNAME` or
  `DB_PASSWORD`; the unresolved username placeholder was sent by the existing datasource
  configuration. Consequently live SQL Server queries, production-data reconciliation,
  runtime startup using production credentials and production-scale performance are
  **not verified**. No schema initialization or account seeders are loaded by that test.
- Browser visual/responsive inspection, interactive employee lookup and modal clicks,
  physical printing and the browser's Save as PDF output require manual browser checks.
  Their server-rendered content and JavaScript syntax are verified as described above.
- Unrelated loan/repayment/account workflows and legacy exports were not exercised
  end to end; their implementation and routes were preserved.

### Not implemented

- Server-generated PDF downloads. No suitable existing PDF dependency is present;
  functional Excel and native Print / Save as PDF are provided without a fake PDF endpoint.
- Historical opening/closing share or capital balances, profit figures and as-of cash-flow
  accounting are not asserted. Share-category filters are omitted because stored annual
  contribution obligations are employee-level and cannot reliably be attributed to OLD
  versus NEW holdings merely from current share quantities.

### Build evidence

- `target/reporting-full-build.log`: successful complete Maven verify and original startup test.
- `target/reporting-final-verification.log`: final reporting/Excel verification and packaging.
- `target/sqlserver-reporting-smoke.log`: attempted SQL Server connection and missing-credential failure.
- `target/surefire-reports/`: automated test details.

