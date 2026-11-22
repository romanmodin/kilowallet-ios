"""Create and verify the isolated device product's metadata; never modify an app in place."""
import argparse
import plistlib
from pathlib import Path

BUNDLE = "dev.kilowallet.tests.KilobotBook"
ONION = "kiloblo3ibbey3wridofauizwb467y5osfaycxoecgsf66mggsrw6ryd.onion"
ATS = {"NSExceptionDomains": {ONION: {"NSExceptionAllowsInsecureHTTPLoads": True}}}
OMIT = ("UIBackgroundModes", "BGTaskSchedulerPermittedIdentifiers", "NSCameraUsageDescription",
        "NSFaceIDUsageDescription", "ITSAppUsesNonExemptEncryption")

def verify(info, bundle):
    if (info.get("NSAppTransportSecurity") != ATS or info.get("CFBundleIdentifier") != bundle
            or info.get("CFBundleDisplayName") != "Kilobot QA" or any(k in info for k in OMIT)):
        raise ValueError("Unexpected bundle, network policy or capabilities on Kilobot QA build")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["prepare", "verify"])
    p.add_argument("path", type=Path)
    args = p.parse_args()
    if args.action == "prepare":
        info = plistlib.loads(Path("iosApp/iosApp/Info.plist").read_bytes())
        for k in OMIT:
            info.pop(k, None)
        info.update(CFBundleDisplayName="Kilobot QA", NSAppTransportSecurity=ATS)
        verify(info, "$(PRODUCT_BUNDLE_IDENTIFIER)")
        args.path.write_bytes(plistlib.dumps(info))
    else:
        verify(plistlib.loads(args.path.read_bytes()), BUNDLE)
