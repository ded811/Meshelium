#!/usr/bin/env python3
"""Build a publish-ready copy of the public tree from this private repo.

WHY THIS EXISTS
---------------
The public repo at github.com/ded811/Meshelium is this repo minus a set of
internal documents. The only thing separating them is the PUBLIC-REPO-ONLY
block in the PUBLIC copy's .gitignore - a block the private .gitignore does
not have.

That means the obvious way to publish is also the dangerous one: copy the
private repo into a fresh folder and push it, and the private .gitignore
travels with it, replaces the public one, and 53 internal files go public in
the same commit. A public push cannot be taken back - the content is cached
and indexed the moment it lands.

So this script builds the tree instead of a human doing it by hand, and then
refuses to hand it over if anything on the denylist made it in.

USAGE
-----
    python tools/prepare-public.py <output-dir>
    python tools/prepare-public.py <output-dir> --compare <existing-public-repo>

It copies only what should be public, writes the correct .gitignore, and runs
every check below. Non-zero exit means DO NOT PUSH.

WHAT IS HELD BACK, AND WHY
--------------------------
  docs/unreleased/farfield/   designs for an unreleased 1.7 feature
  docs/unreleased/sodium/     bytecode analyses of Sodium's internals; Sodium
                              is PolyForm Shield, and studying it is fine
                              while publishing a reverse-engineering writeup
                              of it is a different thing entirely
  docs/unreleased/*           the 1.7 changelog and the open-work list
  docs/<19 internal docs>     recon and design notes; several are thick with
                              a name the clean-room rule keeps out of public
                              text (NVIDIUM-ARCHITECTURE.md alone has ~60)
  CLAUDE.md, .claude/         working instructions for AI agents

WHAT IS DELIBERATELY PUBLISHED
------------------------------
  sodium/                     Meshelium's OWN adapter source. It compiles
                              into the shipped jar, so under LGPL-3.0 it is
                              corresponding source and must ship. It contains
                              no Sodium code.
  versions/                   the per-Minecraft-version layout (versions/
                              README.md): toolchain properties, the rename
                              table and the overlay files. Every published
                              jar is built from these, so they are
                              corresponding source too.
  the far-field CODE          same reason: compiled in, therefore published,
                              even though the feature is unreachable
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

PRIVATE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The public .gitignore, verbatim and authoritative. Kept here so the script
# is self-sufficient: if the public working copy is ever lost or clobbered,
# this rebuilds the one thing that holds the internal docs back.
PUBLIC_DOCS_BLOCK = """
# ----------------------------------------------------------------------
# PUBLIC REPO ONLY. This block does not exist in the private repo, and it
# is the reason this .gitignore must not be overwritten by a straight copy
# of the private one. Over there these docs are tracked on purpose; here
# they must not be.
#
# Two reasons. Several carry a name the clean-room rule keeps out of
# anything public (NVIDIUM-ARCHITECTURE.md alone has 60 mentions), and the
# far-field files are designs for an unreleased 1.7 feature.
#
# The far-field CODE is still published, and has to be: it is compiled
# into the shipped jar, so it is part of the corresponding source whether
# or not a player can reach the feature. Only the design notes are held
# back.
#
# Ignore everything under docs/, then name the exceptions, so a new
# internal doc is private by default instead of public by accident.
docs/*
!docs/MODRINTH.md
!docs/PERFORMANCE.md
!docs/TECHNICAL.md
!docs/TROUBLESHOOTING.md
!docs/fps-chart.png
!docs/fps-chart-rd64.png
!docs/releases/
!docs/tools/
"""

# Everything under docs/ is private unless named here. Deny by default, so a
# new internal document is held back by accident rather than published by it.
DOCS_ALLOW_FILES = {
    "docs/MODRINTH.md",
    "docs/PERFORMANCE.md",
    "docs/TECHNICAL.md",
    "docs/TROUBLESHOOTING.md",
    "docs/fps-chart.png",
    "docs/fps-chart-rd64.png",
}
DOCS_ALLOW_DIRS = ("docs/releases/", "docs/tools/")

# Never published, wherever they appear.
DENY_EXACT = {"CLAUDE.md"}
DENY_PREFIX = (".claude/",)

# If any of these survive into the output, the build is refused outright.
FORBIDDEN_PATH_PARTS = ("docs/unreleased/", "/.claude/", "CLAUDE.md")

# Words that must not appear in text a player can read. The far-field feature
# is unreleased; its name must not be in any published prose.
FORBIDDEN_TERMS = (
    r"far[\s-]?field", r"farfield", r"far terrain", r"far-armed",
)
# ...except in these, which are source code that legitimately implements it.
TERM_SCAN_EXTS = (".md",)


def tracked_files(root):
    out = subprocess.run(["git", "-C", root, "ls-files"],
                         capture_output=True, text=True, check=True).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def is_public(path):
    if path in DENY_EXACT:
        return False
    if any(path.startswith(p) for p in DENY_PREFIX):
        return False
    if path.startswith("docs/"):
        if path in DOCS_ALLOW_FILES:
            return True
        return any(path.startswith(d) for d in DOCS_ALLOW_DIRS)
    return True


def build(out_dir):
    files = tracked_files(PRIVATE_ROOT)
    keep = [f for f in files if is_public(f)]
    held = [f for f in files if not is_public(f)]

    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    for rel in keep:
        src = os.path.join(PRIVATE_ROOT, rel)
        dst = os.path.join(out_dir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)

    # The .gitignore is the one file that must NOT be the private copy.
    private_ignore = open(os.path.join(PRIVATE_ROOT, ".gitignore"),
                          encoding="utf-8").read().rstrip("\n")
    # The private file opens with a banner that says "do not copy this to
    # the public repo"; the public copy must not start with that sentence.
    # The banner is the first comment block, closed by a rule of dashes.
    lines = private_ignore.split("\n")
    if lines and lines[0].startswith("# ---"):
        end = next((i for i, l in enumerate(lines[1:], 1) if l.startswith("# ---")), None)
        if end is not None:
            lines = lines[end + 1:]
            while lines and not lines[0].strip():
                lines.pop(0)
    private_ignore = "\n".join(lines)
    with open(os.path.join(out_dir, ".gitignore"), "w",
              encoding="utf-8", newline="\n") as fh:
        fh.write(private_ignore + "\n" + PUBLIC_DOCS_BLOCK)

    return keep, held


def verify(out_dir, keep):
    problems = []

    for root, dirs, names in os.walk(out_dir):
        for n in names:
            rel = os.path.relpath(os.path.join(root, n), out_dir).replace("\\", "/")
            for bad in FORBIDDEN_PATH_PARTS:
                if bad in rel or rel == bad:
                    problems.append("held-back file present: " + rel)

    ig = open(os.path.join(out_dir, ".gitignore"), encoding="utf-8").read()
    if "PUBLIC REPO ONLY" not in ig:
        problems.append(".gitignore is missing the PUBLIC-REPO-ONLY block")
    for line in ("docs/*", "!docs/MODRINTH.md", "!docs/releases/"):
        if line not in ig:
            problems.append(".gitignore is missing the rule: " + line)
    # Every allowlisted doc must also be un-ignored, or it is copied into a
    # tree whose .gitignore hides it from the public commit.
    for f in sorted(DOCS_ALLOW_FILES):
        if ("!" + f) not in PUBLIC_DOCS_BLOCK:
            problems.append("allowlisted but not un-ignored: " + f)
    for d in DOCS_ALLOW_DIRS:
        if ("!" + d) not in PUBLIC_DOCS_BLOCK:
            problems.append("allowlisted dir not un-ignored: " + d)
    if "DO NOT COPY IT TO THE PUBLIC REPO" in ig:
        problems.append(".gitignore still carries the private banner")

    # LGPL: the adapter source compiles into the jar, so it has to ship.
    if not any(f.startswith("sodium/") for f in keep):
        problems.append("sodium/ source is MISSING - it is in the jar, so "
                        "LGPL-3.0 requires it to be published")

    # Unreleased feature names must not appear in published prose.
    for rel in keep:
        if not rel.endswith(TERM_SCAN_EXTS):
            continue
        try:
            text = open(os.path.join(out_dir, rel), encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        for term in FORBIDDEN_TERMS:
            if re.search(term, text, re.IGNORECASE):
                problems.append("unreleased feature named in published text: "
                                "%s (matched /%s/)" % (rel, term))

    # Nothing that looks like a build artifact or a crash dump.
    for root, dirs, names in os.walk(out_dir):
        for n in names:
            if n.endswith((".hprof", ".log", ".jar")) and "gradle-wrapper" not in n:
                rel = os.path.relpath(os.path.join(root, n), out_dir)
                problems.append("build/crash artifact: " + rel.replace("\\", "/"))

    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out_dir")
    ap.add_argument("--compare", metavar="EXISTING_PUBLIC_REPO",
                    help="report what this build adds or removes versus a checkout")
    args = ap.parse_args()

    keep, held = build(args.out_dir)
    print("prepared %s" % args.out_dir)
    print("  published : %d files" % len(keep))
    print("  held back : %d files" % len(held))

    problems = verify(args.out_dir, keep)

    if args.compare:
        try:
            existing = set(tracked_files(args.compare))
        except subprocess.CalledProcessError:
            existing = None
        if existing is not None:
            new = sorted(set(keep) - existing - {".gitignore"})
            gone = sorted(existing - set(keep) - {".gitignore"})
            print("\n  versus %s:" % args.compare)
            print("    added   : %d" % len(new))
            for f in new[:40]:
                print("      + " + f)
            if len(new) > 40:
                print("      ... and %d more" % (len(new) - 40))
            print("    removed : %d" % len(gone))
            for f in gone[:20]:
                print("      - " + f)

    print()
    if problems:
        print("REFUSING: %d problem(s) - DO NOT PUSH" % len(problems))
        for p in problems:
            print("  ! " + p)
        return 1
    print("All checks passed. Safe to commit and push this tree.")
    print("  held back, as intended:")
    for f in held[:6]:
        print("    - " + f)
    print("    ... and %d more internal files" % max(0, len(held) - 6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
