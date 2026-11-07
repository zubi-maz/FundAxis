# Dedicated Yearly Records Excel export service

Implemented on 2026-11-03.

## Architecture and integration

Created `src/main/java/com/fundaxis/service/excel/YearlyContributionRegisterExcelService.java`.
Its `export(...)` method exclusively builds the Yearly Records workbook from the
existing `ContributionReportingService.AnnualReport` and `AnnualDetail` data.
Financial eligibility, cash/reversal/credit rules, annual statuses and collection
rates remain in the existing reporting layer; no financial calculation engine,
repository, entity, table or dependency was added.

The controller now constructor-injects `YearlyContributionRegisterExcelService` and
calls it from the unchanged protected route:

`GET /admin/contribution-workspace/yearly-records/export`

Request parameters remain `year`, `employeeId`, `employeeName` and `status`.
The Yearly Records button URL and frontend require no changes. Downloads use the
requested filename convention: `FundAxis_Yearly_Contribution_Records_<year>.xlsx`.
ADMIN protection, safe errors, content type and no-store response headers remain.

The yearly-specific `exportAnnual(...)` method was removed entirely from
`MonthlyContributionRegisterExcelService`. That service retains its original
monthly and statement generation methods. Their bodies were compared with the
pre-refactor source and are unchanged. Statement export was outside this task
and was deliberately retained in its existing location.

Only common POI presentation helpers and the existing nested `Look` style class
were made package-private for reuse. The yearly service does not inject the monthly
service or call a monthly workbook-generation method. This avoids copying the
formatting system or adding an unnecessary Java utility class.

## Preserved workbook behavior

1. **Annual Summary:** selected year, timestamp, applied filters, employee counts,
   expected obligations, net cash received, applied credit, total settled,
   outstanding and cash collection rate.
2. **Employee Annual Register:** business IDs, employee details, financial figures,
   statuses and complete filtered totals, using `allRows()` rather than the current page.
3. **Monthly Breakdown:** twelve months per matching employee, expected/cash/credit/
   outstanding values and actual/missing/canceled month statuses.

Existing column headings, numeric monetary types, two-decimal BDT formatting,
widths, styles, totals, frozen/repeated headers, auto-filters and print setup remain.
No formulas or business rules were introduced or removed. As before, totals use
reporting-service BigDecimal values; numeric conversion is only for Excel cells.

## Source changes

### Created

- `src/main/java/com/fundaxis/service/excel/YearlyContributionRegisterExcelService.java`
  - The single new Java class explicitly requested; owns yearly workbook generation.
- `docs/yearly-excel-service-extraction.md` - this delivery report.

### Modified

- `src/main/java/com/fundaxis/service/excel/MonthlyContributionRegisterExcelService.java`
  - Removed yearly generation; reused common presentation helpers through package access.
- `src/main/java/com/fundaxis/controller/admin/AdminContributionWorkspaceController.java`
  - Injected/called the yearly service and updated the download filename.
- `src/test/java/com/fundaxis/reporting/ContributionReportingIntegrationTest.java`
  - Existing annual tests now call the dedicated service. Added real endpoint checks
    with a Spring Mockito spy, combined filters, alternate year, empty data and workbook inspection.

`AdminContributionExcelController`, reporting services/repositories/DTOs, monthly
export implementations, UI assets, security configuration and database schema are unchanged.
The separately existing `/admin/contributions/export/year` share-report route remains untouched.

## Executed validation

- **Maven verify: PASS.** 23 tests reported; 22 passed, 0 failures/errors,
  1 opt-in SQL Server test skipped. Includes the original application startup/context test.
- **Dependency injection and invocation: PASS.** The real Spring yearly service is
  spied in the integration test; the existing export endpoint invokes it with the
  selected year and employee/status filters. Compiled controller bytecode also
  confirms the yearly call targets the dedicated service; monthly calls remain monthly.
- **Year/filter checks: PASS.** Exported 2026 with combined EMP18/Amina/PARTIAL filters,
  verified its filename/summary/employee rows and reconciled money with the same UI report.
  A 2025 export has 2025 metadata and zero eligible financial obligations. An empty
  name-filter result exports a valid zero-total workbook.
- **Complete dataset: PASS.** Existing pagination regression exports all 26 filtered
  employees and their 312 month rows while the visible page contains only 10 employees.
- **Financial regression: PASS.** Existing tests cover multiple/partial payments,
  pending/canceled/missing obligations, reversed payments, applied/reversed credits,
  fractional amounts, annual/monthly/statement/API reconciliation and Excel totals.
- **Monthly exports: PASS.** Existing Single Month and Monthly Register HTTP exports
  still download XLSX files and reopen through Apache POI. Statement exports also pass.
- **Workbook preservation: PASS.** Compared a saved pre-refactor yearly workbook with
  a newly generated workbook using the same isolated fixtures. Worksheet names,
  every non-timestamp cell/value/type/style, column widths, print setup, auto-filters
  and frozen headers match. Generation-timestamp metadata rows were intentionally excluded.
- **Package inspection: PASS.** Both yearly and monthly service classes are present,
  tested main source/resources match the working tree, and test fixtures/H2 are excluded.
- **Scoped git diff --check: PASS.**

Verification used an isolated source/build directory under
`target/yearly-export-verification-workspace`, avoiding previously observed interference
with the root `target/classes` output. The source was compared against the workspace;
this does not substitute a different implementation.

## Artifacts

- Build log: `target/yearly-service-refactor/maven-verify.log`.
- Test workbook: `target/yearly-service-refactor/FundAxis_Yearly_Contribution_Records_2026_TEST_FIXTURES.xlsx`.
- Reviewed package: `target/yearly-service-refactor/fundaxis-yearly-excel-service.jar`.
- Test reports: `target/yearly-export-verification-workspace/target/surefire-reports/`.

The workbook contains safe test fixtures, not production employee financial records.

## Remaining limits

No known extraction defects remain. Live SQL Server and a live authenticated browser
download were not verified in this session; the existing SQL Server credentials were
not available. The actual Spring MVC export route, service invocation and resulting
workbooks were verified using isolated H2 fixtures. No production financial data
or schema was modified.
