package com.amin.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val leanback: Boolean,
) {
    val component get() = ComponentName(packageName, activityName)
}

data class SettingShortcut(val label: String, val intent: Intent)

sealed interface AppArt {
    /** Pre-blurred, color-graded wash of the art, stretched full-screen behind the grid. */
    val ambient: ImageBitmap

    class Banner(val image: ImageBitmap, override val ambient: ImageBitmap) : AppArt

    /** No TV banner: the icon sits on a tile tinted with its own dominant color. */
    class Icon(val image: ImageBitmap, val tint: Color, override val ambient: ImageBitmap) : AppArt
}

// Streaming apps float to the front in this order; everything else follows alphabetically.
private val PINNED = listOf("netflix", "youtube", "prime video", "plex", "disney", "hulu", "max", "apple tv", "spotify")

// Banners are 16:9 by spec; decoding to a fixed size bounds memory (~330 KB each) while staying
// sharp at the focused 1.12x scale.
private const val BANNER_W = 384
private const val BANNER_H = 216
private const val ICON_PX = 144

// The backdrop is a tiny image upscaled with bilinear filtering: after a box blur it reads as a
// soft color field, and costs nothing to draw. Fire OS 8 is API 30, so there's no RenderEffect blur.
private const val AMBIENT_W = 48
private const val AMBIENT_H = 27
private const val BASE_RGB = 0x0E0F12

