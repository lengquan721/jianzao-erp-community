"""Exercise CI report rules using only temporary Surefire XML fixtures."""
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import verify_ci_reports as gates


ADMIN = Path("jianzao-backend/jianzao-admin")
FIN = Path("erp-modules/erp-fin")
PRINT = Path("erp-modules/erp-print")
SYSTEM = Path("jianzao-backend/jianzao-modules/jianzao-system")
ROLE_TEST = "org.dromara.system.service.impl.SysRoleServiceImplTest"
ADMIN_LOGIN = "org.dromara.web.listener.UserActionListenerTenantContextTest"
SERVICE_LOGIN = "org.dromara.web.listener.SysLogininforServiceImplTenantContextTest"
EXPIRY = "org.dromara.system.service.impl.SysTenantExpireTimeUpdateTest"
ERP_COUNT = sum(map(len, gates.ERP_REQUIRED_TESTS.values()))
PLATFORM_COUNT = len(gates.PLATFORM_TESTS)
FIN_AR_AP_TESTS = (
    "org.erp.fin.service.impl.FinArApPaginationTest",
    "org.erp.fin.service.impl.FinArApSqlContractTest",
)
ROUND_REQUIRED = {
    FIN: (
        'org.erp.fin.controller.FinAccountOptionsBoundaryTest',
        'org.erp.fin.excel.FinOpeningExcelBoundaryTest',
        'org.erp.fin.service.impl.FinArApSourceBoundaryTest',
        'org.erp.fin.service.impl.FinOpeningGuardSqlContractTest',
        'org.erp.fin.service.impl.FinOpeningConfirmedAmountsTest',
        'org.erp.fin.service.impl.FinOpeningConfirmedAmountsSqlContractTest',
    ),
    PRINT: (
        'org.erp.print.service.impl.PrintStatementRangeTest',
        'org.erp.print.service.impl.PrintStatementQtyScaleTest',
        'org.erp.print.service.impl.PrintPreviewContextTest',
        'org.erp.print.service.impl.DesignPresetsTest',
    ),
    Path('erp-modules/erp-trade'): (
        'org.erp.trade.service.impl.ErpSaleServiceImplTest',
        'org.erp.trade.service.impl.ErpOrderProgressServiceImplTest',
        'org.erp.trade.service.impl.ErpOtherInServiceImplTest',
        'org.erp.trade.service.impl.ErpOtherOutServiceImplTest',
    ),
}
TEMPLATE_TEST = "org.erp.print.service.impl.TemplateSecurityTest"
ENTRY_TEST = "org.erp.print.service.impl.PrintTemplateEntrySecurityTest"


class VerifyCiReportsTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory(prefix="ci-report-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.start = time.time_ns() - 2_000_000_000

    def write_report(self, module, name, *, tests=1, outcome=None, old=False, file_class=None, case_class=None):
        directory = self.root / module / "target/surefire-reports"
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"TEST-{file_class or name}.xml"
        attrs = {key: str(int(outcome == tag)) for key, tag in gates.RESULT_TAGS.items()}
        suite = ET.Element("testsuite", name=name, tests=str(tests), **attrs)
        for index in range(tests):
            case = ET.SubElement(suite, "testcase", name=f"case{index}", classname=case_class or name)
            if outcome and index == 0:
                ET.SubElement(case, outcome)
        ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
        # Put stale fixtures within two seconds of the start to detect freshness grace periods.
        mtime_ns = self.start + (-1 if old else 1) * 1_000_000_000
        os.utime(path, ns=(mtime_ns, mtime_ns))
        return path

    def seed(self, stage, omit=None):
        for module, names in gates.REPORT_MODULES[stage].items():
            for name in names:
                if name != omit:
                    self.write_report(module, name)

    def check(self, stage):
        return gates.check_reports(self.root, stage, self.start)

    def test_all_erp_reports_pass_with_additional_erp_unit_cases(self):
        self.seed("erp")
        self.write_report(FIN, "org.erp.fin.OtherTest", tests=2)
        self.assertGreaterEqual(ERP_COUNT, 56)
        self.assertEqual(({"reports": ERP_COUNT + 1, "tests": ERP_COUNT + 2, "failures": 0, "errors": 0, "skipped": 0}, []), self.check("erp"))

    def test_all_platform_reports_pass_in_their_actual_modules(self):
        self.seed("platform")
        self.assertEqual(({"reports": PLATFORM_COUNT, "tests": PLATFORM_COUNT, "failures": 0, "errors": 0, "skipped": 0}, []), self.check("platform"))

    def test_each_critical_finance_stock_and_print_class_is_required(self):
        self.seed("erp")
        required = (
            (PRINT, TEMPLATE_TEST),
            (PRINT, ENTRY_TEST),
            (PRINT, "org.erp.print.service.impl.PrintStatementPeriodTest"),
            (PRINT, "org.erp.print.service.impl.BuiltinStatementTemplateTest"),
            (FIN, "org.erp.fin.service.impl.FinOpeningReadBoundaryTest"),
            (FIN, "org.erp.fin.service.impl.FinOpeningSqlContractTest"),
            (FIN, "org.erp.fin.service.impl.FinAccountPagingTest"),
            (FIN, "org.erp.fin.service.impl.FinAccountSqlContractTest"),
            (Path("erp-modules/erp-stock"), "org.erp.stock.service.impl.InventoryServiceImplTest"),
        )
        for module, name in required:
            with self.subTest(name=name):
                (self.root / module / "target/surefire-reports" / f"TEST-{name}.xml").unlink()
                totals, issues = self.check("erp")
                self.assertEqual(ERP_COUNT - 1, totals["tests"])
                self.assertEqual([f"Required test classes did not execute: {name}"], issues)
                self.write_report(module, name)

    def test_read_batch_and_dashboard_regressions_are_mandatory(self):
        expected = {
            "erp-trade": (
                "org.erp.trade.service.impl.ErpPurchaseServiceImplTest",
                "org.erp.trade.service.impl.ErpReturnBatchingRegressionTest",
                "org.erp.trade.service.impl.ErpReturnBatchSqlContractTest",
                "org.erp.trade.service.impl.DashboardServiceImplTest",
                "org.erp.trade.service.impl.FinCostReportServiceImplTest",
                "org.erp.trade.mapper.DashboardP07MapperSqlContractTest",
            ),
            "erp-print": (
                "org.erp.print.service.impl.PrintAllocSourceBatchTest",
                "org.erp.print.service.impl.PrintTemplateServiceImplTest",
            ),
        }
        self.seed("erp")
        for module, names in expected.items():
            for name in names:
                with self.subTest(name=name):
                    self.assertIn(name, gates.ERP_REQUIRED_TESTS[module])
                    (self.root / "erp-modules" / module / "target/surefire-reports" / f"TEST-{name}.xml").unlink()
                    totals, issues = self.check("erp")
                    self.assertEqual(ERP_COUNT - 1, totals["tests"])
                    self.assertEqual([f"Required test classes did not execute: {name}"], issues)
                    self.write_report(Path("erp-modules") / module, name)

    def test_stale_erp_report_is_not_replaced_by_other_fresh_test(self):
        self.seed("erp", omit=TEMPLATE_TEST)
        self.write_report(PRINT, TEMPLATE_TEST, old=True)
        self.write_report(PRINT, "org.erp.print.UnrelatedTest")
        totals, issues = self.check("erp")
        self.assertEqual(ERP_COUNT, totals["tests"])
        self.assertEqual([f"Required test classes did not execute: {TEMPLATE_TEST}"], issues)

    def test_erp_report_must_be_in_the_expected_module(self):
        self.seed("erp", omit=TEMPLATE_TEST)
        self.write_report(ADMIN, TEMPLATE_TEST)
        self.write_report(FIN, TEMPLATE_TEST)
        _, issues = self.check("erp")
        self.assertEqual([f"Required test classes did not execute: {TEMPLATE_TEST}"], issues)

    def test_admin_gate_ignores_reports_in_other_roots(self):
        required = gates.PLATFORM_TESTS[0]
        self.seed("platform", omit=required)
        self.write_report(PRINT, required)
        self.write_report(Path("jianzao-backend/jianzao-common"), required)
        self.write_report(ADMIN / "nested", required)
        totals, issues = self.check("platform")
        self.assertEqual(PLATFORM_COUNT - 1, totals["tests"])
        self.assertEqual([f"Required test classes did not execute: {required}"], issues)

    def test_admin_gate_rejects_a_suite_outside_its_allowlist(self):
        self.seed("platform")
        name = "org.dromara.test.DemoUnitTest"
        self.write_report(ADMIN, name)
        self.assertEqual([f"Unexpected platform test suite outside the module allowlist: {name}"], self.check("platform")[1])

    def test_required_zero_test_report_does_not_count(self):
        required = gates.PLATFORM_TESTS[0]
        self.seed("platform", omit=required)
        self.write_report(ADMIN, required, tests=0)
        totals, issues = self.check("platform")
        self.assertEqual(PLATFORM_COUNT, totals["reports"])
        self.assertEqual(PLATFORM_COUNT - 1, totals["tests"])
        self.assertEqual([f"Required test classes did not execute: {required}"], issues)

    def test_only_zero_tests_cannot_pass(self):
        self.write_report(PRINT, TEMPLATE_TEST, tests=0)
        totals, issues = self.check("erp")
        self.assertEqual(0, totals["tests"])
        self.assertIn("No tests executed in fresh Surefire reports for this stage.", issues)

    def test_unrelated_suite_cannot_use_the_required_filename(self):
        self.write_report(PRINT, "org.erp.print.OtherTest", file_class=TEMPLATE_TEST)
        with self.assertRaisesRegex(ValueError, "name matching the report filename"):
            self.check("erp")

    def test_unrelated_testcase_cannot_prove_the_required_class_executed(self):
        self.seed("erp", omit=TEMPLATE_TEST)
        self.write_report(PRINT, TEMPLATE_TEST, case_class="org.erp.print.OtherTest")
        with self.assertRaisesRegex(ValueError, "testcase identity"):
            self.check("erp")

    def test_any_failure_error_or_skip_in_fresh_reports_fails(self):
        self.seed("erp")
        for key, outcome in gates.RESULT_TAGS.items():
            with self.subTest(outcome=outcome):
                self.write_report(FIN, "org.erp.fin.OtherTest", outcome=outcome)
                totals, issues = self.check("erp")
                self.assertEqual(1, totals[key])
                self.assertEqual(["Fresh Surefire reports must contain zero failures, errors and skipped cases."], issues)

    def test_malformed_fresh_xml_is_not_ignored(self):
        report = self.write_report(PRINT, TEMPLATE_TEST)
        report.write_text("<testsuite>", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, r"TEST-.*\.xml: .*line 1"):
            self.check("erp")

    def test_missing_invalid_or_inconsistent_counters_fail_closed(self):
        for update, expected in (
            ({"errors": None}, "invalid nonnegative errors counter"),
            ({"failures": "-1"}, "invalid nonnegative failures counter"),
            ({"tests": "NaN"}, "invalid nonnegative tests counter"),
            ({"tests": "2"}, "tests counter disagrees"),
            ({"skipped": "1"}, "skipped counter disagrees"),
        ):
            with self.subTest(update=update):
                report = self.write_report(PRINT, TEMPLATE_TEST)
                suite = ET.parse(report).getroot()
                for key, value in update.items():
                    if value is None:
                        del suite.attrib[key]
                    else:
                        suite.set(key, value)
                ET.ElementTree(suite).write(report, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, expected):
                    self.check("erp")

    def test_stale_admin_report_is_not_replaced_by_another_stage(self):
        required = gates.PLATFORM_TESTS[0]
        self.seed("platform", omit=required)
        self.write_report(ADMIN, required, old=True)
        self.write_report(FIN, required)
        self.assertEqual([f"Required test classes did not execute: {required}"], self.check("platform")[1])

    def test_all_stale_reports_fail_the_nonzero_test_gate(self):
        self.seed("erp")
        for module, names in gates.REPORT_MODULES["erp"].items():
            for name in names:
                self.write_report(module, name, old=True)
        totals, issues = self.check("erp")
        self.assertEqual(0, totals["reports"])
        self.assertEqual(0, totals["tests"])
        self.assertIn("No tests executed in fresh Surefire reports for this stage.", issues)

    def test_hidden_testcase_failure_cannot_be_masked_by_zero_counters(self):
        report = self.write_report(PRINT, TEMPLATE_TEST)
        suite = ET.parse(report).getroot()
        ET.SubElement(suite.find("testcase"), "failure")
        ET.ElementTree(suite).write(report, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "failures counter disagrees"):
            self.check("erp")

    def test_invalid_stage_timestamp_is_not_accepted(self):
        for value in (0, -1, True, 1.5):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "positive epoch timestamp"):
                gates.check_reports(self.root, "erp", value)

    def test_cli_allowlist_is_shared_with_admin_evidence_check(self):
        with redirect_stdout(StringIO()) as output:
            self.assertEqual(0, gates.main(["platform-allowlist"]))
        self.assertEqual(11, PLATFORM_COUNT)
        self.assertEqual(gates.PLATFORM_TESTS, tuple(output.getvalue().strip().split(",")))
        self.assertIn(ADMIN_LOGIN, gates.PLATFORM_MODULES[ADMIN])
        self.assertIn(SERVICE_LOGIN, gates.PLATFORM_MODULES[ADMIN])
        self.assertEqual((ROLE_TEST, EXPIRY), gates.PLATFORM_MODULES[SYSTEM])
        with redirect_stdout(StringIO()) as output:
            self.assertEqual(0, gates.main(["platform-reactor"]))
        self.assertEqual("jianzao-admin,jianzao-modules/jianzao-system", output.getvalue().strip())

    def test_login_and_expiry_require_exact_current_module(self):
        for module, wrong, name in ((ADMIN, SYSTEM, ADMIN_LOGIN), (ADMIN, SYSTEM, SERVICE_LOGIN),
                                    (SYSTEM, ADMIN, EXPIRY)):
            with self.subTest(name=name):
                self.seed("platform")
                (self.root / module / 'target/surefire-reports' / f'TEST-{name}.xml').unlink()
                source = gates.WORKTREE / module / "src/test/java" / Path(*name.split(".")).with_suffix(".java")
                self.assertTrue(source.is_file(), name)
                self.assertIn("package " + name.rsplit(".", 1)[0] + ";", source.read_text(encoding="utf-8-sig"))
                wrong_report = self.write_report(wrong, name)
                _, issues = self.check("platform")
                self.assertIn(f"Required test classes did not execute: {name}", issues)
                self.assertIn(f"Unexpected platform test suite outside the module allowlist: {name}", issues)
                wrong_report.unlink()
                self.write_report(module, name)
                self.assertEqual([], self.check("platform")[1])

    def test_round_core_boundary_suites_exist_and_cannot_be_substituted(self):
        self.seed('erp')
        for module, names in ROUND_REQUIRED.items():
            for name in names:
                with self.subTest(module=module, name=name):
                    self.assertIn(name, gates.REPORT_MODULES['erp'][module])
                    java = gates.WORKTREE / module / 'src/test/java' / Path(*name.split('.')).with_suffix('.java')
                    source = java.read_text(encoding='utf-8-sig')
                    self.assertIn('package ' + name.rsplit('.', 1)[0] + ';', source)
                    self.assertIn('@Tag("dev")', source)
                    (self.root / module / 'target/surefire-reports' / f'TEST-{name}.xml').unlink()
                    wrong = self.write_report(PRINT if module == FIN else FIN, name)
                    self.assertIn(f'Required test classes did not execute: {name}', self.check('erp')[1])
                    wrong.unlink()
                    self.write_report(module, name)

    def test_each_new_fin_ar_ap_class_is_required(self):
        self.seed("erp")
        for name in FIN_AR_AP_TESTS:
            with self.subTest(name=name):
                self.assertIn(name, gates.ERP_REQUIRED_TESTS["erp-fin"])
                (self.root / FIN / "target/surefire-reports" / f"TEST-{name}.xml").unlink()
                totals, issues = self.check("erp")
                self.assertEqual(ERP_COUNT - 1, totals["tests"])
                self.assertEqual([f"Required test classes did not execute: {name}"], issues)
                self.write_report(FIN, name)

    def test_new_role_class_cannot_be_omitted(self):
        self.seed("platform", omit=ROLE_TEST)
        totals, issues = self.check("platform")
        self.assertEqual(PLATFORM_COUNT - 1, totals["tests"])
        self.assertEqual([f"Required test classes did not execute: {ROLE_TEST}"], issues)

    def test_role_report_in_admin_cannot_replace_the_system_report(self):
        self.seed("platform", omit=ROLE_TEST)
        self.write_report(ADMIN, ROLE_TEST)
        _, issues = self.check("platform")
        self.assertIn(f"Required test classes did not execute: {ROLE_TEST}", issues)
        self.assertIn(f"Unexpected platform test suite outside the module allowlist: {ROLE_TEST}", issues)

    def test_stale_role_report_is_not_replaced_by_erp_or_nested_reports(self):
        self.seed("platform", omit=ROLE_TEST)
        self.write_report(SYSTEM, ROLE_TEST, old=True)
        self.write_report(FIN, ROLE_TEST)
        self.write_report(SYSTEM / "nested", ROLE_TEST)
        self.assertEqual([f"Required test classes did not execute: {ROLE_TEST}"], self.check("platform")[1])

    def test_mixed_testcase_classes_cannot_inflate_a_required_suite(self):
        report = self.write_report(FIN, FIN_AR_AP_TESTS[0], tests=2)
        suite = ET.parse(report).getroot()
        suite.findall("testcase")[1].set("classname", "org.erp.fin.OtherTest")
        ET.ElementTree(suite).write(report, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "testcase identity"):
            self.check("erp")

    def test_missing_testcase_name_is_invalid_evidence(self):
        report = self.write_report(FIN, FIN_AR_AP_TESTS[0])
        suite = ET.parse(report).getroot()
        del suite.find("testcase").attrib["name"]
        ET.ElementTree(suite).write(report, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "testcase identity"):
            self.check("erp")

    def test_timestamp_boundary_uses_nanoseconds_without_a_grace_period(self):
        self.start = self.start // 1_000 * 1_000
        self.seed("erp")
        report = self.write_report(FIN, FIN_AR_AP_TESTS[0])
        os.utime(report, ns=(self.start - 1_000, self.start - 1_000))
        self.assertIn(f"Required test classes did not execute: {FIN_AR_AP_TESTS[0]}", self.check("erp")[1])
        os.utime(report, ns=(self.start, self.start))
        self.assertEqual([], self.check("erp")[1])

    def test_cli_returns_failure_without_reports_and_success_with_evidence(self):
        args = ["verify", "platform", "--started-at-ns", str(self.start)]
        with patch.object(gates, "WORKTREE", self.root), redirect_stdout(StringIO()), redirect_stderr(StringIO()) as error:
            self.assertEqual(1, gates.main(args))
            self.assertIn("No tests executed", error.getvalue())
            self.seed("platform")
            self.assertEqual(0, gates.main(args))


if __name__ == "__main__":
    unittest.main()
