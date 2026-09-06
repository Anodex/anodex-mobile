"""Report what the unit tests actually covered, and fail if they covered nothing.

`BUILD SUCCESSFUL` is not evidence. Gradle prints it for a test task it served `FROM-CACHE`
without running anything, for a task with no tests to run, and for a task whose tests were all
skipped. Those are indistinguishable from a real pass in the log, which makes a suite that quietly
stopped checking anything invisible.

This reads the JUnit XML the task leaves behind - accurate whether the task executed or was
restored from the cache - prints the totals, and exits non-zero on zero tests or any failure.

Usage, from the repo root:

    python tools/summarize_tests.py [--results-dir app/build/test-results/testDebugUnitTest]
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ElementTree

DEFAULT_RESULTS = os.path.join("app", "build", "test-results", "testDebugUnitTest")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", default=DEFAULT_RESULTS)
    args = parser.parse_args()

    files = sorted(glob.glob(os.path.join(args.results_dir, "*.xml")))
    if not files:
        print("No test result XML in %s - the suite did not run." % args.results_dir)
        return 1

    total = failures = errors = skipped = 0
    rows = []

    for path in files:
        suite = ElementTree.parse(path).getroot()
        name = suite.get("name", os.path.basename(path))
        counts = (
            int(suite.get("tests", 0)),
            int(suite.get("failures", 0)),
            int(suite.get("errors", 0)),
            int(suite.get("skipped", 0)),
        )
        total += counts[0]
        failures += counts[1]
        errors += counts[2]
        skipped += counts[3]
        rows.append((name.rsplit(".", 1)[-1], counts))

    width = max(len(name) for name, _ in rows)
    for name, (tests, fail, err, skip) in rows:
        note = ""
        if fail or err:
            note = "  <-- %d failed, %d errored" % (fail, err)
        elif skip:
            note = "  (%d skipped)" % skip
        print("  %-*s  %3d tests%s" % (width, name, tests, note))

    print("\n%d tests across %d %s: %d failed, %d errored, %d skipped"
          % (total, len(rows), "suite" if len(rows) == 1 else "suites",
             failures, errors, skipped))

    if total == 0:
        print("\nZero tests ran. Treating that as a failure - a suite that checks nothing "
              "should not report success.")
        return 1
    if failures or errors:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
