"""Prepare an offline RobloClient resource directory; does not install it in any app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

# Hash of the sorted {relative POSIX path: SHA-256} map for all 113 reviewed kw5
# files after the publisher CSP overlay. Use the same pin for packaging and verification.
TREE_SHA256 = "f652c1afde624fc450f689fba9fb39d4a0f96093d7cb08957b481227454ade7f"


def verify(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError("missing or linked resource directory")
    files = {}
    dirs = set()
    for p in root.rglob("*"):
        if p.is_symlink():
            raise ValueError("linked resource")
        if p.is_dir():
            dirs.add(p)
        elif p.is_file():
            files[p.relative_to(root).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
        else:
            raise ValueError("unexpected resource type")
    expected_dirs = {parent for name in files for parent in (root / name).parents
                     if parent != root and root in parent.parents}
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode("ascii")
    if dirs != expected_dirs or hashlib.sha256(encoded).hexdigest() != TREE_SHA256:
        raise ValueError("resource tree does not match the reviewed offline client")


def pack(archive, output):
    stage = Path(__file__).resolve().parents[1] / "stage-roblo-preview.py"
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        subprocess.run([sys.executable, "-B", str(stage), str(archive), tmp], check=True)
        tree = Path(tmp) / "roblo-preview"
        verify(tree)
        output.mkdir()  # An existing output must never be replaced.
        try:
            os.rename(tree, output / "RobloClient")
        except OSError:
            output.rmdir()
            raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    build = modes.add_parser("pack")
    build.add_argument("archive", type=Path)
    build.add_argument("new_output", type=Path)
    check = modes.add_parser("verify")
    check.add_argument("directory", type=Path)
    args = parser.parse_args()
    if args.mode == "pack":
        pack(args.archive, args.new_output)
    else:
        verify(args.directory)
