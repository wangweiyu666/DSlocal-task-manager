"""Inventory source test methods and validate the explicit Gradle profile patterns."""
from pathlib import Path
import fnmatch
import re

root = Path(__file__).resolve().parents[2]
tests = []
for source_set in ("test", "testConnected"):
    for path in sorted((root / "app/src" / source_set).rglob("*.kt")):
        source = path.read_text(encoding="utf-8-sig")
        package = re.search(r"^package ([\w.]+)", source, re.M)
        methods = re.findall(r"@Test\b(?:\([^\n]*\))?[\s\S]*?\bfun\s+(`[^`]+`|\w+)\s*\(", source)
        if methods:
            name = package[1] + "." + path.stem
            tests.extend((name, method.strip("`")) for method in methods)

for profile in ("daily", "release", "full"):
    patterns = ["*"] if profile == "full" else [
        line.strip() for line in (root / f"scripts/testing/android-{profile}.txt").read_text().splitlines()
        if line.strip() and not line.startswith("#")
    ]
    for pattern in patterns:
        if not any(fnmatch.fnmatchcase(name, pattern) for name, _ in tests):
            raise SystemExit(f"Unmatched {profile} pattern: {pattern}")
    selected = [(name, method) for name, method in tests if any(fnmatch.fnmatchcase(name, p) for p in patterns)]
    print(f"{profile}: {len(selected)} source methods / {len(set(name for name, _ in selected))} classes")
print("Static inventory only; parameterized runtime counts and pass/fail require JUnit XML.")
