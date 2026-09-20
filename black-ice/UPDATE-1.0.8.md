# Black Ice 1.0.8

- Home music: user-supplied Submerged Meridian recording (unchanged MP3).
- Menu audio: user-supplied Black Ice UI movement pack. Vertical/horizontal navigation, confirm, back, boundary and slider clips replace Home defaults; core navigation, selection, back, toggles and popup clips also replaced.
- Home background: drifting light fields and sparse moving particles. Reduced-motion and background pause apply.
- Performance settings: explicit Snapdragon 662 Graphics and Performance profiles at native resolution. Performance trades accuracy for lower CPU/GPU cost; Graphics restores normal EE timing and synchronous readbacks. No FPS guarantee.
- Overlay settings: opt-in Black Ice hardware monitor. CPU identity, temperature, aggregate usage and peak current core MHz; GPU identity, temperature, readable KGSL usage and clock; used/total system RAM and FPS. RAM MHz and inaccessible sensors display N/A. Read-only, once per second, only during active gameplay. OSD Off hides it.

Device validation needed: test BIOS flow, audio focus/background silence, both preset modes with multiple games, and telemetry on Mangmi Air X. CPU/GPU sysfs access varies by firmware; no elevated permissions requested.
