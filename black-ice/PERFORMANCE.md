# Black Ice native performance experiment

This is an A/B experiment, not a claim of 60 FPS. The shipping 1.0.9 APK
reuses the 1.0.4 native libraries. That build used Debug CMake configuration
with `-O3`. This experiment rebuilds native code instead of reusing those bytes.

| Setting | baseline | candidate |
| --- | --- | --- |
| Native configuration | Debug, -O3 | Release, -O3 |
| Core link-time optimization | Off | On |
| Instruction baseline | ARMv8-A with outlined atomics | Same |
| Native page sizes | 4K and 16K | Same |
| Java/Kotlin build | GithubDebug | Same |
| Compile/target SDK | 36 (available CI toolchain) | Same |
| Performance sampling | Every core metrics update (~500ms) | Same |

Both packages are separate from `dev.aether.preview` and have their own data.
They require their own BIOS/game selection. Do not uninstall the stable app.
These APKs use disposable test keys and are not production updates.
Baseline and candidate are identified by their APK filenames and package IDs
`dev.aether.blackice.bench.baseline` / `dev.aether.blackice.bench.candidate`.

The normal build is unchanged. The experiment has no new speedhacks, no
frame generation, and no global fast-math flags. The existing SD662 preset is
available in both. LTO/debug-check changes need on-device correctness testing.

## Record a comparison

Use the same game region/revision, scene, graphics settings, speed limit,
power mode, and starting device temperature. Copying save files is optional;
do not compare incompatible save states. Test on actual Snapdragon 662 hardware.

1. Install both experimental APKs. Set the same BIOS/game and settings in each.
2. Start one build, open the game, then begin recording before playing the
   repeatable scene. Use only one emulator at a time.
3. Run `adb logcat -v raw -T 1 BlackIceBench:I '*:S' > baseline.log`.
4. Play the scene without pausing for at least 155 seconds, then stop logcat
   with Ctrl+C. Repeat for the candidate as `candidate.log`.
5. Analyze with:

```sh
python3 black-ice/tools/benchmark.py baseline.log --warmup 30 --duration 120
python3 black-ice/tools/benchmark.py candidate.log --warmup 30 --duration 120
```

The parser separates gaps over two seconds, game changes, and timestamp resets.
If a recording includes menus, a pause or a different scene, capture again or
explicitly select a continuous `--segment`. A brief pause under two seconds
may not be detected; avoid all pauses during measurement. A short capture is
rejected, not silently compared against a longer run.

Compare emulation speed, internal FPS where available, EE/GS thread usage,
and average frame time. Refresh FPS is not necessarily the game's unique-frame
rate. The report contains sample means, not 1% lows or per-frame percentiles.
Usage measures emulator threads, not whole-device CPU/GPU utilization.
The benchmark adds no GPU timing queries or sysfs sensor polling.

Repeat in alternating baseline/candidate order after cooling. Then run each
for at least 10 minutes to check sustained speed, graphics, audio, controls,
and saves. Reject the candidate if correctness or sustained performance regresses.

## Build

The `Black Ice native performance experiment` workflow builds both variants
on the `black-ice-performance` branch. It uses freshly compiled native libraries.
Locally, with the Android/Rust toolchains and debug key installed:

```sh
cd platforms/android
bash tools/build-universal-page-apk.sh --performance-candidate \
  --application-id dev.aether.blackice.bench.candidate /tmp/candidate.apk
```

Use `--performance-baseline` and the matching baseline package for comparison.
Device results have not been collected yet. Do not promote this experiment to
the stable branch until the comparison and correctness checks pass.
