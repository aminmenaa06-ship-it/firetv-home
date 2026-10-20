package com.amin.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
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
    class Banner(val image: ImageBitmap) : AppArt
    class Icon(val image: ImageBitmap) : AppArt
}

// Streaming apps float to the front in this order; everything else follows alphabetically.
private val PINNED = listOf("netflix", "youtube", "prime video", "plex", "disney", "hulu", "max", "apple tv", "spotify")

// Banners are 16:9 by spec; decoding to a fixed size bounds memory (~230 KB each).
private const val BANNER_W = 320
private const val BANNER_H = 180
private const val ICON_PX = 128

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
                AppArt.Banner(banner.toBitmap(BANNER_W, BANNER_H).asImageBitmap())
            } else {
                AppArt.Icon(info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap())
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
