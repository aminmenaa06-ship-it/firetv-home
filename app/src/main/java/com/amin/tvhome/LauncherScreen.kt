@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalTextApi::class)

package com.amin.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0E0F12)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextSecondary = Color(0xA6F5F5F7)
private val TileCorner = 14.dp
private val TileShape = RoundedCornerShape(TileCorner)
private val ScreenPadding = 52.dp
private val GridTop = 34.dp // the focused row always rests here, tvOS-style

// Inter is the closest open-source match to Apple's SF Pro; one variable file covers all weights.
private val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

// Two springs drive every motion: a lively one for the focus lift (slight overshoot, like tvOS)
// and a critically damped one for anything that should settle without bouncing.
private val LiftSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 420f)
private val SettleSpring = spring<Float>(dampingRatio = 1f, stiffness = 320f)

private enum class Section(val title: String) { Apps("Apps"), Settings("Settings") }

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, onSurface = TextPrimary)) {
        ProvideTextStyle(TextStyle(fontFamily = Inter), content)
    }
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
) {
    var section by remember { mutableStateOf(Section.Apps) }
    val appsGrid = rememberLazyGridState()
    val firstApp = remember { FocusRequester() }
    val tabs = remember { Section.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var rootHasFocus by remember { mutableStateOf(false) }
    var focusedAmbient by remember { mutableStateOf<ImageBitmap?>(null) }

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

    Box(Modifier.fillMaxSize().background(Bg)) {
        Backdrop(focusedAmbient)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(GridTop)) {
            Column(
                Modifier
                    .fillMaxSize()
                    // Focus guard: if the focused tile vanishes (app uninstalled, list refreshed),
                    // the remote would otherwise go dead. Put focus back on something reachable.
                    .onFocusChanged {
                        rootHasFocus = it.hasFocus
                        // Focus hand-offs between groups report a transient "nothing focused";
                        // only act if it's still lost a frame later.
                        if (!it.hasFocus) scope.launch {
                            withFrameNanos { }
                            if (!rootHasFocus) reclaimFocus()
                        }
                    },
            ) {
                TopBar(section, tabs, onSelect = { section = it })
                AnimatedContent(
                    targetState = section,
                    transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
                    label = "section",
                ) { shown ->
                    when (shown) {
                        Section.Apps -> AppsGrid(
                            apps, appsGrid, firstApp, cachedArt, loadArt, onLaunch, onAppInfo,
                            onAmbient = { focusedAmbient = it },
                        )
                        Section.Settings -> SettingsGrid(settings, onSetting)
                    }
                }
            }
        }
    }
}

private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess

/**
 * Full-screen wash taken from the focused app's art. Changes are debounced so holding the D-pad
 * doesn't strobe colors, then crossfade slowly; a scrim keeps tiles and labels legible.
 */
@Composable
private fun Backdrop(ambient: ImageBitmap?) {
    val latest by rememberUpdatedState(ambient)
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.collectLatest {
            if (it == null) return@collectLatest
            if (shown != null) delay(140)
            shown = it
        }
    }
    Crossfade(
        targetState = shown,
        animationSpec = tween(700, easing = LinearOutSlowInEasing),
        label = "backdrop",
    ) { bitmap ->
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
            )
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.05f),
                    0.55f to Color.Black.copy(alpha = 0.30f),
                    1f to Color.Black.copy(alpha = 0.60f),
                ),
            ),
    )
}

/**
 * Liquid-glass stand-in that works on API 30 (no RenderEffect): what's behind is already a soft
 * blur, so a faint brightening body plus a bright top rim fading downward reads as a glass lens.
 */
private fun Modifier.glass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.07f))))
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.06f))),
        shape = shape,
    )

@Composable
private fun TopBar(section: Section, tabs: Map<Section, FocusRequester>, onSelect: (Section) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 24.dp)
            .height(52.dp),
    ) {
        // Coming up from the grid lands on the selected tab, not whichever is geometrically closest.
        Row(
            Modifier
                .align(Alignment.Center)
                .glass(CircleShape)
                .padding(5.dp)
                .focusRestorer { tabs.getValue(section) }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
        Clock(Modifier.align(Alignment.CenterEnd))
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
            selectedContainerColor = Color.White.copy(alpha = 0.16f),
            selectedContentColor = TextPrimary,
            focusedContainerColor = Color.White.copy(alpha = 0.94f),
            focusedContentColor = Color.Black,
            focusedSelectedContainerColor = Color.White.copy(alpha = 0.94f),
            focusedSelectedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
            pressedSelectedContainerColor = Color.White,
            pressedSelectedContentColor = Color.Black,
        ),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.04f, focusedSelectedScale = 1.04f),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
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
    Text(text = text, modifier = modifier, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
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
    onAmbient: (ImageBitmap) -> Unit,
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
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = GridTop, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                val art by produceState(cachedArt(app), app) {
                    if (value == null) value = loadArt(app)
                }
                var focused by remember { mutableStateOf(false) }
                // Art can finish loading after focus lands (first launch), so key on both.
                LaunchedEffect(focused, art) {
                    if (focused) art?.let { onAmbient(it.ambient) }
                }
                Tile(
                    label = app.label,
                    onClick = { onLaunch(app) },
                    onLongClick = { onAppInfo(app) },
                    onFocusChange = { focused = it },
                    modifier = if (index == 0) Modifier.focusRequester(firstApp) else Modifier,
                ) {
                    AppArtwork(app.label, art)
                }
            }
        }
    }
}

