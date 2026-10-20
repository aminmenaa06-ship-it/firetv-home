@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package com.amin.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private val Bg = Color(0xFF0D0D0F)
private val TileBg = Color(0xFF1C1C1F)
private val TileBgFocused = Color(0xFF2C2C31)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextSecondary = Color(0xFF8E8E93)
private val TileShape = RoundedCornerShape(14.dp)
private val ScreenPadding = 56.dp

private enum class Section(val title: String) { Apps("Apps"), Settings("Settings") }

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Bg, surface = TileBg, onSurface = TextPrimary, border = Color.White),
        content = content,
    )
}

@Composable
fun LauncherScreen(
    apps: List<AppEntry>?,
    settings: List<SettingShortcut>,
    homePresses: Flow<Unit>,
    cachedArt: (AppEntry) -> AppArt?,
    loadArt: suspend (AppEntry) -> AppArt?,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onSetting: (SettingShortcut) -> Unit,
    setupStatus: String?,
    onMakeHome: () -> Unit,
    onRestoreHome: () -> Unit,
) {
    var section by remember { mutableStateOf(Section.Apps) }
    val appsGrid = rememberLazyGridState()
    val firstApp = remember { FocusRequester() }
    val tabs = remember { Section.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var rootHasFocus by remember { mutableStateOf(false) }

    // Somewhere sensible to put focus when nothing holds it: the first app, else the current tab.
    fun reclaimFocus() {
        if (section == Section.Apps && firstApp.tryFocus()) return
        tabs.getValue(section).tryFocus()
    }

    // Home (or Back) always lands on the first app at the top of the grid, like tvOS.
    suspend fun goHome() {
        if (section != Section.Apps) {
            // Park focus on the Apps tab before the Settings grid is torn down. Otherwise the
            // focused tile vanishes, the tab row's restorer picks "Settings", and that re-selects it.
            tabs.getValue(Section.Apps).tryFocus()
            section = Section.Apps
            withFrameNanos { }
        }
        appsGrid.scrollToItem(0)
        withFrameNanos { }
        reclaimFocus()
    }

    LaunchedEffect(homePresses) { homePresses.collect { goHome() } }
    val hasApps = !apps.isNullOrEmpty()
    LaunchedEffect(hasApps) {
        if (hasApps) {
            withFrameNanos { }
            firstApp.tryFocus()
        }
    }
    BackHandler { scope.launch { goHome() } }

    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgePaddingSpec(top = 24.dp, bottom = 44.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                // Focus guard: if the focused tile vanishes (app uninstalled, list refreshed), the
                // remote would otherwise go dead. Put focus back on something reachable.
                .onFocusChanged {
                    rootHasFocus = it.hasFocus
                    // Focus hand-offs between groups report a transient "nothing focused"; only
                    // act if it's still lost a frame later.
                    if (!it.hasFocus) scope.launch {
                        withFrameNanos { }
                        if (!rootHasFocus) reclaimFocus()
                    }
                },
        ) {
            TopBar(section, tabs, onSelect = { section = it })
            when (section) {
                Section.Apps -> AppsGrid(apps, appsGrid, firstApp, cachedArt, loadArt, onLaunch, onAppInfo)
                Section.Settings -> SettingsGrid(settings, setupStatus, onSetting, onMakeHome, onRestoreHome)
            }
        }
    }
}

private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess

