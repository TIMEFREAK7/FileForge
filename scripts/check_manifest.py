#!/usr/bin/env python3
"""Release-blocking manifest checks on a built APK (spec S1, S2, S3, S9).

Reads the final merged manifest from the APK itself, so permissions or components
added by libraries are caught too. Exit code 0 = pass, 1 = a check failed.

Usage: check_manifest.py <apk> [--allow-exported scripts/exported-allowlist.txt]
"""
import argparse
import glob
import os
import re
import subprocess
import sys

NETWORK = {  # S1
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
}
BROAD_STORAGE = {  # S2: the app reads files only through the Storage Access Framework
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
}
COMPONENTS = {"activity", "activity-alias", "service", "receiver", "provider"}
ANDROID_ATTR = re.compile(r"A: http://schemas\.android\.com/apk/res/android:(\w+)\(0x[0-9a-f]+\)=(.*)")


def aapt2():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        sys.exit("ANDROID_HOME is not set")
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", "aapt2")))
    if not found:
        sys.exit("aapt2 not found under $ANDROID_HOME/build-tools")
    return found[-1]


def run(*args):
    return subprocess.run([aapt2(), *args], check=True, capture_output=True, text=True).stdout


def attr_value(raw):
    quoted = re.match(r'"([^"]*)"', raw)
    return quoted.group(1) if quoted else raw.split()[0]


def parse_manifest(apk):
    """Returns (permissions, application attrs, [(tag, attrs)] components) from the APK's manifest."""
    perms = set(re.findall(r"name='([^']+)'", run("dump", "permissions", apk)))
    app_attrs, components = {}, []
    current, current_indent = None, -1
    for line in run("dump", "xmltree", "--file", "AndroidManifest.xml", apk).splitlines():
        indent = len(line) - len(line.lstrip())
        text = line.strip()
        if text.startswith("E: "):
            tag = text.split()[1]
            if tag == "application":
                current, current_indent = app_attrs, indent
            elif tag in COMPONENTS:
                attrs = {}
                components.append((tag, attrs))
                current, current_indent = attrs, indent
            elif indent <= current_indent:
                current, current_indent = None, -1
        elif current is not None and indent == current_indent + 2:
            m = ANDROID_ATTR.match(text)
            if m:
                current[m.group(1)] = attr_value(m.group(2))
    return perms, app_attrs, components


def check(apk, allowlist):
    perms, app_attrs, components = parse_manifest(apk)
    failures = []
    for p in sorted(perms & NETWORK):
        failures.append(f"S1: network permission {p}")
    for p in sorted(perms & BROAD_STORAGE):
        failures.append(f"S2: broad storage permission {p}")
    for tag, attrs in components:
        # targetSdk 31+ makes every component with an intent filter declare exported explicitly.
        if attrs.get("exported") == "true" and attrs.get("name") not in allowlist:
            failures.append(f"S3: {tag} {attrs.get('name')} is exported but not on the allow-list")
    if app_attrs.get("allowBackup") != "false":
        failures.append("S9: android:allowBackup is not false")
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--allow-exported", help="file with one component class name per line")
    args = parser.parse_args()
    allowlist = set()
    if args.allow_exported:
        with open(args.allow_exported) as f:
            allowlist = {line.strip() for line in f if line.strip() and not line.startswith("#")}
    failures = check(args.apk, allowlist)
    for f in failures:
        print(f"FAIL {f}")
    print(f"{os.path.basename(args.apk)}: {'FAILED' if failures else 'passed'} manifest checks")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
