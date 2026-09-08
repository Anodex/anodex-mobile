#!/usr/bin/env python3
"""Publish the APK for the commit that is actually checked out.

Written after shipping a release whose APK was a build of the *previous*
version. The release said 0.44.0, the file inside said 0.43.0, and the phone
therefore updated, reinstalled the same build, and went on offering the update
for ever — an install loop with nothing visibly wrong at either end.

The cause was picking the CI run with `gh run list --limit 1` moments after
pushing. The newest run at that instant was the merge that came just before the
version bump, not the bump itself. Both were green, both produced an APK, and
only one of them had the new version in it.

So this never asks which run is newest. It resolves the run by the commit sha,
and refuses to upload an APK whose `versionName` does not match the tag — the
one check that would have caught it, and the only one that matters, because a
correct tag on the wrong bytes is indistinguishable from success until somebody
installs it.

    python tools/release_apk.py v0.44.0-preview.52 0.44.0 --notes-file notes.md
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

WORKFLOW_ARTIFACT = "anodex-mobile-apk"


def run(*args: str) -> str:
    """A command whose output we need, failing loudly rather than silently."""
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"{' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout.strip()


def version_in(apk: str) -> str | None:
    """The `versionName` compiled into an APK.

    Read out of the binary manifest rather than taken on trust from the
    filename, which is the whole point: the filename is what was wrong.
    """
    with zipfile.ZipFile(apk) as archive:
        manifest = archive.read("AndroidManifest.xml").decode("utf-16-le", "ignore")
    found = sorted(set(re.findall(r"\d+\.\d+\.\d+", manifest)))
    return found[0] if len(found) == 1 else None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", help="release tag, e.g. v0.44.0-preview.52")
    parser.add_argument("version", help="versionName the APK must report, e.g. 0.44.0")
    parser.add_argument("--title", default=None)
    parser.add_argument("--notes-file", default=None)
    parser.add_argument(
        "--sha",
        default=None,
        help="commit to publish; defaults to HEAD, which is what you just pushed",
    )
    args = parser.parse_args()

    # Resolved through git rather than used as typed, so a short sha, a tag or a
    # branch name all work. `gh` reports full shas, and comparing one of those to
    # "ea0d9e9" matches nothing — which reads exactly like "CI has not finished".
    sha = run("git", "rev-parse", args.sha or "HEAD")
    print(f"commit   {sha[:7]}")

    # By sha, never by recency. This is the line the incident was about.
    runs = json.loads(
        run(
            "gh", "run", "list", "--limit", "40",
            "--json", "databaseId,headSha,status,conclusion,workflowName",
        )
    )
    matching = [
        r for r in runs
        if r["headSha"] == sha and r["status"] == "completed" and r["conclusion"] == "success"
    ]
    if not matching:
        sys.exit(
            f"No completed, successful run for {sha[:7]}.\n"
            "Wait for CI on this exact commit — do not reach for a neighbouring run."
        )

    run_id = str(matching[0]["databaseId"])
    print(f"run      {run_id}")

    with tempfile.TemporaryDirectory() as work:
        run("gh", "run", "download", run_id, "-n", WORKFLOW_ARTIFACT, "-D", work)
        built = os.path.join(work, "app-release.apk")
        if not os.path.exists(built):
            sys.exit(f"{WORKFLOW_ARTIFACT} held no app-release.apk")

        found = version_in(built)
        print(f"apk says {found}")
        if found != args.version:
            sys.exit(
                f"Refusing to publish: this APK reports {found}, the release claims "
                f"{args.version}.\nThat mismatch is the bug this script exists to stop."
            )

        named = os.path.join(work, f"anodex-{args.version}.apk")
        os.replace(built, named)

        create = ["gh", "release", "create", args.tag, named]
        if args.title:
            create += ["--title", args.title]
        if args.notes_file:
            create += ["--notes-file", args.notes_file]
        print(run(*create))

    print(f"published {args.tag} carrying {args.version}")


if __name__ == "__main__":
    main()
