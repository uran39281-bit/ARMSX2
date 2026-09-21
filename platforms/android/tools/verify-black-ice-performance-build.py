"""Fail an experiment if CMake ignored its requested configuration."""
import sys
from pathlib import Path

root, mode, library, page_size = sys.argv[1:]
assert mode in ("baseline", "candidate")
expected = {
    "CMAKE_BUILD_TYPE": "Release" if mode == "candidate" else "Debug",
    "LTO_PCSX2_CORE": "ON" if mode == "candidate" else "OFF",
    "BLACKICE_BENCHMARK": "ON",
    "ARMSX2_EMUCORE_LIBRARY_NAME": library,
    "ARMSX2_ANDROID_HOST_PAGE_SIZE": page_size,
}
checked = 0
for path in Path(root).rglob("CMakeCache.txt"):
    cache = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith(("#", "//")) and "=" in line and ":" in line.split("=", 1)[0]:
            key, value = line.split("=", 1)
            cache[key.split(":", 1)[0]] = value
    if cache.get("ARMSX2_EMUCORE_LIBRARY_NAME") != library:
        continue
    for key, value in expected.items():
        assert cache.get(key) == value, f"{path}: {key}={cache.get(key)!r}, expected {value!r}"
    for key in ("CMAKE_C_FLAGS", "CMAKE_CXX_FLAGS"):
        flags = cache.get(key, "").split()
        assert "-march=armv8-a" in flags and "-moutline-atomics" in flags, (path, key, flags)
    checked += 1
assert checked, "No matching native CMake cache: experiment was not built from source"
print(f"Verified {mode}: {library}, {page_size}, {expected['CMAKE_BUILD_TYPE']}, LTO={expected['LTO_PCSX2_CORE']}")
