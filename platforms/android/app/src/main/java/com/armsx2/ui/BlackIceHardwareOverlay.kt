package com.armsx2.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.GpuInfo
import com.armsx2.Thermals
import com.armsx2.runtime.MainActivityRuntime
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.co.iefriends.pcsx2.NativeApp

/** Read-only system telemetry. Missing/restricted sensors are never substituted with estimates. */
object BlackIceHardwareStats {
    val enabled = mutableStateOf(false)
    fun load() { enabled.value = MainActivityRuntime.prefs.getBoolean("blackIce.hardwareStats", false) }
    fun setEnabled(value: Boolean) {
        enabled.value = value
        MainActivityRuntime.prefs.edit().putBoolean("blackIce.hardwareStats", value).apply()
    }
}

private class HardwareSampler(private val context: Context) {
    private var previous: Pair<Long, Long>? = null
    private fun read(path: String) = runCatching { File(path).readText().trim() }.getOrNull()
    private val cpuName = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN } ?: Build.HARDWARE else Build.HARDWARE
    private val gpuName = runCatching { GpuInfo.rendererName() }.getOrNull() ?: "GPU N/A"
    private fun temp(value: Float) = if (value == Thermals.NONE) "N/A" else "${value.toInt()}°C"
    fun sample(): List<String> {
        Thermals.poll(context, 1000)
        val ticks = read("/proc/stat")?.lineSequence()?.firstOrNull()?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }
        val now = ticks?.takeIf { it.size >= 5 }?.let { it.take(8).sum() to (it[3] + it[4]) }
        val old = previous
        previous = now
        val cpuUsage = if (now != null && old != null && now.first > old.first && now.second >= old.second)
            "${((1.0 - (now.second-old.second).toDouble()/(now.first-old.first))*100).coerceIn(0.0,100.0).toInt()}%" else "N/A"
        val cpuMHz = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
            read("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.toLongOrNull()?.takeIf { khz -> khz > 0 }
        }.maxOrNull()?.let { "${it/1000} MHz peak" } ?: "N/A MHz"
        val gpuMHz = read("/sys/class/kgsl/kgsl-3d0/gpuclk")?.toLongOrNull()?.takeIf { it > 0 }?.let { "${it/1000000} MHz" } ?: "N/A MHz"
        val busy = read("/sys/class/kgsl/kgsl-3d0/gpubusy")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
        val gpuUsage = if (busy != null && busy.size == 2 && busy[1] > 0 && busy[0] in 0..busy[1]) "${busy[0]*100/busy[1]}%" else "N/A"
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        fun gib(bytes: Long) = String.format(Locale.US, "%.1f", bytes/1073741824.0)
        val fps = runCatching { NativeApp.getFPS() }.getOrNull()?.takeIf { it.isFinite() && it >= 0 }
        return listOf(
            "CPU  $cpuName  ${temp(Thermals.cpu)}  $cpuUsage  $cpuMHz",
            "GPU  $gpuName  ${temp(Thermals.gpu)}  $gpuUsage  $gpuMHz",
            "RAM  ${gib(mem.totalMem-mem.availMem)} / ${gib(mem.totalMem)} GB used  N/A MHz",
            "FPS  ${fps?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"}",
        )
    }
}

@Composable
fun BlackIceHardwareOverlay() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { BlackIceHardwareStats.load() }
    if (!BlackIceHardwareStats.enabled.value || InGameOverlay.osdMode.value == InGameOverlay.OsdMode.Off) return
    var rows by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(context) {
        val sampler = withContext(Dispatchers.IO) { HardwareSampler(context) }
        while (true) {
            rows = withContext(Dispatchers.IO) { sampler.sample() }
            delay(1000)
        }
    }
    Column(Modifier.padding(top = 48.dp, start = 8.dp).background(Color(0xB3080B10)).padding(8.dp)) {
        rows.forEachIndexed { index, row ->
            Text(row, color = if (index == 3) Color.White else Color(0xFFFFA12B), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
