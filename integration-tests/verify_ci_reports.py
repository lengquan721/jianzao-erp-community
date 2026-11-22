"""Gate a CI Maven phase on fresh, scoped Surefire XML evidence."""
from __future__ import annotations

import argparse
from pathlib import Path
import stat
import sys
import xml.etree.ElementTree as ET


WORKTREE = Path(__file__).resolve().parents[1]
# Keep required classes in sync with the outer verify_candidate.py; CI is standalone.
ERP_REQUIRED_TESTS = {
    "erp-master": (
        "org.erp.common.excel.CellValuesBoundsTest",
        "org.erp.common.excel.ExcelRowReaderLimitsTest",
        "org.erp.common.excel.ImportResultBoundsTest",
        "org.erp.common.sequence.SequenceServiceTest",
    ),
    "erp-stock": (
        "org.erp.stock.mapper.StockMapperSqlTest",
        "org.erp.stock.service.impl.InventoryServiceImplTest",
    ),
    "erp-trade": (
        "org.erp.trade.service.impl.ErpSaleReturnRefundFlowTest",
        "org.erp.trade.service.impl.TradeSqlContractTest",
        "org.erp.trade.service.impl.ErpPurchaseServiceImplTest",
        "org.erp.trade.service.impl.ErpSaleServiceImplTest",
        "org.erp.trade.service.impl.ErpOrderProgressServiceImplTest",
        "org.erp.trade.service.impl.ErpOtherInServiceImplTest",
        "org.erp.trade.service.impl.ErpOtherOutServiceImplTest",
        "org.erp.trade.service.impl.ErpReturnBatchingRegressionTest",
        "org.erp.trade.service.impl.ErpReturnBatchSqlContractTest",
        "org.erp.trade.service.impl.DashboardServiceImplTest",
        "org.erp.trade.service.impl.FinCostReportServiceImplTest",
        "org.erp.trade.mapper.DashboardP07MapperSqlContractTest",
    ),
    "erp-fin": (
        "org.erp.fin.controller.FinanceConfirmPermissionTest",
        "org.erp.fin.controller.FinConfirmPermissionTest",
        "org.erp.fin.controller.FinAccountOptionsBoundaryTest",
        "org.erp.fin.service.impl.FinArApSourceBoundaryTest",
        "org.erp.fin.service.impl.FinOpeningGuardSqlContractTest",
        "org.erp.fin.service.impl.FinOpeningConfirmedAmountsSqlContractTest",
        "org.erp.fin.service.impl.FinOpeningConfirmedAmountsTest",
        "org.erp.fin.excel.FinOpeningExcelBoundaryTest",
        "org.erp.fin.service.impl.FinArApServiceImplTest",
        "org.erp.fin.service.impl.FinReceiptServiceImplTest",
        "org.erp.fin.service.impl.FinOtherFlowServiceImplTest",
        "org.erp.fin.service.impl.FinAccountRefundBalanceTest",
        "org.erp.fin.service.impl.FinAccountPagingTest",
        "org.erp.fin.service.impl.FinAccountSqlContractTest",
        "org.erp.fin.service.impl.FinReceiptRefundBoundaryTest",
        "org.erp.fin.service.impl.FinReceiptSaleReturnRefundTest",
        "org.erp.fin.service.impl.FinArApSaleReturnRefundTest",
        "org.erp.fin.service.impl.FinArApPaginationTest",
        "org.erp.fin.service.impl.FinArApSqlContractTest",
        "org.erp.fin.service.impl.FinOpeningReadBoundaryTest",
        "org.erp.fin.service.impl.FinOpeningSqlContractTest",
        "org.erp.fin.service.impl.FinOpeningServiceImplTest",
        "org.erp.fin.service.impl.FinOpeningGuardServiceImplTest",
        "org.erp.fin.service.impl.FinOpeningConcurrencyOrderTest",
        "org.erp.fin.excel.FinOpeningExcelServiceTest",
    ),
    "erp-print": (
        "org.erp.print.service.impl.PrintAllocSourceBatchTest",
        "org.erp.print.service.impl.PrintTemplateServiceImplTest",
        "org.erp.print.service.impl.BuiltinStatementTemplateTest",
        "org.erp.print.service.impl.PrintFinanceAndProfitTest",
        "org.erp.print.service.impl.PrintStatementPeriodTest",
        "org.erp.print.service.impl.PageSetupTest",
        "org.erp.print.service.impl.PrintStatementRangeTest",
        "org.erp.print.service.impl.PrintStatementQtyScaleTest",
        "org.erp.print.service.impl.PrintPreviewContextTest",
        "org.erp.print.service.impl.DesignPresetsTest",
        "org.erp.print.mapper.PrintFinanceMapperSqlTest",
        "org.erp.print.service.impl.TemplateSecurityTest",
        "org.erp.print.service.impl.PrintTemplateEntrySecurityTest",
    ),
}
PLATFORM_MODULES = {
    Path("jianzao-backend/jianzao-admin"): (
        "org.dromara.common.tenant.handle.PlusTenantLineHandlerFailClosedTest",
        "org.dromara.common.mybatis.core.page.PageQueryBoundsTest",
        "org.dromara.common.web.filter.RequestBodyLimitFilterTest",
        "org.dromara.common.web.filter.RequestBodyLimitsTest",
        "org.dromara.web.config.RequestLimitConfigurationGuardTest",
        "org.dromara.common.log.aspect.LogAspectTest",
        "org.dromara.common.web.config.ResourcesConfigCorsTest",
        "org.dromara.web.listener.UserActionListenerTenantContextTest",
        # The test of the system service is currently in admin with this package.
        "org.dromara.web.listener.SysLogininforServiceImplTenantContextTest",
    ),
    Path("jianzao-backend/jianzao-modules/jianzao-system"): (
        "org.dromara.system.service.impl.SysRoleServiceImplTest",
        "org.dromara.system.service.impl.SysTenantExpireTimeUpdateTest",
    ),
}
PLATFORM_TESTS = tuple(name for names in PLATFORM_MODULES.values() for name in names)
REPORT_MODULES = {
    "erp": {Path("erp-modules") / module: classes for module, classes in ERP_REQUIRED_TESTS.items()},
    "platform": PLATFORM_MODULES,
}
RESULT_TAGS = {"failures": "failure", "errors": "error", "skipped": "skipped"}


