"""Stage the verified client in a throwaway simulator's data container, never the app bundle."""
import hashlib
import io
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

PIN = "c6070977d3da505b6726fd732f496ce124d35c66995f5743e6369ea52a9b55de"


# Publisher overlay on the disposable copy only. The original archive stays byte-for-byte pinned.
OFFLINE_CSP = (
    "default-src 'none'; script-src file: 'unsafe-inline' 'wasm-unsafe-eval'; "
    "style-src file: 'unsafe-inline'; img-src file: data: blob:; media-src file: data: blob:; "
    "font-src file: data:; worker-src file: blob:; connect-src 'none'; "
    "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"
)


def restrict_index(index):
    html = index.read_bytes()
    prefix = b"<!doctype html><head>"
    if not html.startswith(prefix):
        raise ValueError("expected pinned client HTML prologue")
    policy = f'<meta http-equiv="Content-Security-Policy" content="{OFFLINE_CSP}">'.encode("ascii")
    index.write_bytes(prefix + policy + html[len(prefix):])


def stage(archive, documents):
    data = archive.read_bytes()
    if hashlib.sha256(data).hexdigest() != PIN:
        raise ValueError("unexpected Roblosats client digest")
    documents = documents.resolve(strict=True)
    destination = documents / "roblo-preview"
    if destination.exists():
        raise FileExistsError(destination)
    with tempfile.TemporaryDirectory(dir=documents) as tmp:
        root = Path(tmp) / "client"
        root.mkdir()
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for item in z.infolist():
                path = root / item.filename
                if not path.resolve().is_relative_to(root) or item.is_dir():
                    raise ValueError(item.filename)
                path.parent.mkdir(parents=True, exist_ok=True)
                with z.open(item) as src, path.open("xb") as dst:
                    shutil.copyfileobj(src, dst)
        for name in ("index.html", "SOURCE", "LICENSE"):
            if not (root / name).is_file():
                raise ValueError(name)
        restrict_index(root / "index.html")
        os.rename(root, destination)


if __name__ == "__main__":
    stage(*map(Path, sys.argv[1:]))
