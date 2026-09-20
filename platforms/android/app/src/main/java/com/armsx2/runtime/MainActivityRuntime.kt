Warning: truncated output (original token count: 77236)
Total output lines: 5413

package com.armsx2.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.armsx2.BuildConfig
import com.armsx2.EmuState
import com.armsx2.FilenameParser
import com.armsx2.GameInfo
import com.armsx2.MemoryCardBackup
import com.armsx2.PlayTime
import com.armsx2.i18n.str
import com.armsx2.input.ControllerMappings
import com.armsx2.input.SoftKeyboard
import com.armsx2.runtime.MainActivityRuntime.Companion.internalBiosDir
import com.armsx2.runtime.MainActivityRuntime.Companion.romsDirs
import com.armsx2.ui.Colors
import com.armsx2.ui.InGameOverlay
import com.armsx2.ui.WindowImpl
import compose.icons.LineAwesomeIcons
import compose.icons.lineawesomeicons.Android
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kr.co.iefriends.pcsx2.MainActivity
import kr.co.iefriends.pcsx2.NativeApp
import org.libsdl.app.HIDDeviceManager
import org.libsdl.app.SDLControllerManager
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import androidx.core.net.toUri
import androidx.core.content.edit

private const val LIGHT_NAVIGATION_BAR_SCRIM = 0x04000000
private const val DARK_NAVIGATION_BAR_SCRIM = 0x0A000000

private const val STICK_DEAD = 0.15f
// Trigger (L2/R2) dead-low: much smaller than the stick deadzone — triggers want fine
// control and full range. Just enough to swallow resting-axis noise on cheaper / non-Xbox
// pads; the value is re-normalized past it (see sendTrigger) so pressure ramps smoothly
// from 0 instead of flickering on/off at a hard threshold — the jitter those pads showed.
private const val TRIGGER_DEAD = 0.06f
// Travel past which a trigger counts as the L2/R2 BUTTON being held rather than a pressure
// value — what the bind capture records, what fires a trigger-bound hotkey, and what makes a
// held trigger a combo modifier. Well above TRIGGER_DEAD: pressure ramps from a brush, but
// "pressed" should mean a deliberate press.
private const val TRIGGER_DIGITAL_THRESHOLD = 0.5f
// Threshold past which a stick remapped to D-pad / face buttons registers as a
// digital press. Higher than STICK_DEAD so a resting/wobbling stick doesn't fire.
private const val STICK_DIGITAL_THRESHOLD = 0.5f
// Off-axis bleed gate for the RADIAL analog path (accumStickRadial): the minor axis is
// dropped when it's below this fraction of the major axis, so a near-cardinal push on a
// stick that isn't perfectly centered on the other axis doesn't leak a phantom second
// direction ("up also presses right"). 0.15 ≈ snaps only ~<9° diagonals to the cardinal;
// genuine diagonals (minor axis well above this) pass through untouched.
private const val STICK_CROSS_GATE = 0.15f
private const val UI_NAV_DEAD = 0.20f
private const val UI_NAV_RELEASE_DEAD = 0.06f
private const val UI_HAT_DEAD = 0.50f
private const val UI_NAV_DOMINANCE = 1.35f
private const val UI_OVERLAY_RELEASE_MS = 80L
private const val UI_KEY_AXIS_SUPPRESS_MS = 220L
// Hold-to-repeat cadence for controller menu navigation: first auto-repeat
// after the initial hold, then steady repeats while the stick/dpad is held.
private const val NAV_REPEAT_INITIAL_MS = 340L
private const val NAV_REPEAT_INTERVAL_MS = 110L

// During a hotkey capture, a 2nd keycode arriving within this window of the
// first DOWN is treated as part of the SAME physical press (some controllers
// emit two codes per button) rather than a deliberate modifier+key combo.
// A real combo is a held first button + a later second press, well past this.
private const val COMBO_MIN_GAP_MS = 40L

open class MainActivityRuntime : ComponentActivity() {
    private var lastUiNavCode = 0
    private var lastUiNavAt = 0L
    private var lastUiNavWasAxis = false
    private var overlayAxisX = 0
    private var overlayAxisY = 0
    private var overlayHorizontalReleaseAt = 0L
    private var libraryAxisX = 0
    private var libraryAxisY = 0

    companion object {
        var instance: MainActivityRuntime? = null
        lateinit var prefs: SharedPreferences

        // Tap-to-hold state (#612). In the companion rather than beside handleTurbo because the
        // boot path that has to clear it -- a latch must not outlive the game it was set in --
        // runs here, while the dispatch that sets it is an instance method. Instance methods see
        // companion members, so both reach it.
        private val latchDown = HashSet<Long>()  // physical buttons currently held, port|physical
        private val latchHeld = HashSet<Long>()  // PS2 targets currently latched ON, port|target

        /** A latch must not outlive the game it was set in. Called on each fresh start. */
        fun clearLatches() {
            latchDown.clear()
            latchHeld.clear()
        }

        /**
         * Release whatever is latched right now, through the normal dispatch so the analog and
         * pressure bookkeeping unwinds with it.
         *
         * Turning tap-to-hold off for a button that is currently HELD would otherwise strand it
         * pressed: the second tap that would have released it no longer toggles anything, so the
         * game sees the button down forever. Called whenever the setting changes.
         */
        fun releaseLatches() {
            val held = latchHeld.toList()
            clearLatches()
            val act = instance ?: return
            held.forEach { key ->
                act.sendKeyAction(
                    KeyEventType.KeyUp,
                    (key and 0xffffffffL).toInt(),   // target, as packed by turboMapKey
                    (key ushr 32).toInt(),           // port
                )
            }
        }
        val setupComplete = mutableStateOf(false)
        // Set at launch when a restored-but-unusable setup is detected (Auto Backup
        // brought back prefs incl. setupComplete, but the ROMs folder permission
        // didn't survive the reinstall). Drives a one-time explanatory toast; the
        // wizard is re-shown so the user can re-grant folder access.
        val setupRecoveryNeeded = mutableStateOf(false)
        val setupEditorVisible = mutableStateOf(false)
        val blackIceBiosSetup = mutableStateOf(false)
        val nativeReady = mutableStateOf(false)
        // Tree URI of the user-picked PCSX2 system folder (where bios/,
        // memcards/, etc. should live). Persisted as `systemDir` pref.
        // When unset, emucore falls back to getExternalFilesDir(null)
        // (Android/data/<package>/files).
        val systemDir = mutableStateOf<String?>(null)
        val bios = mutableStateOf<String?>(null)
        // Tree URI of the folder the user picked their BIOS from. Persisted
        // separately from `bios` (the path of the copied private file) so
        // re-entering setup can re-scan the original folder without
        // forcing the user to re-pick.
        val biosDir = mutableStateOf<String?>(null)

        /** Persisted list of ROM-folder tree URIs. Replaces the legacy
         *  single-folder `romsDir` pref (kept readable as a one-element
         *  list at load time). The setup wizard's ROMs page lets the user
         *  add/remove entries; the library scans every entry and merges
         *  results de-duplicated by URI. Empty list = no library. */
        val romsDirs = mutableStateOf<List<String>>(emptyList())

        /** Update [romsDirs] state and persist as JSON. Drops the legacy
         *  single-string pref so we don't keep two views in sync forever. */
        fun setRomsDirs(dirs: List<String>) {
            romsDirs.value = dirs
            val arr = org.json.JSONArray()
            for (d in dirs) arr.put(d)
            prefs.edit {
                putString("romsDirs", arr.toString())
                    .remove("roms")
            }
        }

        // Default backend is "auto" — emucore's GSUtil::GetPreferredRenderer
        // picks at runtime per device. The setup wizard no longer asks; the
        // in-game overlay's Renderer tab is where users override (OpenGL /
        // Software cycle, plus Mali/Adreno-specific paths once those land).
        // `upscale` (1.0..5.0) still persists; it's exposed in the in-game
        // overlay's Renderer tab.
        val renderer = mutableStateOf("auto")
        val upscale = androidx.compose.runtime.mutableFloatStateOf(1.0f)

        /** Active custom Vulkan driver id (matches `CustomDriver.InstalledDriver.id`).
         *  Null = system Vulkan loader. Set from the setup wizard's driver
         *  chip. Applied to native via CustomDriver.applyToNative inside
         *  applyRendererPrefs BEFORE runVMThread enters MTGS::Open, which
         *  is when Vulkan::LoadVulkanLibrary reads the pinned path. */
        val customDriverId = mutableStateOf<String?>(null)

        private val eDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val eScope = CoroutineScope(eDispatcher)

        /**
         * Scope for boot-time synthetic input that must run WHILE the VM is booting.
         *
         * It cannot be [eScope]: [eDispatcher] is a single thread, and start()'s `invoke { }` block
         * occupies it with the BLOCKING `NativeApp.runVMThread()` for the entire game session.
         * Coroutine dispatch is cooperative, so a job launched on [eScope] during boot is starved
         * until the game EXITS and the thread frees — which is exactly why the Auto-Progressive-Scan
         * Triangle+Cross hold never fired for anyone (it "released" 15 s after a long-dead VM). Run it
         * on an independent pool instead — the same shape EmuCoreX uses (`Dispatchers.Default`). Every
         * JNI it touches (setPadButton/hasActiveVM/getGameCRC) is already thread-safe.
         */
        private val auxScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

        /**
         * Resolve the user-chosen system folder (a SAF tree URI persisted
         * as `systemDir`) to a POSIX path emucore can use as
         * `EmuFolders::DataRoot`. Memcards / savestates / configs land
         * under it.
         *
         * Tree URIs from OpenDocumentTree look like
         *   content://com.android.externalstorage.documents/tree/primary%3APCSX2
         * The "primary:" prefix means the volume is the primary external
         * storage (`/storage/emulated/0`); other prefixes are SD-card or
         * removable volume IDs which mount under `/storage/<volumeId>`.
         *
         * Returns null when systemDir is unset, malformed, or this
         * Android build can't translate the tree URI (rare). Caller
         * falls back to the app's externalFilesDir in that case.
         *
         * Caveat: emucore's POSIX FileSystem APIs require the resolved path to
         * be writable without broad shared-storage privileges. On modern
         * Android, that generally means app-private storage.
         */
        fun systemDirPosix(): String? {
            val v = systemDir.value ?: return null
            // Volume-choice model stores an absolute app-specific path directly
            // (e.g. the SD card's Android/data/<pkg>/files). Legacy installs may
            // still hold a SAF tree-URI string; resolve those the old way.
            return if (v.startsWith("content://")) resolveTreeUriToPosix(v) else v
        }

        /** `<DataRoot>/inputprofiles/` — the portable folder both touch-layout and
         *  controller-mapping profiles mirror themselves into, so they survive a
         *  data-folder move and can be shared or hand-dropped. Null when no system
         *  dir is configured yet; created on demand.
         *
         *  Lives here rather than in either profile store because BOTH need it and
         *  the fallback below is the subtle part: systemDirPosix() is null for the
         *  DEFAULT (private app folder), so it falls back to getExternalFilesDir,
         *  which is exactly where the native core puts EmuFolders::InputProfiles.
         *  Two copies of that reasoning would be one copy too many. */
        fun inputProfilesDir(): File? {
            val root = systemDirPosix()
                ?: instance?.applicationContext?.getExternalFilesDir(null)?.absolutePath
                ?: return null
            val dir = File(root, "inputprofiles")
            if (!dir.exists()) runCatching { dir.mkdirs() }
            return if (dir.isDirectory) dir else null
        }

        /** The host: filesystem root, matching EmuFolders::DataRoot/hostfs on the native side.
         *  Same fallback as [inputProfilesDir] and for the same reason. Created on demand so it
         *  is already there when someone goes looking for it. */
        fun hostfsDir(): File? {
            // Asked of the core, NOT rebuilt here. EmuFolders::DataRoot and systemDirPosix()
            // are different paths whenever the data folder sits on an SD card, and deriving
            // this locally put the ISO extraction and the ELF copy in separate folders.
            val fromCore = runCatching { kr.co.iefriends.pcsx2.NativeApp.getHostfsDir() }.getOrNull()
            if (!fromCore.isNullOrBlank()) {
                val dir = File(fromCore)
                if (!dir.exists()) runCatching { dir.mkdirs() }
                if (dir.isDirectory) return dir
            }
            // Native not up yet (library scan can run before the core initialises).
            val root = systemDirPosix()
                ?: instance?.applicationContext?.getExternalFilesDir(null)?.absolutePath
                ?: return null
            val dir = File(root, "hostfs")
            if (!dir.exists()) runCatching { dir.mkdirs() }
            return if (dir.isDirectory) dir else null
        }

        /** App-specific data dir on a removable/secondary volume (SD card),
         *  e.g. /storage/<volId>/Android/data/<pkg>/files. Always raw-writable
         *  by the native core with NO permission under scoped storage, which is
         *  why it works on the Play build where arbitrary folders cannot.
         *  getExternalFilesDirs()[0] is primary/internal; [1..] are removable
         *  volumes (entries may be null while a card is unmounting). Returns the
         *  first usable secondary path, or null when no SD card is present. */
        fun sdCardDataDir(context: Context): String? {
            val dirs = context.getExternalFilesDirs(null)
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                return d.absolutePath
            }
            return null
        }

