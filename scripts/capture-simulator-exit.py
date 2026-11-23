"""Collect only a matching disposable simulator process's structured exit classification."""
import argparse
import json
import re
from pathlib import Path


def classify(directories, bundle, pid, udid, since):
    for directory in directories:
        for path in directory.glob("Kilowallet*.ips"):
            try:
                if path.stat().st_mtime < since or path.stat().st_size > 16 * 1024 * 1024:
                    continue
                text = path.read_text().lstrip()
                obj, end = json.JSONDecoder().raw_decode(text)
                if text[end:].strip():
                    obj = json.loads(text[end:])
                if (obj.get("pid") != pid or obj.get("procName") != "Kilowallet"
                        or obj.get("bundleInfo", {}).get("CFBundleIdentifier") != bundle
                        or f"/Devices/{udid}/" not in obj.get("procPath", "")):
                    continue
                result = {"matched": True}
                for section, key in (("exception", "type"), ("exception", "signal"),
                                     ("termination", "namespace"), ("termination", "code")):
                    val = (obj.get(section) or {}).get(key)
                    if type(val) is int or (isinstance(val, str) and re.fullmatch(r"[A-Z0-9_ -]{1,40}", val)):
                        result[key] = val
                return result
            except (OSError, UnicodeError, ValueError, TypeError, AttributeError):
                continue
    return {"matched": False}


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("bundle")
    p.add_argument("pid", type=int)
    p.add_argument("udid")
    p.add_argument("since", type=int)
    a = p.parse_args()
    root = Path.home() / "Library"
    device = root / "Developer/CoreSimulator/Devices" / a.udid / "data/Library/Logs/CrashReporter"
    print(json.dumps(classify([root / "Logs/DiagnosticReports", device],
                              a.bundle, a.pid, a.udid, a.since), sort_keys=True))
