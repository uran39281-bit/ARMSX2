"""Reuse unchanged native binaries for a UI-only update, or verify packaged bytes."""
import argparse
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument("base")
parser.add_argument("destination")
parser.add_argument("--verify", action="store_true")
args = parser.parse_args()
with ZipFile(args.base) as base:
    libraries = {name: base.read(name) for name in base.namelist()
                 if name.startswith("lib/arm64-v8a/") and name.endswith(".so")}
assert "lib/arm64-v8a/libemucore_4k.so" in libraries
assert "lib/arm64-v8a/libemucore_16k.so" in libraries
if args.verify:
    with ZipFile(args.destination) as apk:
        actual = {name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        assert actual == set(libraries), "Native library set changed"
        for name, data in libraries.items():
            assert apk.read(name) == data, f"Native library changed: {name}"
    print(f"Verified {len(libraries)} byte-identical native libraries")
else:
    for name, data in libraries.items():
        relative = Path(name).relative_to("lib")
        assert ".." not in relative.parts
        target = Path(args.destination) / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print(f"Staged {len(libraries)} native libraries")
