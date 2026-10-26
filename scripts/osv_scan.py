#!/usr/bin/env python3
"""S11: checks every dependency in the gradle.lockfile files against the OSV database.

Fails (exit 1) on any HIGH or CRITICAL finding in a dependency that ships in the APK (a runtime
classpath). Findings in build-only tooling (compilers, lint, the on-device test runner) and lower
severities are listed as warnings.
Needs network access to api.osv.dev, so it runs in CI, not on the build machine's offline path.

Usage: osv_scan.py [lockfile ...]   (default: every **/gradle.lockfile)
"""
import glob
import json
import sys
import urllib.request

API = "https://api.osv.dev/v1"


def post(path, body):
    req = urllib.request.Request(f"{API}/{path}", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def get(path):
    with urllib.request.urlopen(f"{API}/{path}", timeout=60) as r:
        return json.load(r)


def ships(configs):
    return any(c.endswith("RuntimeClasspath") and "Test" not in c for c in configs)


def packages(lockfiles):
    """{(name, version): shipped?}"""
    found = {}
    for path in lockfiles:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or line.startswith("empty="):
                    continue
                coords, _, configs = line.partition("=")
                parts = coords.split(":")
                if len(parts) == 3:
                    key = (f"{parts[0]}:{parts[1]}", parts[2])
                    found[key] = found.get(key, False) or ships(configs.split(","))
    return found


def severity(vuln):
    """GHSA-style label when OSV has one; otherwise None (reported, not failing)."""
    label = (vuln.get("database_specific") or {}).get("severity")
    for affected in vuln.get("affected", []):
        label = label or (affected.get("database_specific") or {}).get("severity")
    return label.upper() if isinstance(label, str) else None


def main():
    lockfiles = sys.argv[1:] or glob.glob("**/gradle.lockfile", recursive=True)
    shipped = packages(lockfiles)
    pkgs = sorted(shipped)
    if not pkgs:
        sys.exit("no dependencies found in lockfiles")
    findings = []
    for start in range(0, len(pkgs), 500):
        chunk = pkgs[start:start + 500]
        queries = [{"package": {"name": n, "ecosystem": "Maven"}, "version": v} for n, v in chunk]
        results = post("querybatch", {"queries": queries})["results"]
        for (name, version), result in zip(chunk, results):
            for v in result.get("vulns", []):
                detail = get(f"vulns/{v['id']}")
                findings.append((severity(detail), shipped[(name, version)], v["id"], f"{name}:{version}", detail.get("summary", "")))
    blocking = [f for f in findings if f[0] in ("HIGH", "CRITICAL") and f[1]]
    for sev, in_apk, vid, pkg, summary in findings:
        fail = sev in ("HIGH", "CRITICAL") and in_apk
        where = "ships in APK" if in_apk else "build tooling only"
        print(f"{'FAIL' if fail else 'WARN'} {sev or 'UNRATED'} {vid} {pkg} ({where}): {summary}")
    print(f"{len(pkgs)} dependencies checked, {len(findings)} finding(s), {len(blocking)} high or critical in the APK")
    sys.exit(1 if blocking else 0)


if __name__ == "__main__":
    main()
