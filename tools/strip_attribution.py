#!/usr/bin/env python3
"""Strip coding-agent attribution from a commit message before it is recorded.

Run from the `commit-msg` hook, so it applies to every commit made in this repo
whatever produced the message.

The desktop repo has had this for a while and its history stayed clean. This one
did not, and 109 commits accumulated `Co-Authored-By` trailers that render as a
second contributor on the repository page — an account that never wrote a line
of it. Removing them afterwards means rewriting history and force-pushing, which
is why the guard belongs here rather than in a cleanup script.

The concern is narrow: what gets *published* as authorship. Product names are
legitimate elsewhere — a commit body that discusses these tools in prose is left
alone. Only trailers and sign-off lines that credit the tool are removed, because
those are what become avatars and badges.

Install:  python tools/strip_attribution.py --install
"""

from __future__ import annotations

import os
import re
import stat
import sys

# Matched against whole lines. Keyed on the tool identity as well as the trailer
# shape, so a real human co-author trailer still survives.
ATTRIBUTION = [
    re.compile(
        r"^\s*co-authored-by:.*\b(claude|codex|copilot|cursor|anthropic\.com|openai\.com)\b",
        re.I,
    ),
    re.compile(r"^\s*(?:🤖\s*)?generated with\b.*\b(claude|codex|copilot|cursor)\b", re.I),
    re.compile(
        r"^\s*(?:signed-off-by|assisted-by|authored-by):.*\b(claude|codex|copilot|cursor)\b",
        re.I,
    ),
]

HOOK = """#!/bin/sh
# Installed by tools/strip_attribution.py — keeps coding-agent attribution out
# of this repository's history. Re-install with:
#   python tools/strip_attribution.py --install
python tools/strip_attribution.py "$1"
"""


def strip(path: str) -> None:
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().splitlines()

    kept = [line for line in lines if not any(p.match(line) for p in ATTRIBUTION)]

    # A trailer block is usually preceded by a blank line. Removing the trailer
    # and leaving the blank behind gives every cleaned message a trailing gap,
    # which git keeps and every log then shows.
    while kept and not kept[-1].strip():
        kept.pop()

    if kept != lines:
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(kept) + "\n")
        print("strip-attribution: removed coding-agent attribution from the commit message")


def install() -> None:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    hook = os.path.join(root, ".git", "hooks", "commit-msg")

    with open(hook, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(HOOK)

    # Git will not run a hook it cannot execute, and it says nothing when it
    # skips one — the failure mode is silence, which is how this went unnoticed.
    os.chmod(hook, os.stat(hook).st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    print("installed", hook)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--install":
        install()
    elif len(sys.argv) > 1:
        strip(sys.argv[1])
    else:
        sys.exit("usage: strip_attribution.py <commit-msg-file> | --install")