        /** Directory holding the configured BIOS file, used by
         *  NativeApp.initializeOnce to point EmuFolders::Bios at the real
         *  BIOS location. Null when no BIOS is configured yet —
         *  initializeOnce then falls back to [internalBiosDir]. */
        fun biosFolderPosix(): String? =
            bios.value?.takeIf { it.isNotEmpty() }?.let { File(it).parent }

        /** App-private BIOS folder, ALWAYS readable by the native core regardless
         *  of the chosen data root. The BIOS must live here (NOT under a custom /
         *  SD systemDir): on Android 11+ the native FileSystem APIs can't reliably
         *  open a BIOS that sits on a removable volume or a SAF-picked folder, so a
         *  game booted with the data root on SD failed VM init (BIOS load) and
         *  bounced back to the library. This mirrors the design documented in
         *  native-lib initialize() ("p_szbiosfolder is always externalFilesDir/bios").
         *  Memcards / saves / configs still follow the chosen data root. */
        fun internalBiosDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.dataDir, "bios")

        /** URI-string-independent POSIX resolver. Pulled out of
         *  systemDirPosix so the setup wizard can probe a freshly-picked
         *  URI for writability before persisting it. Returns null if the
         *  URI is malformed or its volume ID isn't translatable. */
        fun resolveTreeUriToPosix(uriString: String?): String? {
            val raw = uriString ?: return null
            val uri = try {
                raw.toUri() } catch (_: Exception) { return null }
            val docId = try {
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) { null } ?: return null
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val (volumeId, relPath) = parts
            return when (volumeId) {
                "primary" -> "/storage/emulated/0/$relPath"
                else -> "/storage/$volumeId/$relPath"
            }
        }

        /**
         * Probe the resolved POSIX path for emucore-compatible write
         * access. Creates a `.armsx2-write-probe` file, deletes it,
         * returns true on success.
         *
         * Catches the scoped-storage trap: Android lets the SAF tree-URI
         * permission survive the picker, so reads work, but raw `fopen`/`mkdir`
         * from emucore can still fail with EACCES during memcard / savestate /
         * config generation. We probe up-front so the wizard can refuse to
         * advance and keep writable emulator data in app-private storage.
         */
        fun validateSystemDirWritable(posixPath: String): Boolean {
            return try {
                val dir = File(posixPath)
                if (!dir.exists() && !dir.mkdirs()) return false
                if (!dir.isDirectory) return false
                val probe = File(dir, ".armsx2-write-probe")
                val ok = probe.createNewFile()
                if (ok) probe.delete()
                ok
            } catch (_: Exception) {
                false
            }
        }

        val surface = mutableStateOf<EmulationSurface?>(null)

        @JvmField
        val eState = mutableStateOf(EmuState.STOPPED)

        // Active quick save/load slot (0-9), cycled by the "Cycle Save Slot"
        // hotkey. Quick Save/Load State hotkeys read this so users aren't pinned
        // to slot 0.
        val currentSaveSlot = androidx.compose.runtime.mutableIntStateOf(0)

        // Limiter mode fast-forward engages. Unlimited (3), NOT Turbo (1): Turbo caps at
        // EmulationSpeed.TurboScalar (2.0x) and reporters consistently saw no speed-up from
        // it, while "frame limit off" — which is this same mode 3 — visibly fast-forwarded.
        // Use the path that demonstrably works instead of shipping a second one that doesn't.
        const val FF_LIMITER_MODE = 3

        // Fast-forward SPEED slider (in-game pause menu, under Frame Limit). Stored as an integer
        // multiplier 2..10 (×); FF_SPEED_UNLIMITED = no cap, which reuses the mode-3 uncapped path
        // above and is the default (unchanged behaviour). Below the top, ffLimiterMode() pushes the
        // Turbo scalar to native and engages Turbo (mode 1) so fast-forward runs at the chosen speed.
        const val FF_SPEED_UNLIMITED = 11
        private const val KEY_FF_SPEED = "ff.speed"
        fun fastForwardSpeed(): Int =
            runCatching { prefs.getInt(KEY_FF_SPEED, FF_SPEED_UNLIMITED) }
                .getOrDefault(FF_SPEED_UNLIMITED).coerceIn(2, FF_SPEED_UNLIMITED)

        fun setFastForwardSpeed(v: Int) {
            runCatching { prefs.edit().putInt(KEY_FF_SPEED, v.coerceIn(2, FF_SPEED_UNLIMITED)).apply() }
        }

        /** Limiter mode for engaging fast-forward, honouring the FF-speed slider. Unlimited at the
         *  top (mode 3); otherwise push the Turbo scalar and return Turbo (mode 1). Call only when
         *  actually engaging FF — it has the side effect of setting the scalar. */
        fun ffLimiterMode(): Int {
            val s = fastForwardSpeed()
            if (s >= FF_SPEED_UNLIMITED) return FF_LIMITER_MODE
            runCatching { NativeApp.setTurboScalar(s.toFloat()) }
            return 1 // Turbo, at the scalar just pushed
        }

        // Latched state for the "Fast Forward (toggle)" hotkey: each press flips between
        // fast-forward and the base limiter mode (vs. the hold variant which is momentary).
        // Reset to false whenever a game starts.
        @Volatile var fastForwardToggleActive = false

        /** Read-only view of the fast-forward latch, for UI that only needs to DISPLAY it (the
         *  second-display panel shows a ▶▶ marker while carrying the OSD). */
        fun isFastForwardActive(): Boolean = fastForwardToggleActive

        // Latched state for the "Slow Down (toggle)" hotkey (LimiterModeType::Slomo).
        // Mutually exclusive with the fast-forward latch; blocked in RA hardcore.
        @Volatile var slowDownToggleActive = false

        // Runtime gyro enable (issue #337), driven by the GYRO_TOGGLE / GYRO_HOLD hotkeys.
        // Session-only by design — a mid-game silence, not a persisted preference, so it
        // never contradicts the Gyro Mode setting the user chose. Compose state:
        // TouchControlsOverlay's DisposableEffect keys on it and starts/stops the sensor.
        // Stopping emits (0,0), which releases the gyro's contribution to the merged
        // stick, so the physical stick is left driving on its own.
        val gyroActive = mutableStateOf(true)

        // Bridge to the live AndroidGyroscopeInput's recenter(). The sensor instance is
        // remembered inside TouchControlsOverlay, so the runtime (which owns hotkey
        // dispatch) has no other handle on it. Set while a gyro session is registered and
        // nulled on dispose, so GYRO_RECENTER can tell "no motion running" from a real
        // recenter instead of silently doing nothing.
        @Volatile var gyroRecenterHook: (() -> Unit)? = null

        // #254: whether the emulated USB keyboard is attached for the running
        // game (resolved Settings.usbKeyboard, cached at launch in
        // applyRendererPrefs). Read hot in dispatchKeyEvent to decide whether a
        // physical keyboard's key events should be forwarded to the USB device
        // instead of driving the pad / frontend. Cheap flag so the per-event
        // path doesn't touch ConfigStore.
        @Volatile var usbKeyboardActive = false

        /** TOGGLE_KEYBOARD hotkey: raise or drop the Android IME that feeds the emulated USB
         *  keyboard. Bound to a spare pad button so chat can be opened mid-game without
         *  pausing — which is the whole point, and why this isn't a settings toggle.
         *
         *  Reports instead of silently doing nothing when the USB keyboard isn't attached:
         *  the keystrokes would go nowhere and the user would have no way to tell why. */
        fun toggleSoftKeyboard() {
            val act = instance ?: return
            if (eState.value == EmuState.STOPPED) return
            if (!usbKeyboardActive) {
                act.runOnUiThread {
                    android.widget.Toast.makeText(
                        act,
                        "Turn on Emulate USB Keyboard (Network settings) first",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                return
            }
            act.runOnUiThread { SoftKeyboard.toggle(act) }
        }

        // Cached metadata for the currently-running game. Populated when
        // The library opens a card (so we have title, serial, compatibility,
        // extension and the cover URL ready), cleared when the user
        // launches via paths that don't have a GameInfo handy (Swap/Boot Disc
        // file picker, BIOS-only boot). InGameOverlay reads this for its
        // top-left game info block — falls back to NativeApp.getPause* +
        // a runtime compat lookup when it's null.
        val currentGame = mutableStateOf<GameInfo?>(null)

        val focusRequester = FocusRequester()

        private var m_szGamefile = ""
        private val pendingExternalLaunch = mutableStateOf<String?>(null)
        // A library game tapped before native init finished — deferred and fired once
        // nativeReady. Fixes the first-cold-launch / DeX crash: applyRendererPrefs
        // pushed GS settings before the base settings layer existed → native SIGSEGV.
        private val pendingLaunch = mutableStateOf<Pair<String, GameInfo?>?>(null)

        /** Names of the memory cards a pending launch found unreadable, when a verified backup
         *  exists to put back. Non-empty holds the boot and shows the recovery prompt; the prompt
         *  either restores and retries, or sets [memoryCardRecoveryBypass] and retries. */
        val memoryCardRecovery = mutableStateOf<List<String>>(emptyList())

        /** Set by "Start anyway" so the next [start] does not re-ask about the same card. Cleared
         *  once that launch has gone through, so a later session asks again. */
        private var memoryCardRecoveryBypass = false

        fun dismissMemoryCardRecovery(startAnyway: Boolean) {
            memoryCardRecovery.value = emptyList()
            if (startAnyway) memoryCardRecoveryBypass = true
        }

        fun invoke(task: suspend () -> Unit) {
            eScope.launch {
                task()
            }
        }

        private val vmLifecycleLock = Any()
        @Volatile private var vmStopInProgress = false
        @Volatile private var vmRestartAfterStop = false
        @Volatile private var vmRunLoopActive = false

        // Quit-after-the-VM-stops latch — set by the "Close Game & Quit" hotkey, or by
        // a frontend-launched game's Close Game. One-shot: read+cleared by
        // finishToLauncherIfRequested in whichever terminal STOPPED branch fires first.
        @Volatile var quitAfterStop = false
        // True while the CURRENT game was launched from an external frontend intent.
        @Volatile var launchedExternally = false

        /** Terminal (non-restart) STOPPED branches call this: if a quit was requested,
         *  finish the Activity back to the launcher/frontend AFTER the VM has fully
         *  unwound and flushed (memcards/savestate). Marshalled to the UI thread. */
        private fun finishToLauncherIfRequested() {
            if (quitAfterStop) {
                quitAfterStop = false
                instance?.runOnUiThread { instance?.finish() }
            }
        }

        /** Close the running game the way the user asked for. When the game came from an
         *  external frontend (ES-DE / Cocoon / Daijishō) and the opt-in is on, finish the
         *  app so the frontend regains focus instead of dropping the user into the ARMSX2
         *  library.
         *
         *  EVERY close route must come through here. The hotkeys used to inline this check
         *  while the in-game menu's Close action called stop() directly, so the menu
         *  silently ignored "Exit to launcher on close" and users had to bind a hotkey to
         *  work around it. One chokepoint means the two can't drift apart again. */
        @JvmStatic
        fun closeGame(saveAutosave: Boolean = false) {
            if (launchedExternally && prefs.getBoolean("ui.exitToLauncherExternal", true))
                quitAfterStop = true
            stop(saveAutosave = saveAutosave)
        }

        /** Fully exit the app (the library Exit button and hold-back gesture route
         *  here). VM-safe: if a game is running, flush it first (quitAfterStop +
         *  async stop(), which finishes once the VM unwinds via the STOPPED branch);
         *  if already stopped, finish immediately. Never finish inline on a running
         *  VM — stop() is async and inline finish would skip the memcard/savestate
         *  flush (the same reason QUIT_APP uses the latch). */
        @JvmStatic
        fun exitApp() {
            if (eState.value == EmuState.STOPPED && !vmStopInProgress && !vmRunLoopActive) {
                instance?.runOnUiThread { instance?.finish() }
            } else {
                quitAfterStop = true
                stop()
            }
        }

        @JvmStatic
        fun isVmStopInProgress(): Boolean = vmStopInProgress

        /** True from game/BIOS boot until we are back in the library. This — not currentGame —
         *  decides which rotation tier applyEmulationOrientation() uses: a BIOS boot has no
         *  GameInfo yet is still emulation, so keying on currentGame made the BIOS follow the
         *  LAUNCHER rotation (reported as "BIOS ignores the Renderer rotation and goes portrait"). */
        private var emulationOwnsOrientation = false

        /** The single "we're back in the library" cleanup: drop the current-game pointer (so
         *  Settings reverts to Global scope) and hand the Activity's rotation back to the
         *  launcher preference.
         *
         *  This MUST run on every terminal path out of the VM. It used to live only inside
         *  stop()'s post-shutdown branch, which is guarded on `!vmRunLoopActive` — a flag the
         *  VM thread clears from its own finally. stop() usually evaluates that guard first, so
         *  the block was skipped and the surviving path never reverted anything: the launcher
         *  stayed locked in the game's landscape until the process was killed. Idempotent. */
        private fun onReturnedToLibrary() {
            currentGame.value = null
            emulationOwnsOrientation = false
            // Never leave the device pinned once the game is gone (#425).
            com.armsx2.ui.ScreenPinning.stop()
            stopAutoProgressiveScanHold()
            // Drop pressure-modifier bookkeeping: a button still held when the game exits would
            // otherwise stay in the set and be re-emitted into the NEXT session.
            com.armsx2.ui.touch.TouchControls.clearHeldPressureKeys()
            com.armsx2.BatteryWatcher.resetForNewSession()
            instance?.runOnUiThread { instance?.applyEmulationOrientation() }
        }

        // ---- Auto Progressive Scan -------------------------------------------------------
        // Some PS2 titles (Tekken 4, a number of Criterion games) offer 480p progressive output
        // only if Triangle+Cross are held while the game boots — on real hardware you hold them
        // from power-on. We reproduce that as a synthetic pad hold; games without the prompt
        // simply ignore it. Codes match applyPadButton()'s switch in native-lib.cpp.
        private const val PAD_CODE_TRIANGLE = 100
        private const val PAD_CODE_CROSS = 96

        /** How long to keep the combo held. Titles probe it at very different points — some well
         *  after the PS2 logo — so this deliberately spans the whole boot sequence. */
        private const val AUTO_PROGRESSIVE_HOLD_MS = 30_000L
        /// How often the synthetic Triangle+Cross hold is re-pressed. Must be well under a frame
        /// budget's worth of pad polling so the game never samples a gap, and short enough that a
        /// pad re-init can't swallow the whole hold.
        // Achievement-progress capture cadence. The first wait lets RetroAchievements resolve the
        // set over the network after boot; the poll only exists so a process killed mid-session
        // still leaves a recent figure in the library. Neither is latency-sensitive.
        private const val ACHIEVEMENT_SNAPSHOT_FIRST_MS = 15_000L
        private const val ACHIEVEMENT_SNAPSHOT_POLL_MS = 120_000L

        private const val AUTO_PROGRESSIVE_REASSERT_MS = 200L
        /// Keep holding this long after the game's ELF starts, then let go — the 480p prompt is
        /// checked at game start, and holding into the menus would fight the player. 8 s (up from 4)
        /// gives margin for titles that probe a few seconds into the ELF, once past the intro logos;
        /// a continuously-held button presents no fresh press edge, so it won't drive early menus.
        private const val AUTO_PROGRESSIVE_POST_ELF_MS = 8_000L

        /** Pad writes are dropped while no VM exists (applyPadButton bails on !HasValidVM), so
         *  wait for boot rather than pressing into the void. Bounded so a failed boot can't spin. */
        private const val AUTO_PROGRESSIVE_VM_WAIT_MS = 15_000L

        private var autoProgressiveScanJob: Job? = null

        private fun startAutoProgressiveScanHold() {
            stopAutoProgressiveScanHold()
            // ★ auxScope, NOT eScope. The old eScope.launch was the whole bug: eScope's single thread
            // is held by the blocking runVMThread() for the entire session, so this coroutine never
            // got to run during boot — it did nothing for anyone (the "fix" that added re-assertion
            // couldn't run either). On the independent auxScope it runs alongside the booting VM.
            autoProgressiveScanJob = auxScope.launch {
                var held = false
                try {
                    var waited = 0L
                    while (!NativeApp.hasActiveVM() && waited < AUTO_PROGRESSIVE_VM_WAIT_MS) {
                        delay(100)
                        waited += 100
                    }
                    if (!NativeApp.hasActiveVM())
                        return@launch
                    held = true
                    // Re-assert on a short interval rather than pressing once. The state itself
                    // persists (Pad::SetControllerState), so a single press would mostly work — but
                    // re-pressing cheaply survives the pad (re)init during boot ("Pad: DS2 Config
                    // Finished" lands after the VM goes active) with no gap for the game to sample.
                    //
                    // Release shortly after the game's own ELF starts rather than blocking for the
                    // full timeout: the 480p prompt is checked at game start, and continuing to jam
                    // Triangle+Cross into a booted game would fight the player in the menus. CRC
                    // goes non-zero exactly when the ELF is running, so it is the right edge to
                    // watch. AUTO_PROGRESSIVE_HOLD_MS remains the hard ceiling.
                    var elapsed = 0L
                    var sinceElf = -1L
                    while (elapsed < AUTO_PROGRESSIVE_HOLD_MS) {
                        if (!NativeApp.hasActiveVM())
                            return@launch
                        NativeApp.setPadButton(PAD_CODE_TRIANGLE, 0, true)
                        NativeApp.setPadButton(PAD_CODE_CROSS, 0, true)
                        delay(AUTO_PROGRESSIVE_REASSERT_MS)
                        elapsed += AUTO_PROGRESSIVE_REASSERT_MS
                        val elfRunning = runCatching { NativeApp.getGameCRC() }.getOrNull()
                            ?.let { it.length == 8 && it != "00000000" } ?: false
                        if (elfRunning) {
                            if (sinceElf < 0) sinceElf = 0
                            else sinceElf += AUTO_PROGRESSIVE_REASSERT_MS
                            if (sinceElf >= AUTO_PROGRESSIVE_POST_ELF_MS)
                                break
                        }
                    }
                } finally {
                    // Release on every exit path, cancellation included — a stuck Triangle+Cross
                    // would make the game unplayable. These are plain JNI calls, not suspends, so
                    // they still run in a cancelled coroutine.
                    if (held && NativeApp.hasActiveVM()) {
                        NativeApp.setPadButton(PAD_CODE_TRIANGLE, 0, false)
                        NativeApp.setPadButton(PAD_CODE_CROSS, 0, false)
                    }
                }
            }
        }

        private fun stopAutoProgressiveScanHold() {
            autoProgressiveScanJob?.cancel()
            autoProgressiveScanJob = null
        }

        fun start() {
            // Pre-boot memory card pass. Here rather than after the VM is up because the card file
            // is not open yet, so an unreadable card can still be put back before the game ever
            // mounts it — and once the console has mounted a card it caches its own picture of the
            // directory, which a restore underneath would not update.
            if (!memoryCardRecoveryBypass && MemoryCardBackup.isEnabled()) {
                val broken = instance?.applicationContext?.let { ctx ->
                    runCatching {
                        MemoryCardBackup.unreadableActiveCards(ctx, currentGame.value?.settingsKey)
                    }.getOrDefault(emptyList())
                }.orEmpty()
                if (broken.isNotEmpty()) {
                    // Held, not cancelled: the prompt calls start() again either way.
                    memoryCardRecovery.value = broken
                    return
                }
            }
            synchronized(vmLifecycleLock) {
                if (vmStopInProgress || vmRunLoopActive || eState.value != EmuState.STOPPED) {
                    vmRestartAfterStop = true
                    return
                }
                vmRunLoopActive = true
            }
            // Only now that this launch has actually committed. Clearing it above the early return
            // would drop the user's "start anyway" on a deferred launch, and ask them again when
            // the restart came round.
            memoryCardRecoveryBypass = false

            invoke {
                try {
                    eState.value = EmuState.RUNNING
                    clearLatches()
                    println("@@ANDROID_START_VM@@ kind=game path=${m_szGamefile.take(240)}")
                    // …57236 tokens truncated…  /** Gyro (aim mode 1 / steer mode 2) as an ADDITIVE stick contributor. Called
     *  from the sensor callback on the main looper. [gx],[gy] are the signed,
     *  smoothed gyro vector in [-1,1]; (0,0) on settle/stop releases it. The gyro
     *  sums with whichever physical stick shares its axis (aim -> right, or the
     *  user-chosen left for RE4-style games; steer -> left) so coarse stick aim
     *  and fine gyro adjustment work together instead of clobbering each other. */
    fun onGyroAnalog(mode: Int, gx: Float, gy: Float) {
        gyroCombineLeft = mode == 2 ||
            (mode == 1 && ControllerMappings.gyroAimStick() == ControllerMappings.GYRO_STICK_LEFT)
        gyroVecX = gx; gyroVecY = gy
        gyroCombineActive = gx != 0f || gy != 0f
        emitCombinedSticks()
    }

    /** Re-drive BOTH P1 sticks from their last physical vector plus the gyro addend
     *  on the target side, then flush once. Re-contributing the NON-target stick is
     *  what stops flushAnalogAxes' release pass from dropping it when only the gyro
     *  moved (single owner of the analog codes = the shared merge layer). flush only
     *  writes codes whose value changed, so an unchanged stick costs nothing. */
    private fun emitCombinedSticks() {
        val gxL = if (gyroCombineLeft) gyroVecX else 0f
        val gyL = if (gyroCombineLeft) gyroVecY else 0f
        val gxR = if (gyroCombineLeft) 0f else gyroVecX
        val gyR = if (gyroCombineLeft) 0f else gyroVecY
        accumStickRadial(lastPhysStickX[0] + gxL, lastPhysStickY[0] + gyL, true,  111, 113, 112, 110)
        accumStickRadial(lastPhysStickX[1] + gxR, lastPhysStickY[1] + gyR, false, 121, 123, 122, 120)
        flushAnalogAxes(0)
    }

    /** Route one physical stick's two axes to the PS2 pad per [mode]: native analog
     *  stick (default), thresholded digital D-pad / face presses, or per-direction
     *  CUSTOM binds. [leftStick] selects which stick's CUSTOM binds to read. */
    private fun dispatchStick(
        event: MotionEvent, mode: ControllerMappings.StickMode,
        axisX: Int, axisY: Int,
        aXPos: Int, aXNeg: Int, aYPos: Int, aYNeg: Int,
        leftStick: Boolean, port: Int,
    ) {
        // Read the raw axis values once, then apply the per-stick axis correction
        // (swap X/Y first, then invert each) BEFORE any mode dispatch — so it fixes
        // pads that read rotated/mirrored ("down is up, left is right") in Analog,
        // Face and Custom modes alike.
        var vx = event.getAxisValue(axisX)
        var vy = event.getAxisValue(axisY)
        if (ControllerMappings.stickSwapXY(leftStick)) { val t = vx; vx = vy; vy = t }
        if (ControllerMappings.stickInvertX(leftStick)) vx = -vx
        if (ControllerMappings.stickInvertY(leftStick)) vy = -vy
        when (mode) {
            ControllerMappings.StickMode.ANALOG -> {
                // Radial shaping into the merge layer (flushAnalogAxes writes once
                // per event, after every contributor has been folded in).
                // P1 (port 0): remember this stick's PHYSICAL vector and, when the
                // gyro is driving THIS stick, sum the gyro's signed addend on top so
                // coarse stick aim + fine gyro adjust simultaneously (onGyroAnalog).
                // Stored value is pre-gyro so the sensor path can add gyro cleanly.
                var sx = vx; var sy = vy
                if (port == 0) {
                    val si = if (leftStick) 0 else 1
                    lastPhysStickX[si] = vx; lastPhysStickY[si] = vy
                    if (gyroCombineActive && gyroCombineLeft == leftStick) { sx += gyroVecX; sy += gyroVecY }
                }
                accumStickRadial(sx, sy, leftStick, aXPos, aXNeg, aYPos, aYNeg)
                if (leftStick && ControllerMappings.dpadAsLeftStick()) {
                    // Fold the physical D-pad (HAT) into the left stick so the
                    // D-pad drives analog movement — full deflection, unshaped
                    // (a d-pad press is digital). The HAT is gated out of
                    // dispatchDpadCombined while this is on.
                    val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                    val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                    if (hx > STICK_DEAD) accumAnalog(aXPos, hx) else if (hx < -STICK_DEAD) accumAnalog(aXNeg, -hx)
                    if (hy > STICK_DEAD) accumAnalog(aYPos, hy) else if (hy < -STICK_DEAD) accumAnalog(aYNeg, -hy)
                }
            }
            ControllerMappings.StickMode.FACE -> {
                sendAxisDigital(vx, posCode = 97, negCode = 99, port = port)  // Circle / Square (right/left)
                sendAxisDigital(vy, posCode = 96, negCode = 100, port = port) // Cross / Triangle (down/up)
            }
            ControllerMappings.StickMode.DPAD -> {
                // "Stick as D-pad" preset (opt-in; the nightly's default for Joy-Cons).
                // The bit-writes happen in dispatchDpadCombined — the single, change-
                // tracked d-pad owner, keyed off stickModeFor(...)==DPAD — so a DPAD
                // stick and the physical HAT can never release each other. The swap/
                // invert correction above still applied; dispatchDpadCombined re-reads
                // the raw axes for the fold. Nothing to emit here by design.
            }
            ControllerMappings.StickMode.CUSTOM -> {
                // Each direction is bound to any PS2 button (per-player). D-pad targets
                // (19-22) are owned by dispatchDpadCombined() (avoids the release race);
                // emitCustom keeps analog targets proportional, others thresholded.
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.RIGHT, port),
                    if (vx > 0f) vx else 0f, port, leftStick)
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.LEFT, port),
                    if (vx < 0f) -vx else 0f, port, leftStick)
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.DOWN, port),
                    if (vy > 0f) vy else 0f, port, leftStick)
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.UP, port),
                    if (vy < 0f) -vy else 0f, port, leftStick)
            }
        }
    }

    // CUSTOM stick directions bound to an ARMSX2 hotkey are edge-triggered: this tracks
    // which hotkey codes are currently held past the threshold, per port, so each
    // crossing fires exactly once (re-armed on release).
    private val stickHotkeyHeld = Array(8) { HashSet<Int>() } // per unified pad slot (multitap)

    /** Fire any SysHotkey bound (Hotkeys tab) to a stick DIRECTION, edge-triggered. The
     *  stick still drives the pad, so this is meant for sticks/directions a game doesn't
     *  use. Reuses [stickHotkeyHeld] — the reserved 1000+ stick-hotkey keycodes don't
     *  collide with the Custom-mode 300+ codes also tracked there. */
    private fun fireStickHotkeys(ev: MotionEvent, port: Int) {
        fireStickHotkeyAxis(ev, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, true, port)
        val (hkRightX, hkRightY) = rightStickAxes(ev.deviceId)
        fireStickHotkeyAxis(ev, hkRightX, hkRightY, false, port)
    }
    private fun fireStickHotkeyAxis(ev: MotionEvent, axisX: Int, axisY: Int, left: Boolean, port: Int) {
        val x = ev.getAxisValue(axisX)
        val y = ev.getAxisValue(axisY)
        val held = stickHotkeyHeld[port]
        val dirs = arrayOf(
            ControllerMappings.StickDir.UP to -y, ControllerMappings.StickDir.DOWN to y,
            ControllerMappings.StickDir.LEFT to -x, ControllerMappings.StickDir.RIGHT to x,
        )
        for ((dir, value) in dirs) {
            val code = ControllerMappings.stickHotkeyKeyCode(left, dir)
            if (value > STICK_DIGITAL_THRESHOLD) {
                // Mirror the held direction into heldKeys so it can serve as the
                // MODIFIER of a stick+button combo hotkey (dispatchKeyEvent's
                // matchHotkey consults heldKeys when the button arrives).
                heldKeys.add(code)
                if (held.add(code)) {
                    // Edge: fire a hotkey with this direction as its MAIN key —
                    // combo-aware (e.g. "hold Select + push R-Stick Up"), falling
                    // back to a plain single-direction binding.
                    ControllerMappings.matchHotkey(code, heldKeys)?.let { runEdgeHotkey(it) }
                }
            } else {
                heldKeys.remove(code)
                held.remove(code)
            }
        }
    }

    /** Fire an ARMSX2 hotkey from a non-key source (a stick direction or a trigger crossing
     *  its threshold — edge-triggered, treated as a single press). Hold-type hotkeys
     *  (FAST_FORWARD hold, PRESSURE_MOD) are no-ops here: a stick edge has no hold semantics,
     *  and sendTrigger handles them itself on both edges. The rest mirror the one-shot
     *  actions in dispatchKeyEvent. */
    private fun runEdgeHotkey(h: ControllerMappings.SysHotkey) {
        when (h) {
            ControllerMappings.SysHotkey.MENU -> InGameOverlay.toggle()
            ControllerMappings.SysHotkey.SCREENSHOT -> com.armsx2.Screenshots.capture(applicationContext)
            ControllerMappings.SysHotkey.SAVE_STATE -> {
                val slot = currentSaveSlot.value
                kotlin.concurrent.thread { runCatching { NativeApp.saveStateToSlot(slot) } }
            }
            ControllerMappings.SysHotkey.LOAD_STATE -> {
                val slot = currentSaveSlot.value
                kotlin.concurrent.thread { runCatching { NativeApp.loadStateFromSlot(slot) } }
            }
            ControllerMappings.SysHotkey.CYCLE_SLOT -> cycleSaveSlot()
            ControllerMappings.SysHotkey.TEXTURE_DUMP -> {
                val on = runCatching { NativeApp.toggleTextureDumping() }.getOrDefault(false)
                android.widget.Toast.makeText(this,
                    if (on) "Texture dumping ON" else "Texture dumping OFF",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            ControllerMappings.SysHotkey.FAST_FORWARD_TOGGLE -> {
                fastForwardToggleActive = !fastForwardToggleActive
                val on = fastForwardToggleActive
                runCatching { NativeApp.speedhackLimitermode(if (on) ffLimiterMode() else baseLimiterMode()) }
                hotkeyToast(if (on) "Fast Forward ON" else "Fast Forward OFF")
            }
            ControllerMappings.SysHotkey.GYRO_TOGGLE -> toggleGyro()
            // GYRO_HOLD needs key up/down edges, which this edge-triggered path (stick
            // directions / combos) doesn't provide — behave as a toggle here rather than
            // latching gyro on with no release.
            ControllerMappings.SysHotkey.GYRO_HOLD -> toggleGyro()
            ControllerMappings.SysHotkey.GYRO_RECENTER -> recenterGyro()
            ControllerMappings.SysHotkey.RES_UP -> stepResolution(1)
            ControllerMappings.SysHotkey.RES_DOWN -> stepResolution(-1)
            ControllerMappings.SysHotkey.ACHIEVEMENTS -> com.armsx2.ui.emulation.EmulationMenuInputController.open(com.armsx2.ui.emulation.EmulationMenuTab.Options)
            ControllerMappings.SysHotkey.CLOSE_GAME -> closeGame()
            ControllerMappings.SysHotkey.QUIT_APP -> { quitAfterStop = true; stop()
            }
            ControllerMappings.SysHotkey.SAVE_AND_EXIT -> closeGame(saveAutosave = true)
            ControllerMappings.SysHotkey.RESET_GAME -> restart()
            ControllerMappings.SysHotkey.SLOW_DOWN -> toggleSlowDown()
            ControllerMappings.SysHotkey.TOGGLE_OSD -> hotkeyToast(InGameOverlay.cycleOsd())
            ControllerMappings.SysHotkey.TOGGLE_KEYBOARD -> toggleSoftKeyboard()
            ControllerMappings.SysHotkey.DISPLAY_REFRESH -> cycleDisplayRefresh()
            ControllerMappings.SysHotkey.PREV_SLOT -> cycleSaveSlot(-1)
            // Hold-type hotkeys have no one-shot stick-edge meaning.
            ControllerMappings.SysHotkey.FAST_FORWARD,
            ControllerMappings.SysHotkey.PRESSURE_MOD -> {}
            // Per-slot save/load: same one-shot meaning on a stick edge as on a button.
            else -> if (h in slotHotkeys) fireSlotHotkey(h)
        }
    }

    /** Emit one CUSTOM stick-direction binding given its 0..1 deflection [mag]
     *  toward that direction. D-pad codes (19-22) are skipped — dispatchDpadCombined
     *  owns them; analog codes (110-123) stay proportional; others are thresholded. */
    private fun emitCustom(code: Int, mag: Float, port: Int, srcLeft: Boolean) {
        // Bound to an ARMSX2 hotkey? Edge-trigger it (fire once on threshold crossing,
        // re-arm on release) instead of sending a PS2 button.
        ControllerMappings.hotkeyForStickCode(code)?.let { hk ->
            val held = stickHotkeyHeld[port]
            if (mag > STICK_DIGITAL_THRESHOLD) {
                if (held.add(code)) runEdgeHotkey(hk)
            } else {
                held.remove(code)
            }
            return
        }
        if (code in 19..22) return
        if (code in 110..123) {
            // Analog target: shape with the SOURCE stick's feel settings (the stick
            // being physically moved), and contribute to the merge layer instead of
            // writing directly, so a CUSTOM direction can't fight the other stick's
            // ANALOG writer (or a trigger/button bound to the same direction).
            val m = shapeStickMag(mag, srcLeft)
            accumAnalog(code, m)
        } else {
            NativeApp.setPadButtonForPort(port, code, 32767, mag > STICK_DIGITAL_THRESHOLD)
        }
    }

    /** Stick-as-button: press [posCode] / [negCode] once the axis passes the digital
     *  threshold. setPadButton is a state set, so re-sending the same state is a no-op. */
    // [v] is the already-corrected axis value (swap/invert applied by dispatchStick).
    private fun sendAxisDigital(v: Float, posCode: Int, negCode: Int, port: Int) {
        NativeApp.setPadButtonForPort(port, posCode, 32767, v > STICK_DIGITAL_THRESHOLD)
        NativeApp.setPadButtonForPort(port, negCode, 32767, v < -STICK_DIGITAL_THRESHOLD)
    }

    // D-pad codes (19-22) THIS function last pressed, so it releases only its own
    // presses. Owns the D-pad from ALL non-KeyEvent sources: the physical HAT, a
    // stick in DPAD mode, and any CUSTOM stick direction bound to a D-pad code.
    // PER-PORT (index = player) so P1 and P2 D-pad presses can't release each other.
    private val dpadOwnHeld = Array(8) { HashSet<Int>() } // per unified pad slot (multitap)

    /** True when any CUSTOM-mode stick has a direction bound to a D-pad code, for
     *  the given player. */
    private fun customTargetsDpad(port: Int): Boolean {
        for (isLeft in booleanArrayOf(true, false)) {
            if (ControllerMappings.stickModeFor(isLeft, port) != ControllerMappings.StickMode.CUSTOM) continue
            for (dir in ControllerMappings.StickDir.values())
                if (ControllerMappings.customStickCode(isLeft, dir, port) in 19..22) return true
        }
        return false
    }

    /** Drive the PS2 D-pad from every non-KeyEvent source that can map to it — the
     *  physical HAT, a stick in DPAD mode, and CUSTOM directions bound to a D-pad
     *  code — through ONE change-tracked owner. Writing all four codes every event
     *  (the old approach) released a held direction whenever the stick re-centered
     *  or another stick emitted an event; tracking our own presses avoids that and
     *  never clobbers a physical D-pad arriving as KeyEvents. */
    private fun dispatchDpadCombined(ev: MotionEvent, port: Int) {
        val held = dpadOwnHeld[port]
        // When the D-pad drives the left stick, the HAT is folded into the stick
        // in dispatchStick — ignore it here so it doesn't ALSO press the d-pad.
        val dpadAsStick = ControllerMappings.dpadAsLeftStick()
        val hatX = if (dpadAsStick) 0f else ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = if (dpadAsStick) 0f else ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hatActive = hatX != 0f || hatY != 0f
        // "Stick as D-pad" preset (StickMode.DPAD, opt-in): a stick in DPAD mode drives
        // the PS2 d-pad through THIS single change-tracked owner (folded via foldStick
        // below) so it can never release — or be released by — the physical HAT. Left
        // stick = AXIS_X/Y, right = AXIS_Z/RZ. dispatchStick's DPAD branch is a no-op by
        // design so there is exactly one writer. The combined owner also still handles
        // the physical HAT and CUSTOM directions bound to a d-pad code.
        val leftDpad = ControllerMappings.stickModeFor(true, port) == ControllerMappings.StickMode.DPAD
        val rightDpad = ControllerMappings.stickModeFor(false, port) == ControllerMappings.StickMode.DPAD
        // Nothing we own could be active → release what we hold and bail, so we
        // never touch the D-pad bits a KeyEvent-style physical D-pad drives.
        if (!hatActive && !leftDpad && !rightDpad && !customTargetsDpad(port)) {
            if (held.isNotEmpty()) {
                held.forEach { NativeApp.setPadButtonForPort(port, it, 0, false) }
                held.clear()
            }
            return
        }
        // HAT → D-pad only when that direction is still bound. The physical HAT never
        // flows through the keycode binding path (it arrives as a motion axis), so
        // clearing/reassigning a D-pad direction in the Pad tab was previously ignored
        // here. The custom-stick→D-pad fold below is a SEPARATE binding and stays
        // active even when the physical D-pad is cleared.
        val dpRightBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_RIGHT, port) != null
        val dpLeftBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_LEFT, port) != null
        val dpDownBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_DOWN, port) != null
        val dpUpBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_UP, port) != null
        var right = hatX > 0.5f && dpRightBound
        var left = hatX < -0.5f && dpLeftBound
        var down = hatY > 0.5f && dpDownBound
        var up = hatY < -0.5f && dpUpBound

        fun foldStick(axisX: Int, axisY: Int) {
            val x = ev.getAxisValue(axisX)
            val y = ev.getAxisValue(axisY)
            right = right || x > STICK_DIGITAL_THRESHOLD
            left = left || x < -STICK_DIGITAL_THRESHOLD
            down = down || y > STICK_DIGITAL_THRESHOLD
            up = up || y < -STICK_DIGITAL_THRESHOLD
        }
        val (foldRightX, foldRightY) = rightStickAxes(ev.deviceId)
        if (leftDpad) foldStick(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        if (rightDpad) foldStick(foldRightX, foldRightY)

        // Fold CUSTOM directions that target a D-pad code so they share this owner.
        fun foldCustom(isLeft: Boolean, axisX: Int, axisY: Int) {
            if (ControllerMappings.stickModeFor(isLeft, port) != ControllerMappings.StickMode.CUSTOM) return
            val x = ev.getAxisValue(axisX)
            val y = ev.getAxisValue(axisY)
            fun mark(dir: ControllerMappings.StickDir, active: Boolean) {
                if (!active) return
                when (ControllerMappings.customStickCode(isLeft, dir, port)) {
                    22 -> right = true
                    21 -> left = true
                    20 -> down = true
                    19 -> up = true
                }
            }
            mark(ControllerMappings.StickDir.RIGHT, x > STICK_DIGITAL_THRESHOLD)
            mark(ControllerMappings.StickDir.LEFT, x < -STICK_DIGITAL_THRESHOLD)
            mark(ControllerMappings.StickDir.DOWN, y > STICK_DIGITAL_THRESHOLD)
            mark(ControllerMappings.StickDir.UP, y < -STICK_DIGITAL_THRESHOLD)
        }
        foldCustom(true, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        foldCustom(false, foldRightX, foldRightY)

        // Write only on change so a resting stick's motion stream can't re-release
        // a direction the physical D-pad is also holding.
        fun apply(code: Int, on: Boolean) {
            val was = held.contains(code)
            if (on == was) return
            NativeApp.setPadButtonForPort(port, code, if (on) 32767 else 0, on)
            if (on) held.add(code) else held.remove(code)
        }
        apply(22, right) // D-pad right
        apply(21, left)  // D-pad left
        apply(20, down)  // D-pad down
        apply(19, up)    // D-pad up
    }

    /** Does this device actually report the given motion axis? InputDevice.getDevice is a
     *  binder call and motion events arrive far too often to query per event, hence the cache. */
    private val axisPresenceCache = HashMap<Long, Boolean>()
    private fun deviceHasAxis(deviceId: Int, axis: Int): Boolean {
        if (axis < 0) return false
        return axisPresenceCache.getOrPut((deviceId.toLong() shl 32) or (axis.toLong() and 0xffffffffL)) {
            runCatching { InputDevice.getDevice(deviceId)?.getMotionRange(axis) }.getOrNull() != null
        }
    }

    // Triggers past TRIGGER_DIGITAL_THRESHOLD, per unified pad slot: edge state for
    // trigger-bound hotkeys, so each press fires once and re-arms on release.
    private val triggerHotkeyHeld = Array(8) { HashSet<Int>() }

    /**
     * Trigger keycodes whose hotkey edge the AXIS path has already fired for the current press.
     *
     * ★ Some pads report a trigger BOTH ways — as an axis and as a key event — so a single pull
     * reaches the hotkey dispatcher twice, once from sendTrigger and once from the key path. For
     * a hold that is harmless (both compute the same state). For a TOGGLE it is fatal: the first
     * flips it on and the second immediately flips it back, which is why Fast Forward (Toggle)
     * bound to L2/R2 came on for a frame and then reported OFF, worked when bound to a
     * non-trigger button, and worked once the pad was switched to digital triggers — reported by
     * SKrazy on an AYN pad and Shmoda12 on a Thor.
     *
     * The axis path claims the press; the key path sees the claim and skips its own edge. Scoped
     * to L2/R2 alone so nothing else changes, and cleared on release so the next pull re-arms.
     */
    private val triggerHotkeyClaimed = HashSet<Int>()

    private fun sendTrigger(event: MotionEvent, left: Boolean, port: Int) {
        // -1 = no trigger axis on this side; its L2/R2 is a key event, key path owns it.
        val raw = triggerTravel(event, left)
        if (raw < 0f) return
        val code = triggerKeyCode(left)
        val held = triggerHotkeyHeld[port]
        val pressed = raw > TRIGGER_DIGITAL_THRESHOLD

        // Mirror into heldKeys so a held trigger can be a combo MODIFIER, exactly as it is on a
        // pad whose triggers send key events. Cleared on OUR release edge only — a pad that
        // reports its triggers both ways must not have the key path's hold wiped by a motion
        // event that happens to read the axis low.
        if (pressed) heldKeys.add(code)
        if (pressed != held.contains(code)) {
            if (pressed) held.add(code) else { held.remove(code); heldKeys.remove(code) }
            // Claim this press so the key path does not fire the same hotkey again on a pad
            // that reports the trigger both ways. See triggerHotkeyClaimed.
            if (pressed) triggerHotkeyClaimed.add(code) else triggerHotkeyClaimed.remove(code)
            // Triggers now reach the Hotkeys tab's capture like any other button, so they have
            // to be able to fire one here. Hold-type hotkeys act on both edges (a trigger has a
            // real release, unlike a stick edge); the rest fire on the press. Matching on
            // release re-adds the code, as the key path does, so a combo still resolves.
            ControllerMappings.matchHotkey(code, if (pressed) heldKeys else heldKeys + code)?.let { hk ->
                when (hk) {
                    ControllerMappings.SysHotkey.FAST_FORWARD -> {
                        if (pressed) fastForwardToggleActive = false
                        runCatching {
                            NativeApp.speedhackLimitermode(if (pressed) ffLimiterMode() else baseLimiterMode())
                        }
                    }
                    ControllerMappings.SysHotkey.PRESSURE_MOD ->
                        com.armsx2.ui.touch.TouchControls.pressureModifierHeld.value = pressed
                    ControllerMappings.SysHotkey.GYRO_HOLD -> gyroActive.value = pressed
                    else -> if (pressed) runEdgeHotkey(hk)
                }
            }
            // Macros are keyed on the physical code too, and the Pad tab now lets a trigger be
            // captured for one. Same both-edges firing as dispatchGameplayKey.
            com.armsx2.ui.touch.TouchControls.macroForPhysicalCode(code)?.let { macro ->
                com.armsx2.ui.touch.TouchControls.fireMacro(macro, "pad$port", pressed) { c, p ->
                    sendKeyAction(if (p) KeyEventType.KeyDown else KeyEventType.KeyUp, c, port)
                }
            }
        }
        // A trigger bound to a hotkey or a macro doesn't also drive the pad — the precedence
        // the key path and emitCustom already apply. The hotkey match is combo-aware, so a
        // trigger that is merely a MODIFIER keeps working as L2/R2.
        if (ControllerMappings.matchHotkey(code, heldKeys) != null) return
        if (com.armsx2.ui.touch.TouchControls.macroForPhysicalCode(code) != null) return

        // Honor the L2/R2 binding: triggers arrive as motion axes, never through the
        // keycode binding path, so clearing/remapping them in the Pad tab was ignored.
        // Resolve the physical trigger keycode to its mapped PS2 target — null = cleared,
        // so the trigger is disabled; otherwise drive the resolved (possibly remapped) code.
        val target = ControllerMappings.targetForPhysical(code, port) ?: return
        // Deadzone off the bottom, re-normalized, so pressure ramps from zero instead of
        // flicking on/off at a hard threshold (the jitter non-Xbox pads showed).
        val out = if (raw <= TRIGGER_DEAD) 0f else (raw - TRIGGER_DEAD) / (1f - TRIGGER_DEAD)
        if (target in 110..123) {
            // Trigger bound to a PS2 STICK direction ("(send)" rows): contribute the
            // proportional pressure to the merge layer so it can't be released by
            // the target stick's own (resting) ANALOG writer in the same event.
            accumAnalog(target, out)
        } else {
            NativeApp.setPadButtonForPort(port, target, (out * 32767).toInt(), out > 0f)
        }
    }

    /** Set in onPause when the screen goes off (a real sleep), consumed in onResume so the sleep
     *  chime is paired with a wake chime + a brief "Welcome Back!" — never on a plain background. */
    private var wasAsleep = false

    /** Frees a pad's slot in PadRouter when its controller is unplugged / power-cycled, so a
     *  re-enumerated device (a NEW deviceId after AYANEO sleep/wake) re-claims Player 1 instead of
     *  the stale dead id owning it and shunting gameplay to an un-armed pad (#394). Registered in
     *  onCreate and kept alive across pause so the wake-time remove/add is caught. */
    private val inputDeviceListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            com.armsx2.input.PadRouter.forgetDevice(deviceId)
        }
    }

    private var blackIceForeground = false

    private fun blackIceSession(event: String) {
        if (!blackIceForeground && (event == "running" || event == "checkpoint")) return
        runCatching { contentResolver.call(android.net.Uri.parse("content://${packageName}.blackice.session"), event, null, null) }
    }

    private fun publishBlackIceBios() {
        runCatching {
            contentResolver.call(android.net.Uri.parse("content://${packageName}.blackice.session"), "bios", bios.value.orEmpty(), null)
        }
    }

    override fun onPause() {
        publishBlackIceBios()
        blackIceForeground = false
        blackIceSession("paused")
        // Take the second-display panel down with the app. A Presentation is not torn down by the
        // activity stopping, so it otherwise stayed on the external screen while the user was off
        // doing something else (reported).
        runCatching { com.armsx2.SecondScreen.setForeground(applicationContext, false) }
        // DS-lid-style chime when the SCREEN is going off (device sleeping) — gated on isInteractive
        // so a plain background (home / recents, screen still on) stays silent. Fires before we pause
        // audio below so the blip is heard as the device sleeps.
        if ((getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive == false) {
            wasAsleep = true
            com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.SLEEP)
        }
        com.armsx2.navigation.UiNavigator.drawerOpen.value = false
        // Mark backgrounded BEFORE opening the overlay below. open() sets overlayVisible = true,
        // which re-fires the pause-music LaunchedEffect — and without this flag already false, that
        // effect would call start() and play the track on the OS home screen. setForeground(false)
        // also pauses whatever is currently playing.
        com.armsx2.PauseMusic.setForeground(false)
        // Leaving the app (home / recents / slide-out) while a game is running:
        // open the pause OVERLAY instead of a silent pause. A bare pause left
        // users staring at a frozen game with no obvious way back — they had to
        // know to open the menu and tap Resume. open() pauses the VM AND shows
        // the pause menu, so returning lands straight on the Resume button.
        // No-op if the overlay is already up (it already paused the game).
        if (eState.value == EmuState.RUNNING)
            InGameOverlay.open()
        // Persist Vulkan pipeline cache before Android can reap the process.
        // ~VKShaderCache only fires on a clean device teardown, but swipe-kill
        // / OOM-kill skip that path — every cold launch would otherwise
        // re-compile every TFX pipeline from scratch. No-op on OpenGL.
        NativeApp.flushShaderCache()
        // PGO instrument build: flush profile counters so a profiling run survives
        // an Android process kill. No-op in normal builds.
        runCatching { NativeApp.dumpPgoProfile() }
        // Library music must not keep playing out of a backgrounded app — it would sound
        // like the emulator ignoring the home button. Paused, not stopped, so returning
        // to the library picks it back up.
        com.armsx2.LibraryMusic.pause()
        // Pause-menu track was already paused by setForeground(false) at the top of onPause.
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        blackIceForeground = true
        if (eState.value == EmuState.RUNNING) blackIceSession("running")
        runCatching { com.armsx2.SecondScreen.setForeground(applicationContext, true) }
        // Woke from a real sleep (paired with the onPause sleep chime): play the wake chime + a brief
        // top-left "Welcome Back!". A plain background return never set wasAsleep, so this only fires
        // after an actual screen-off sleep.
        if (wasAsleep) {
            wasAsleep = false
            com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.WAKE)
            if (prefs.getBoolean("ui.hotkeyToasts", true))
                com.armsx2.ui.WelcomeBanner.show(com.armsx2.i18n.I18n.get("app.welcomeBack"))
        }
        // #394 backstop: a controller that slept and woke returns with a NEW deviceId, so drop any
        // pad slot whose device is no longer connected — the re-enumerated pad then re-claims Player 1
        // rather than the dead id silently owning it. (inputDeviceListener catches the live remove;
        // this covers a remove that landed while we were paused / was never delivered.)
        com.armsx2.input.PadRouter.pruneStale(android.view.InputDevice.getDeviceIds())
        // Returning to the foreground: call start(), not resume(). A PERMANENT audio-focus
        // loss (another media app took over — YouTube, iiSU) releases our player entirely,
        // and resume() only un-pauses an existing player, so the music stayed dead until a
        // full app restart (#398-adjacent report). start() rebuilds a released player — and
        // still just un-pauses a merely-paused one — while its own guards keep it a no-op
        // when the setting is off, a VM is running, or that other app is still playing.
        com.armsx2.LibraryMusic.start(this)
        // Back in the foreground: clear the background guard first, THEN restart the pause track if a
        // menu is still up. The LaunchedEffect won't do it — the overlay states didn't change while
        // we were away, so it never re-runs — and start() no-ops until foreground is true again.
        com.armsx2.PauseMusic.setForeground(true)
        if (WindowImpl.overlayVisible.value || WindowImpl.inGameScreen.value != null) {
            com.armsx2.PauseMusic.start(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalLaunchIntent(intent)
    }

    override fun onDestroy() {
        blackIceSession("paused")
        getSystemService(android.hardware.input.InputManager::class.java)
            ?.unregisterInputDeviceListener(inputDeviceListener)
        // On a CONFIGURATION-driven recreate (e.g. Samsung DeX moving the activity to
        // an external display, density/uiMode change) Android destroys+recreates us.
        // Do NOT tear down the native VM or hard-kill the process then — that races the
        // recreate and crashes ("this app has a bug"). Only shut down on a real finish.
        if (isChangingConfigurations()) {
            super.onDestroy()
            return
        }
        // Real finish only — a configuration recreate must NOT tear the second-display panel
        // down (it would flicker away and rebuild on every rotation/density change).
        runCatching { com.armsx2.SecondScreen.release(applicationContext) }
        NativeApp.shutdown()
        super.onDestroy()

        val appPid = Process.myPid()
        Process.killProcess(appPid)
    }

    private fun handleExternalLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("blackIceBiosSetup", false) == true) {
            blackIceBiosSetup.value = true
            setupEditorVisible.value = true
            intent.removeExtra("blackIceBiosSetup")
            return
        }
        val raw = extractLaunchUri(intent) ?: return
        persistReadGrant(intent, raw)
        // Frontends (Cocoon/Daijisho/ES-DE) list the .cue, since that's the canonical disc
        // descriptor for a cue+bin rip — but the core has no cue parser and .cue isn't in its
        // disc whitelist (VMManager::IsDiscFileName), so booting one fails outright. Resolve
        // the cue's first FILE "<name>" BINARY entry to its sibling track and launch that.
        // Falls back to the original URI whenever anything fails, so a launch that already
        // worked (.iso/.bin/.chd) can never be made worse by this.
        val uri = resolveCueToTrack(raw) ?: raw
        currentGame.value = null
        pendingExternalLaunch.value = uri.toString()
        launchPendingExternalGameIfReady()
    }

    /** Maps a `.cue` sheet to the track file it points at. Returns null for anything that
     *  isn't a resolvable cue, so the caller keeps the original URI. */
    private fun resolveCueToTrack(cue: Uri): Uri? = runCatching {
        val label = (cue.lastPathSegment ?: cue.path).orEmpty()
        if (!label.endsWith(".cue", ignoreCase = true)) return null
        val text = readBounded(cue) ?: return null
        // FILE "Game.bin" BINARY  — the name may also be unquoted. Strip any directory part;
        // a cue always references tracks sitting beside it.
        val m = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))""").find(text) ?: return null
        val track = (m.groupValues[1].takeIf(String::isNotBlank) ?: m.groupValues[2])
            .trim().substringAfterLast('/').substringAfterLast('\\')
        if (track.isBlank()) return null
        siblingOf(cue, track)
    }.getOrNull()

    /** Bounded read — cue sheets are a few hundred bytes, so never slurp an arbitrary file. */
    private fun readBounded(uri: Uri, limit: Int = 65536): String? = runCatching {
        val stream = if (uri.scheme == "content") contentResolver.openInputStream(uri)
        else uri.path?.let { java.io.File(it).takeIf(java.io.File::isFile)?.inputStream() }
        stream?.use { s ->
            val buf = ByteArray(limit)
            var n = 0
            while (n < limit) {
                val r = s.read(buf, n, limit - n)
                if (r <= 0) break
                n += r
            }
            String(buf, 0, n)
        }
    }.getOrNull()

    /** Sibling file alongside [origin]. Raw/file paths resolve directly (we hold all-files
     *  access on the sideload build); a content:// URI only resolves when it carries a parent
     *  — a single-document grant from a frontend does not, so we return null and fall back. */
    private fun siblingOf(origin: Uri, fileName: String): Uri? = runCatching {
        if (origin.scheme == null || origin.scheme == "file") {
            val parent = origin.path?.let { java.io.File(it).parentFile } ?: return null
            java.io.File(parent, fileName).takeIf { it.isFile }?.absolutePath?.toUri()
        } else {
            androidx.documentfile.provider.DocumentFile.fromSingleUri(this, origin)
                ?.parentFile?.findFile(fileName)?.takeIf { it.isFile }?.uri
        }
    }.getOrNull()

    private fun extractLaunchUri(intent: Intent?): Uri? {
        if (intent == null)
            return null

        intent.data?.let { return it }

        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        stream?.let { return it }

        intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { return it }

        for (key in listOf("path", "game", "rom", "uri", "android.intent.extra.STREAM")) {
            val value = intent.getStringExtra(key)?.takeIf { it.isNotBlank() } ?: continue
            return value.toUri()
        }

        return null
    }

    private fun persistReadGrant(intent: Intent?, uri: Uri) {
        if (uri.scheme != "content" || intent == null)
            return

        val flags = intent.flags
        if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 ||
            (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0)
            return

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