@Composable
private fun TopBar(section: Section, tabs: Map<Section, FocusRequester>, onSelect: (Section) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding - 14.dp, end = ScreenPadding, top = 28.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Coming up from the grid lands on the selected tab, not whichever is geometrically closest.
        Row(
            Modifier
                .focusRestorer { tabs.getValue(section) }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in Section.entries) {
                SectionTab(
                    title = s.title,
                    selected = s == section,
                    modifier = Modifier.focusRequester(tabs.getValue(s)),
                    onFocused = { onSelect(s) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Clock()
    }
}

@Composable
private fun SectionTab(title: String, selected: Boolean, modifier: Modifier, onFocused: () -> Unit) {
    // tvOS behavior: focusing a tab switches to it; no click needed.
    Surface(
        selected = selected,
        onClick = onFocused,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = SelectableSurfaceDefaults.shape(shape = CircleShape),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = TextSecondary,
            selectedContainerColor = Color.White.copy(alpha = 0.12f),
            selectedContentColor = TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            focusedSelectedContainerColor = Color.White,
            focusedSelectedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
            pressedSelectedContainerColor = Color.White,
            pressedSelectedContentColor = Color.Black,
        ),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.06f, focusedSelectedScale = 1.06f),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // TIME_TICK is wall-clock aligned; SCREEN_ON covers waking from standby, when ticks were skipped.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                now = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val text = remember(now) {
        val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now))
    }
    Text(text = text, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun AppsGrid(
    apps: List<AppEntry>?,
    state: LazyGridState,
    firstApp: FocusRequester,
    cachedArt: (AppEntry) -> AppArt?,
    loadArt: suspend (AppEntry) -> AppArt?,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
) {
    when {
        apps == null -> Unit // first query is in flight (tens of ms); draw nothing rather than a spinner
        apps.isEmpty() -> EmptyState("No apps installed")
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer { firstApp },
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 24.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                val art by produceState(cachedArt(app), app) {
                    if (value == null) value = loadArt(app)
                }
                Tile(
                    label = app.label,
                    showLabel = art !is AppArt.Icon, // icon tiles already carry their name
                    onClick = { onLaunch(app) },
                    onLongClick = { onAppInfo(app) },
                    modifier = if (index == 0) Modifier.focusRequester(firstApp) else Modifier,
                ) {
                    AppArtwork(app.label, art)
                }
            }
        }
    }
}

@Composable
private fun SettingsGrid(
    shortcuts: List<SettingShortcut>,
    status: String?,
    onOpen: (SettingShortcut) -> Unit,
    onMakeHome: () -> Unit,
    onRestoreHome: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val tiles = listOf("Use as Home Screen" to onMakeHome, "Restore Amazon Home" to onRestoreHome) +
        shortcuts.map { s -> s.label to { onOpen(s) } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer { first },
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 24.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (status != null) {
            item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Text(text = status, modifier = Modifier.padding(bottom = 10.dp), color = TextPrimary, fontSize = 15.sp)
            }
        }
        itemsIndexed(tiles, key = { _, (label, _) -> label }) { index, (label, onClick) ->
            Tile(
                label = label,
                showLabel = false,
                onClick = onClick,
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showLabel: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val labelVisible by rememberUpdatedState(showLabel)
    Column {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            interactionSource = interaction,
            shape = CardDefaults.shape(TileShape),
            colors = CardDefaults.colors(
                containerColor = TileBg,
                contentColor = TextPrimary,
                focusedContainerColor = TileBgFocused,
                focusedContentColor = TextPrimary,
                pressedContainerColor = TileBgFocused,
                pressedContentColor = TextPrimary,
            ),
            scale = CardDefaults.scale(focusedScale = 1.1f, pressedScale = 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = TileShape)),
            glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.18f), elevation = 12.dp)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
        }
        // Title appears under the focused tile only. Alpha is read in the layer block so focus
        // changes skip recomposition and layout entirely.
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .graphicsLayer { alpha = if (focused && labelVisible) 1f else 0f },
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppArtwork(label: String, art: AppArt?) {
    when (art) {
        is AppArt.Banner -> Image(
            bitmap = art.image,
            contentDescription = label,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        is AppArt.Icon -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(bitmap = art.image, contentDescription = label, modifier = Modifier.size(44.dp))
            Text(
                text = label,
                modifier = Modifier.padding(top = 8.dp, start = 10.dp, end = 10.dp),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        null -> Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp),
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = TextSecondary, fontSize = 16.sp)
    }
}

/**
 * Scroll only as far as needed to keep the focused tile (plus its scale-up and title) clear of the
 * viewport edges. Minimal movement reads as snappier than centering on every D-pad press.
 */
@Composable
private fun rememberEdgePaddingSpec(top: Dp, bottom: Dp): BringIntoViewSpec {
    val density = LocalDensity.current
    val topPx = with(density) { top.toPx() }
    val bottomPx = with(density) { bottom.toPx() }
    return remember(topPx, bottomPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val end = containerSize - bottomPx
                return when {
                    offset < topPx -> offset - topPx
                    offset + size > end -> min(offset - topPx, offset + size - end)
                    else -> 0f
                }
            }
        }
    }
}