class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val artCache = LruCache<String, AppArt>(96)

    // Two decoders at most so banner loading never starves the UI thread on a quad-core stick.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val artDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps

    private val _settings = MutableStateFlow<List<SettingShortcut>>(emptyList())
    val settings: StateFlow<List<SettingShortcut>> = _settings

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _apps.value = queryApps()
        if (_settings.value.isEmpty()) _settings.value = querySettings()
    }

    fun cachedArt(app: AppEntry): AppArt? = artCache.get(app.packageName)

    suspend fun loadArt(app: AppEntry): AppArt? = withContext(artDispatcher) {
        artCache.get(app.packageName) ?: runCatching {
            val info = pm.getActivityInfo(app.component, 0)
            val banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
            if (banner != null) {
                val bmp = banner.toBitmap(BANNER_W, BANNER_H)
                AppArt.Banner(bmp.asImageBitmap(), ambientOf(bmp))
            } else {
                val bmp = info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX)
                val tint = dominantColor(bmp)
                val field = Bitmap.createBitmap(AMBIENT_W, AMBIENT_H, Bitmap.Config.ARGB_8888).apply { eraseColor(tint) }
                AppArt.Icon(bmp.asImageBitmap(), Color(tint), ambientOf(field))
            }
        }.getOrNull()?.also { artCache.put(app.packageName, it) }
    }

    fun launchIntent(app: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun appInfoIntent(app: AppEntry): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun queryApps(): List<AppEntry> {
        val byPackage = LinkedHashMap<String, AppEntry>()
        // TV-native entries win; plain LAUNCHER only fills in sideloaded phone apps.
        for ((category, leanback) in listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER to true,
            Intent.CATEGORY_LAUNCHER to false,
        )) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == context.packageName || ai.packageName in byPackage) continue
                byPackage[ai.packageName] = AppEntry(ai.packageName, ai.name, ri.loadLabel(pm).toString().trim(), leanback)
            }
        }
        return byPackage.values.sortedWith(compareBy({ pinnedRank(it.label) }, { it.label.lowercase() }))
    }

    private fun pinnedRank(label: String): Int {
        val l = label.lowercase()
        val i = PINNED.indexOfFirst { l.startsWith(it) }
        return if (i < 0) PINNED.size else i
    }

    private fun querySettings(): List<SettingShortcut> {
        val shortcuts = listOf(
            "Network" to Settings.ACTION_WIFI_SETTINGS,
            "Display" to Settings.ACTION_DISPLAY_SETTINGS,
            "Sound" to Settings.ACTION_SOUND_SETTINGS,
            "Remotes & Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "Applications" to Settings.ACTION_APPLICATION_SETTINGS,
            "Date & Time" to Settings.ACTION_DATE_SETTINGS,
            "Developer Options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            "All Settings" to Settings.ACTION_SETTINGS,
        ).map { (label, action) -> SettingShortcut(label, Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .filter { it.intent.resolveActivity(pm) != null }
        if (shortcuts.any { it.label == "All Settings" }) return shortcuts
        // Fire OS fallback: Amazon's settings app may not answer the generic action.
        val amazon = pm.getLeanbackLaunchIntentForPackage("com.amazon.tv.settings.v2")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return shortcuts
        return shortcuts + SettingShortcut("All Settings", amazon)
    }
}

private fun ambientOf(src: Bitmap): ImageBitmap {
    // createScaledBitmap averages while shrinking; copy() also handles HARDWARE-config sources.
    val small = Bitmap.createScaledBitmap(src, AMBIENT_W, AMBIENT_H, true).copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(AMBIENT_W * AMBIENT_H)
    small.getPixels(px, 0, AMBIENT_W, 0, 0, AMBIENT_W, AMBIENT_H)
    for (i in px.indices) px[i] = grade(px[i])
    repeat(2) { boxBlur(px, AMBIENT_W, AMBIENT_H, radius = 3) }
    return Bitmap.createBitmap(px, AMBIENT_W, AMBIENT_H, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// Composite over the dark base, push saturation and pull brightness down: a tinted wash rather
// than a picture, so white banners (Netflix, YouTube) don't turn the whole screen grey.
private fun grade(c: Int): Int {
    val a = (c ushr 24) / 255f
    fun ch(shift: Int) = ((c shr shift) and 0xFF) / 255f * a + ((BASE_RGB shr shift) and 0xFF) / 255f * (1 - a)
    val r = ch(16); val g = ch(8); val b = ch(0)
    val l = 0.299f * r + 0.587f * g + 0.114f * b
    // Colorful art keeps more brightness; near-white/grey art sinks to graphite instead of grey.
    val sat = maxOf(r, g, b) - minOf(r, g, b)
    val bright = 0.2f + 0.22f * (sat * 2.5f).coerceAtMost(1f)
    fun out(v: Float) = ((l + (v - l) * 1.8f) * bright).coerceIn(0f, 1f).times(255).toInt()
    return (0xFF shl 24) or (out(r) shl 16) or (out(g) shl 8) or out(b)
}

private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
    val tmp = IntArray(px.size)
    fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
        val (outer, inner) = if (horizontal) h to w else w to h
        for (o in 0 until outer) for (i in 0 until inner) {
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) {
                val j = (i + k).coerceIn(0, inner - 1)
                val c = src[if (horizontal) o * w + j else j * w + o]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val n = 2 * radius + 1
            dst[if (horizontal) o * w + i else i * w + o] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
    pass(px, tmp, horizontal = true)
    pass(tmp, px, horizontal = false)
}

// Alpha- and saturation-weighted average, so an icon's brand color wins over its white/grey parts.
private fun dominantColor(icon: Bitmap): Int {
    val small = Bitmap.createScaledBitmap(icon, 16, 16, true).copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(256).also { small.getPixels(it, 0, 16, 0, 0, 16, 16) }
    var r = 0f; var g = 0f; var b = 0f; var total = 0f
    for (c in px) {
        val a = (c ushr 24) / 255f
        val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
        val sat = (maxOf(cr, cg, cb) - minOf(cr, cg, cb)) / 255f
        val wgt = a * (sat + 0.08f)
        r += cr * wgt; g += cg * wgt; b += cb * wgt; total += wgt
    }
    if (total < 0.5f) return 0xFF2A2D35.toInt()
    return (0xFF shl 24) or ((r / total).toInt() shl 16) or ((g / total).toInt() shl 8) or (b / total).toInt()
}
