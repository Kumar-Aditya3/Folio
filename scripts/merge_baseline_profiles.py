"""Merge actual AndroidX collector output after a direct instrumentation run.

Useful when running Gradle alongside an emulator exhausts host memory. Build the
profiling APKs first, stop Gradle, run the collector, and pull its text outputs.
Startup rules also belong in the install-time Baseline Profile.
"""
import argparse
from pathlib import Path
import re


def merge(paths):
    rules = {}
    for path in paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            match = re.fullmatch(r"([HSP]*)(L\S+)", line)
            if not match:
                raise ValueError(f"Invalid profile rule in {path}: {line[:120]}")
            flags, signature = match.groups()
            rules.setdefault(signature, set()).update(flags)
    if not rules:
        raise ValueError("The collector output contains no profile rules")
    return "\n".join("".join(f for f in "HSP" if f in flags) + signature
                     for signature, flags in sorted(rules.items())) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, action="append", required=True)
    parser.add_argument("--startup", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, default=Path("androidApp/src/release/generated/baselineProfiles"))
    args = parser.parse_args()
    baseline = merge(args.baseline + args.startup)
    startup = merge(args.startup)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, content in (("baseline-prof.txt", baseline), ("startup-prof.txt", startup)):
        destination = args.output / name
        destination.write_text(content, encoding="utf-8", newline="\n")
        print(f"{destination}: {len(content.splitlines())} rules")


if __name__ == "__main__":
    main()
