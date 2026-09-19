#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/armsx2/ui/settings/PerformanceTab.kt")
text = path.read_text(encoding="utf-8")

if "import com.armsx2.config.snapdragon662Preset" not in text:
    text = text.replace(
        "import com.armsx2.config.Settings\n",
        "import com.armsx2.config.Settings\nimport com.armsx2.config.snapdragon662Preset\n",
        1,
    )

old = '''            // -1 = no preset matches (custom): no segment highlighted.\n            val idx = when (s) { safe -> 0; fast -> 1; lowEnd -> 2; else -> -1 }\n            SegmentedRow(\n'''
new = '''            // Snapdragon 662 / Adreno 610: aggressive native-resolution tune.\n            val sd662 = s.snapdragon662Preset()\n            // -1 = no preset matches (custom): no segment highlighted.\n            val idx = when (s) { safe -> 0; fast -> 1; lowEnd -> 2; sd662 -> 3; else -> -1 }\n            SegmentedGridRow(\n'''
if old not in text:
    raise SystemExit("Performance preset anchor changed; refusing to patch blindly")
text = text.replace(old, new, 1)

old = '                options = listOf(str("perf.speedhackProfile.optimal"), str("perf.speedhackProfile.fast"), str("perf.speedhackProfile.lowEnd")),\n'
new = '                options = listOf(str("perf.speedhackProfile.optimal"), str("perf.speedhackProfile.fast"), str("perf.speedhackProfile.lowEnd"), "SD 662 Max FPS"),\n'
if old not in text:
    raise SystemExit("Performance preset options anchor changed")
text = text.replace(old, new, 1)

old = '''                selectedIndex = idx,\n                onChange = { when (it) { 0 -> apply(safe); 1 -> apply(fast); 2 -> apply(lowEnd) } },\n'''
new = '''                selectedIndex = idx,\n                columns = 2,\n                onChange = { when (it) { 0 -> apply(safe); 1 -> apply(fast); 2 -> apply(lowEnd); 3 -> apply(sd662) } },\n'''
if old not in text:
    raise SystemExit("Performance preset action anchor changed")
text = text.replace(old, new, 1)

help_anchor = '        HelpText(str("perf.speedhackProfile.help"))\n'
help_replacement = help_anchor + '        HelpText("SD 662 Max FPS is tuned for Snapdragon 662 / Adreno 610. It lowers rendering cost and uses aggressive CPU speedhacks; if a game has visual or timing problems, switch back to Low-End or Fast.")\n'
if "SD 662 Max FPS is tuned" not in text:
    if help_anchor not in text:
        raise SystemExit("Performance preset help anchor changed")
    text = text.replace(help_anchor, help_replacement, 1)

path.write_text(text, encoding="utf-8")
print("Applied Black Ice Snapdragon 662 performance preset UI patch")
