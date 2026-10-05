#!/usr/bin/env python3
"""Append commits that "Orders Service Evolution.html" does not list yet.

Hand-written entries live in the page's `C` array. Every other commit on the
first-parent history of HEAD is written into the AUTO-COMMITS block, with its
subject, message and per-file line counts. Commits made by this script's
GitHub Action are skipped. Run from the repository root.
"""
import json
import re
import subprocess
import sys

GUIDE = "Orders Service Evolution.html"
START = "// AUTO-COMMITS:START\n"
END = "// AUTO-COMMITS:END\n"
BOT_EMAIL = "41898282+github-actions[bot]@users.noreply.github.com"


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def commit_entry(sha):
    short, parents, subject, body = git("show", "-s", "--format=%h%x00%p%x00%s%x00%b", sha).split("\x00", 3)
    files = []
    for line in git("show", "--numstat", "--format=", "--first-parent", sha).splitlines():
        added, deleted, path = line.split("\t", 2)
        if added == "-":
            files.append([path, "binary"])
        else:
            files.append([path, f"+{added}" + (f" −{deleted}" if deleted != "0" else "")])
    # Drop trailer lines such as Co-Authored-By from the summary
    paragraphs = [p.strip() for p in body.strip().split("\n\n") if p.strip()]
    paragraphs = [p for p in paragraphs if not re.match(r"^[\w-]+: ", p)]
    summary = " ".join(" ".join(p.split()) for p in paragraphs) or subject
    touches_code = any(path.startswith("src/") for path, _ in files)
    why = ("Added automatically from git history. This commit changes code under src/, "
           "so the architecture diagram may be out of date for it."
           if touches_code else "Added automatically from git history.")
    return {
        "h": short, "s": subject, "lane": "merge" if len(parents.split()) > 1 else "main",
        "sum": summary, "why": why, "add": [], "mod": [],
        "files": files or [["(no file changes)", ""]],
    }


def main():
    html = open(GUIDE, encoding="utf-8").read()
    if START not in html or END not in html:
        sys.exit(f"{GUIDE}: AUTO-COMMITS markers not found")
    head, rest = html.split(START, 1)
    _, tail = rest.split(END, 1)
    known = set(re.findall(r"\bh: '([0-9a-f]{7,})'", head))

    entries = []
    for line in git("log", "--first-parent", "--reverse", "--format=%H %ae", "HEAD").splitlines():
        sha, email = line.split(" ", 1)
        if email == BOT_EMAIL or any(sha.startswith(k) for k in known):
            continue
        entries.append(commit_entry(sha))

    # json.dumps output is valid JS; escape "</" so commit text cannot close the <script> tag
    rows = ",\n".join("  " + json.dumps(e, ensure_ascii=False).replace("</", "<\\/") for e in entries)
    block = "const AUTO = [\n" + rows + "\n];\n" if entries else "const AUTO = [];\n"
    updated = head + START + block + END + tail
    if updated != html:
        open(GUIDE, "w", encoding="utf-8").write(updated)
        print(f"Wrote {len(entries)} automatic entr{'y' if len(entries) == 1 else 'ies'}")
    else:
        print("Guide already up to date")


if __name__ == "__main__":
    main()