@Composable
private fun SettingsGrid(items: List<SettingShortcut>, onOpen: (SettingShortcut) -> Unit) {
    val first = remember { FocusRequester() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer { first },
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = GridTop, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items, key = { _, s -> s.label }) { index, item ->
            Tile(
                label = item.label,
                showLabel = false,
                glass = true,
                onClick = { onOpen(item) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            ) {
                Text(
                    text = item.label,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * tvOS-style focusable tile: 5:3, no border. Focus springs it up with a soft shadow, a glassy rim
 * and highlight, and one sweep of light across the face; the title fades in underneath.
 * Animated values are read inside graphicsLayer/draw blocks, so focus changes skip recomposition.
 */
@Composable
private fun Tile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: (Boolean) -> Unit = {},
    showLabel: Boolean = true,
    glass: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = when {
            pressed -> 1.05f
            focused -> 1.12f
            else -> 1f
        },
        animationSpec = LiftSpring,
        label = "scale",
    )
    val lift = animateFloatAsState(if (focused) 1f else 0f, SettleSpring, label = "lift")
    val sheen = remember { Animatable(1f) }
    val latestOnFocusChange by rememberUpdatedState(onFocusChange)
    LaunchedEffect(focused) {
        latestOnFocusChange(focused)
        if (focused) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(5f / 3f)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    // Glass is translucent, so an elevation shadow would show through it as a hard
                    // dark core; glass tiles lift by brightening instead.
                    shadowElevation = if (glass) 0f else lift.value * 22.dp.toPx()
                    shape = TileShape
                },
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(TileShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = TextPrimary,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = TextPrimary,
                pressedContainerColor = Color.Transparent,
                pressedContentColor = TextPrimary,
            ),
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (glass) {
                            Modifier.drawBehind { drawRect(Color.White.copy(alpha = 0.09f + 0.13f * lift.value)) }
                        } else {
                            Modifier
                        },
                    )
                    .drawWithContent {
                        drawContent()
                        drawSpecular(lift.value, sheen.value)
                    },
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .graphicsLayer {
                    val a = if (showLabel) lift.value else 0f
                    alpha = a
                    translationY = (1f - a) * -6.dp.toPx()
                },
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DrawScope.drawSpecular(lift: Float, sheen: Float) {
    // Hairline rim, brighter along the top edge, like light catching the edge of glass.
    val stroke = 1.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.10f + 0.36f * lift), Color.White.copy(alpha = 0.03f + 0.08f * lift)),
        ),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(TileCorner.toPx() - stroke / 2),
        style = Stroke(width = stroke),
    )
    if (lift <= 0.01f) return
    // Resting highlight on the upper half of the face.
    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f * lift), 0.5f to Color.Transparent))
    // One sweep of light as focus arrives.
    if (sheen < 1f) {
        val w = size.width
        val x = -0.6f * w + sheen * 1.9f * w
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.26f * lift), Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + 0.5f * w, size.height),
            ),
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
            filterQuality = FilterQuality.Medium,
        )
        is AppArt.Icon -> Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(art.tint.scaled(0.92f), art.tint.scaled(0.6f)))),
            contentAlignment = Alignment.Center,
        ) {
            // Most of these are square legacy icons with their own background: round them off
            // so they sit on the tile like an app icon rather than a pasted-in square.
            Image(
                bitmap = art.image,
                contentDescription = label,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(15.dp)),
            )
        }
        null -> Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

private fun Color.scaled(f: Float) = Color(red * f, green * f, blue * f, alpha)

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = TextSecondary, fontSize = 16.sp)
    }
}

/**
 * tvOS keeps the focused row at a fixed height and slides the grid under it. Pin every focused
 * tile's top to [pivot] (the first row's resting spot) and glide there on a critically damped
 * spring; the grid clamps at both ends on its own.
 */
@Composable
private fun rememberPivotSpec(pivot: Dp): BringIntoViewSpec {
    val pivotPx = with(LocalDensity.current) { pivot.toPx() }
    return remember(pivotPx) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 240f)

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - pivotPx
        }
    }
}
