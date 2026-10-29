package net.findip.shield

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** The device facts an event carries (spec §2). A family instead of the model; no hardware or advertising IDs. */
internal data class DeviceInfo(
    val platform: String,
    val osVersion: String? = null,
    val modelFamily: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val screen: Screen? = null,
    val signals: Signals? = null,
) {
    data class Screen(val width: Int, val height: Int, val scale: Double)
    data class Signals(val emulator: Boolean?, val rooted: Boolean?, val debugger: Boolean?)

    companion object {
        private val osVersionPattern = Regex("^\\d{1,3}(\\.\\d{1,3}){0,3}$")
        private val familyPattern = Regex("^[A-Za-z][A-Za-z ]{0,31}$")

        /** "14", "8.1.0"; anything else (a codename, a suffix) is left out. */
        fun osVersion(release: String?): String? = release?.trim()?.takeIf { osVersionPattern.matches(it) }

        /** A family Shield can store: "Pixel 8 Pro" → "Pixel", "SM-S918B" (Samsung) → "Galaxy", else the maker. */
        fun family(manufacturer: String?, model: String?): String? {
            val m = model?.trim().orEmpty()
            val maker = manufacturer?.trim().orEmpty()
            val candidate = when {
                m.startsWith("Pixel", ignoreCase = true) -> "Pixel"
                maker.equals("samsung", ignoreCase = true) || m.startsWith("SM-") -> "Galaxy"
                else -> maker.filter { it.isLetter() || it == ' ' }.trim().replaceFirstChar { it.uppercase() }
            }
            return candidate.takeIf { familyPattern.matches(it) }
        }

        /** The facts of the device the app runs on. */
        fun current(context: Context): DeviceInfo {
            val metrics = context.resources.displayMetrics
            val density = metrics.density.toDouble().takeIf { it > 0 } ?: 1.0
            return DeviceInfo(
                platform = "android",
                osVersion = osVersion(Build.VERSION.RELEASE),
                modelFamily = family(Build.MANUFACTURER, Build.MODEL),
                locale = Locale.getDefault().toLanguageTag(),
                timezone = TimeZone.getDefault().id,
                screen = Screen((metrics.widthPixels / density).toInt(), (metrics.heightPixels / density).toInt(), density),
                signals = Signals(emulator = isEmulator(), rooted = isRooted(), debugger = Debug.isDebuggerConnected()),
            )
        }

        private fun isEmulator(): Boolean? = try {
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.HARDWARE == "goldfish" || Build.HARDWARE == "ranchu" ||
                Build.PRODUCT.contains("sdk") || Build.MODEL.contains("Emulator")
        } catch (e: Exception) {
            null
        }

        /** A conservative check: an `su` binary on the usual paths, or a test-keys build. Null when it cannot run. */
        private fun isRooted(): Boolean? = try {
            val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su")
            paths.any { File(it).exists() } || (Build.TAGS?.contains("test-keys") == true)
        } catch (e: Exception) {
            null
        }
    }
}

/** The app the SDK runs in. */
internal data class AppInfo(val bundleId: String, val version: String? = null, val build: String? = null) {
    companion object {
        fun current(context: Context): AppInfo {
            val name = context.packageName
            return try {
                val info = context.packageManager.getPackageInfo(name, 0)
                @Suppress("DEPRECATION")
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                AppInfo(name, info.versionName, code.toString())
            } catch (e: PackageManager.NameNotFoundException) {
                AppInfo(name)
            }
        }
    }
}
