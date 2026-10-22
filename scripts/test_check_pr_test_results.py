#!/usr/bin/env python3
import unittest

from check_pr_test_results import check_results


def summary(total=3, completed=2, aborted=0, succeeded=3, failed=0,
            canceled=0, ignored=0, pending=0):
    return (
        f"[info] Total number of tests run: {total}\n"
        f"[info] Suites: completed {completed}, aborted {aborted}\n"
        f"[info] Tests: succeeded {succeeded}, failed {failed}, canceled {canceled}, "
        f"ignored {ignored}, pending {pending}\n"
    )


class PrTestResultsTest(unittest.TestCase):
    def test_normal_and_colored_logs(self):
        for log in (summary(), "\x1b[32m" + summary() + "\x1b[0m"):
            self.assertIn("3 tests passed across 2 suites", check_results(log))

    def test_rejects_zero_tests_and_incomplete_logs(self):
        for log in ("", "[success] Total time: 10 s", summary(total=0, succeeded=0),
                    summary(completed=0), summary() + summary()):
            with self.subTest(log=log), self.assertRaises(ValueError):
                check_results(log)

    def test_rejects_incomplete_or_failed_test_execution(self):
        for field in ("aborted", "failed", "canceled", "pending"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                check_results(summary(**{field: 1}))
        with self.assertRaises(ValueError):
            check_results(summary(total=4))


if __name__ == "__main__":
    unittest.main()