def read_suite(report: Path) -> tuple[str, dict[str, int], bool]:
    suite = ET.parse(report).getroot()
    name = suite.get("name", "")
    if suite.tag != "testsuite" or not name or report.name != f"TEST-{name}.xml":
        raise ValueError("expected a Surefire testsuite name matching the report filename")

    counts = {}
    for key in ("tests", *RESULT_TAGS):
        value = suite.get(key, "")
        if not value.isascii() or not value.isdecimal():
            raise ValueError(f"missing or invalid nonnegative {key} counter")
        counts[key] = int(value)
    cases = suite.findall("testcase")
    if counts["tests"] != len(cases):
        raise ValueError("tests counter disagrees with the number of testcase elements")
    for key, tag in RESULT_TAGS.items():
        if counts[key] != sum(case.find(tag) is not None for case in cases):
            raise ValueError(f"{key} counter disagrees with testcase results")
    if any(case.get("classname") != name or not case.get("name") for case in cases):
        raise ValueError("testcase identity must match its suite class and have a nonempty name")
    return name, counts, bool(cases)


def fresh_reports(started_at_ns: int, modules: tuple[Path, ...]) -> list[Path]:
    """Read only exact module report directories; never recurse or grant clock grace."""
    if type(started_at_ns) is not int or started_at_ns <= 0:
        raise ValueError("stage start must be a positive epoch timestamp in nanoseconds")
    files = []
    for module in modules:
        directory = module.absolute() / "target/surefire-reports"
        if directory.resolve() != directory:
            raise ValueError(f"{directory}: refusing redirected report directory")
        for report in sorted(directory.glob("TEST-*.xml")):
            info = report.lstat()
            if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
                    or getattr(info, 'st_file_attributes', 0) & 0x400):
                raise ValueError(f"{report}: not a regular report file")
            if info.st_mtime_ns >= started_at_ns:
                files.append(report)
    return files


def check_reports(worktree: Path, stage: str, started_at_ns: int) -> tuple[dict[str, int], list[str]]:
    worktree = worktree.resolve()
    totals = dict.fromkeys(("reports", "tests", *RESULT_TAGS), 0)
    issues = []
    missing = []
    for module, required in REPORT_MODULES[stage].items():
        executed = set()
        for report in fresh_reports(started_at_ns, (worktree / module,)):
            try:
                name, counts, has_class_case = read_suite(report)
            except (ET.ParseError, OSError, ValueError) as e:
                raise ValueError(f"{report.relative_to(worktree)}: {e}") from e
            totals["reports"] += 1
            for key, count in counts.items():
                totals[key] += count
            if has_class_case:
                executed.add(name)
            if stage == "platform" and name not in required:
                issues.append(f"Unexpected platform test suite outside the module allowlist: {name}")
        missing.extend(name for name in required if name not in executed)

    if not totals["tests"]:
        issues.append("No tests executed in fresh Surefire reports for this stage.")
    if missing:
        issues.append("Required test classes did not execute: " + ", ".join(missing))
    if any(totals[key] for key in RESULT_TAGS):
        issues.append("Fresh Surefire reports must contain zero failures, errors and skipped cases.")
    return totals, issues


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("platform-allowlist", help="Print the exact Surefire -Dtest selector.")
    commands.add_parser("platform-reactor", help="Print exact platform Maven module selectors.")
    verify = commands.add_parser("verify", help="Verify the selected Maven phase's reports.")
    verify.add_argument("stage", choices=REPORT_MODULES)
    verify.add_argument("--started-at-ns", type=int, required=True)
    args = parser.parse_args(argv)

    if args.command == "platform-allowlist":
        print(",".join(PLATFORM_TESTS))
        return 0
    if args.command == "platform-reactor":
        print(",".join(module.relative_to("jianzao-backend").as_posix() for module in PLATFORM_MODULES))
        return 0
    try:
        counts, issues = check_reports(WORKTREE, args.stage, args.started_at_ns)
    except (OSError, ValueError) as e:
        print(f"{args.stage}: {e}", file=sys.stderr)
        return 1
    print(f"{args.stage} fresh Surefire reports: " + ", ".join(f"{key}={value}" for key, value in counts.items()))
    for issue in issues:
        print(f"{args.stage}: {issue}", file=sys.stderr)
    return int(bool(issues))


if __name__ == "__main__":
    sys.exit(main())
