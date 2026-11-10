package com.music.bitchord.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.icons.BitChordIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.yield

// The window's frame: a top bar across the whole width with the search in the middle of it, a
// sidebar under it on the left, and the page as one rounded panel with the player floating over
// its foot. Spotify's arrangement, drawn the way Apple Music draws things.

/** What the window is painted with behind the panels. */
internal val ShellBase = Color(0xFF0A0A0B)

/** The page's panel, and the colour the pages are drawn against. */
internal val ShellPanel = Color(0xFF151517)

/** Buttons and fields in the top bar. */
private val ShellControl = Color(0xFF1E1E21)
private val ShellControlHover = Color(0xFF2A2A2E)

/** The one colour the chrome uses: Apple Music's red, which is also what marks the playing row. */
internal val ShellAccent = PlayingAccent

internal val SHELL_TOP_BAR_HEIGHT = 52.dp
private val SHELL_GAP = 8.dp
private val PANEL_RADIUS = 12.dp
private val SIDEBAR_WIDTH = 236.dp
private val RAIL_WIDTH = 72.dp

/** Below this the sidebar folds down to its icons. */
private val RAIL_BELOW = 1_040.dp

private val PLAYER_HEIGHT = 64.dp
private val PLAYER_MARGIN = 12.dp

/** How far the page keeps its last row clear of the player floating over it. */
internal val SHELL_CONTENT_BOTTOM = PLAYER_HEIGHT + PLAYER_MARGIN * 2 + 8.dp

/**
 * Where the system's window buttons are, so nothing is put under them. In the unified strip they
 * are centred in its 52pt and end 73pt in; in the plain one, 61pt.
 */
private val MAC_LIGHTS_UNIFIED = 84.dp
private val MAC_LIGHTS_PLAIN = 72.dp

@Composable
internal fun DesktopShellFrame(
    containerColor: Color,
    modifier: Modifier = Modifier,
    /** Receives true when its solid base should be left clear for the system material. */
    backdrop: @Composable (transparentBase: Boolean) -> Unit,
    topBar: @Composable () -> Unit,
    sidebar: @Composable (rail: Boolean) -> Unit,
    player: @Composable (width: Dp) -> Unit,
    /** The column on the far side of the page — the lyrics or the queue, when one is open. */
    trailing: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val haze = remember { HazeState() }
    val material by DesktopWindowBackdrop.active.collectAsState()
    val appBackground by DesktopWindowBackdrop.appBackground.collectAsState()
    val glass = material != DesktopBackdrop.OFF
    val materialBehindApp = glass && appBackground
    CompositionLocalProvider(LocalDesktopHaze provides haze) {
        Box(modifier.fillMaxSize().then(if (glass) Modifier else Modifier.background(ShellBase))) {
            Column(Modifier.fillMaxSize()) {
                DesktopTitleBar()
                topBar()
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val rail = maxWidth < RAIL_BELOW
                    Row(Modifier.fillMaxSize()) {
                        sidebar(rail)
                        val panelShape = RoundedCornerShape(PANEL_RADIUS)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(end = SHELL_GAP, bottom = SHELL_GAP)
                                .clip(panelShape)
                                .then(if (materialBehindApp) Modifier else Modifier.background(containerColor))
                                .border(0.5.dp, Color.White.copy(alpha = 0.05f), panelShape),
                        ) {
                            Row(Modifier.fillMaxSize()) {
                                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                                    val pageWidth = maxWidth
                                    Box(Modifier.fillMaxSize().hazeSource(haze)) {
                                        backdrop(materialBehindApp)
                                        // A little air under the panel's top edge, which the pages
                                        // themselves start right against.
                                        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
                                            content(PaddingValues(bottom = SHELL_CONTENT_BOTTOM))
                                        }
                                    }
                                    // Where the page meets the panel's top edge and the player: a
                                    // fade rather than a line.
                                    if (!materialBehindApp) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopCenter)
                                                .fillMaxWidth()
                                                .height(10.dp)
                                                .background(
                                                    Brush.verticalGradient(listOf(containerColor, containerColor.copy(alpha = 0f))),
                                                ),
                                        )
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .height(SHELL_CONTENT_BOTTOM + 12.dp)
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(containerColor.copy(alpha = 0f), containerColor.copy(alpha = 0.72f)),
                                                    ),
                                                ),
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(horizontal = 16.dp)
                                            .padding(bottom = PLAYER_MARGIN),
                                    ) { player(pageWidth - 32.dp) }
                                }
                                Box(Modifier.fillMaxHeight()) { trailing() }
                            }
                        }
                    }
                }
            }
            overlay()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

@Composable
internal fun DesktopShellTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    homeSelected: Boolean,
    browseSelected: Boolean,
    onHome: () -> Unit,
    onBrowse: () -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit,
    accountAvatar: String?,
    onOpenAccounts: () -> Unit,
    /** Search was picked from the keyboard or the menu: the field takes the focus. */
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
) {
    val titleBarEnabled by DesktopTitleBarSetting.enabled.collectAsState()
    val inlineCaption = DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled
    val unified by DesktopMacWindow.unified.collectAsState()
    DesktopTitleBarDragArea(Modifier.fillMaxWidth().height(SHELL_TOP_BAR_HEIGHT)) {
        CenteredBar(
            Modifier.fillMaxSize().padding(end = 14.dp),
            leading = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        DesktopPlatform.hasInsetWindowButtons ->
                            Spacer(Modifier.width(if (unified) MAC_LIGHTS_UNIFIED else MAC_LIGHTS_PLAIN))
                        inlineCaption -> {
                            Spacer(Modifier.width(12.dp))
                            DesktopWindowButtons()
                            Spacer(Modifier.width(10.dp))
                        }
                        else -> Spacer(Modifier.width(14.dp))
                    }
                    ShellRoundButton(
                        onClick = onBack,
                        enabled = canGoBack,
                        size = 32.dp,
                        filled = false,
                        description = DesktopStrings["back", "Back"],
                    ) {
                        Icon(
                            Icons.Rounded.ChevronLeft,
                            null,
                            tint = if (canGoBack) Color.White else DesktopSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            },
            center = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShellRoundButton(
                        onClick = onHome,
                        size = 40.dp,
                        description = DesktopStrings["home", "Home"],
                    ) {
                        Icon(
                            BitChordIcons.Home,
                            null,
                            tint = if (homeSelected) Color.White else DesktopSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ShellSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearch = onSearch,
                        onClear = onClearSearch,
                        browseSelected = browseSelected,
                        onBrowse = onBrowse,
                        focusSearch = focusSearch,
                        onSearchFocused = onSearchFocused,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            },
            trailing = {
                DesktopAccountButton(avatar = accountAvatar, onClick = onOpenAccounts)
            },
        )
    }
}

/**
 * Three groups in a row, the middle one centred on the bar — not on what is left between the other
 * two — and as wide as it may be without running into either.
 */
@Composable
private fun CenteredBar(
    modifier: Modifier,
    leading: @Composable () -> Unit,
    center: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    maxCenterWidth: Dp = 560.dp,
    gap: Dp = 16.dp,
) {
    Layout(
        contents = listOf(leading, center, trailing),
        modifier = modifier,
    ) { (leadingM, centerM, trailingM), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val l = leadingM.first().measure(loose)
        val r = trailingM.first().measure(loose)
        val gapPx = gap.roundToPx()
        val side = maxOf(l.width, r.width) + gapPx
        // Centred if it can be; otherwise slid towards the roomier side.
        val centredRoom = width - side * 2
        val freeRoom = width - l.width - r.width - gapPx * 2
        val room = maxOf(centredRoom, minOf(freeRoom, maxCenterWidth.roundToPx())).coerceAtLeast(0)
        val c = centerM.first().measure(Constraints(maxWidth = minOf(room, maxCenterWidth.roundToPx()), maxHeight = height))
        layout(width, height) {
            l.place(0, (height - l.height) / 2)
            r.place(width - r.width, (height - r.height) / 2)
            val ideal = (width - c.width) / 2
            val x = ideal.coerceIn(l.width + gapPx, (width - r.width - gapPx - c.width).coerceAtLeast(l.width + gapPx))
            c.place(x, (height - c.height) / 2)
        }
    }
}

@Composable
private fun ShellSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    browseSelected: Boolean,
    onBrowse: () -> Unit,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            runCatching { focusRequester.requestFocus() }
            onSearchFocused()
        }
    }
    // Coming back to the window puts the caret back where it was.
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) {
            restoreFocus = focused
        } else if (restoreFocus) {
            yield()
            runCatching { focusRequester.requestFocus() }
            restoreFocus = false
        }
    }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(percent = 50)
    val fill by animateColorAsState(
        when {
            focused -> ShellControlHover
            hovered -> Color(0xFF26262A)
            else -> ShellControl
        },
        tween(140),
    )
    val edge by animateColorAsState(
        if (focused) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.06f),
        tween(140),
    )
    Box(
        modifier
            .widthIn(min = 220.dp)
            .fillMaxWidth()
            .height(40.dp)
            .hoverable(hover)
            // Pressing in the field selects text; in the title strip it would move the window.
            .desktopHoldsWindowStill()
            .clip(shape)
            .background(fill)
            .border(if (focused) 1.5.dp else 1.dp, edge, shape)
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                BitChordIcons.Search,
                contentDescription = null,
                tint = if (focused) Color.White else DesktopSecondary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        DesktopStrings["d_search_prompt", "What do you want to play?"],
                        color = DesktopSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    onSearch()
                                    true
                                }
                                Key.Escape -> {
                                    if (query.isNotEmpty()) onClear() else focusManager.clearFocus()
                                    true
                                }
                                else -> false
                            }
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontSize = 14.5.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
            }
            if (query.isNotEmpty()) {
                ShellRoundButton(
                    onClick = onClear,
                    size = 28.dp,
                    filled = false,
                    description = DesktopStrings["clear", "Clear"],
                ) {
                    Icon(Icons.Rounded.Close, null, tint = DesktopSecondary, modifier = Modifier.size(17.dp))
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            ShellRoundButton(
                onClick = onBrowse,
                size = 30.dp,
                filled = false,
                description = DesktopStrings["d_explore", "Explore"],
            ) {
                Icon(
                    BitChordIcons.Explore,
                    null,
                    tint = if (browseSelected) Color.White else DesktopSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * While the pointer is over this, a press-and-drag on macOS' title strip is left to it rather than
 * moving the window — see [DesktopMacWindow.holdStill].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.desktopHoldsWindowStill(): Modifier {
    if (!DesktopPlatform.isMac) return this
    var holding by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (holding) DesktopMacWindow.holdStill(false) }
    }
    return this
        .onPointerEvent(PointerEventType.Enter) {
            if (!holding) {
                holding = true
                DesktopMacWindow.holdStill(true)
            }
        }
        .onPointerEvent(PointerEventType.Exit) {
            if (holding) {
                holding = false
                DesktopMacWindow.holdStill(false)
            }
        }
}

/** A round button: a press shrinks it a touch, the pointer over it lifts its fill. */
@Composable
internal fun ShellRoundButton(
    onClick: () -> Unit,
    size: Dp,
    description: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1f, tween(110))
    val fill by animateColorAsState(
        when {
            !enabled -> if (filled) ShellControl else Color.Transparent
            hovered -> if (filled) ShellControlHover else Color.White.copy(alpha = 0.08f)
            else -> if (filled) ShellControl else Color.Transparent
        },
        tween(120),
    )
    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(fill)
            .hoverable(interaction, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------------------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------------------------

@Composable
internal fun DesktopShellSidebar(
    rail: Boolean,
    destination: DesktopDestination,
    settingsOpen: Boolean,
    accountPlaylists: List<ShelfItem>,
    localPlaylists: List<DesktopPlaylist>,
    openedCollectionId: String?,
    downloadsActive: Int,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onOpenAccountPlaylist: (ShelfItem) -> Unit,
    onOpenLocalPlaylist: (DesktopPlaylist) -> Unit,
    onNewPlaylist: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val onPage = !settingsOpen && openedCollectionId == null
    fun isAt(d: DesktopDestination) = onPage && destination == d
    Column(
        Modifier
            .width(if (rail) RAIL_WIDTH else SIDEBAR_WIDTH)
            .fillMaxHeight()
            .padding(start = 10.dp, end = 10.dp, bottom = 14.dp, top = 2.dp),
    ) {
        val main = listOf(
            Triple(DesktopDestination.LISTEN_NOW, BitChordIcons.Home, DesktopStrings["home", "Home"]),
            Triple(DesktopDestination.EXPLORE, BitChordIcons.Explore, DesktopStrings["d_explore", "Explore"]),
            Triple(DesktopDestination.LIBRARY, BitChordIcons.Library, DesktopStrings["library", "Library"]),
        )
        main.forEach { (d, icon, label) ->
            ShellSidebarItem(icon, label, isAt(d), rail, accentIcon = true) { onDestinationSelected(d) }
        }
        ShellSidebarHeader(DesktopStrings["library", "Library"], rail)
        ShellSidebarItem(BitChordIcons.Clock, DesktopStrings["history", "History"], isAt(DesktopDestination.HISTORY), rail) {
            onDestinationSelected(DesktopDestination.HISTORY)
        }
        ShellSidebarItem(
            BitChordIcons.Download,
            DesktopStrings["downloads", "Downloads"],
            isAt(DesktopDestination.DOWNLOADS),
            rail,
            badge = downloadsActive.takeIf { it > 0 }?.toString(),
        ) { onDestinationSelected(DesktopDestination.DOWNLOADS) }
        ShellSidebarItem(BitChordIcons.MusicNote, DesktopStrings["d_local_music", "Local Music"], isAt(DesktopDestination.LOCAL_MUSIC), rail) {
            onDestinationSelected(DesktopDestination.LOCAL_MUSIC)
        }
        ShellSidebarHeader(DesktopStrings["playlists", "Playlists"], rail, action = onNewPlaylist)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(accountPlaylists, key = { "account:${it.browseId}" }) { playlist ->
                ShellPlaylistItem(
                    title = playlist.title,
                    artwork = playlist.thumbnailUrl,
                    selected = !settingsOpen && openedCollectionId == playlist.browseId,
                    rail = rail,
                ) { onOpenAccountPlaylist(playlist) }
            }
            items(localPlaylists, key = { "local:${it.id}" }) { playlist ->
                ShellPlaylistItem(
                    title = playlist.title,
                    artwork = null,
                    selected = !settingsOpen && openedCollectionId == playlist.id,
                    rail = rail,
                ) { onOpenLocalPlaylist(playlist) }
            }
            if (accountPlaylists.isEmpty() && localPlaylists.isEmpty() && !rail) {
                item {
                    Text(
                        "Your playlists will appear here",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        color = DesktopSecondary.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        ShellSidebarItem(Icons.Rounded.Settings, DesktopStrings["settings", "Settings"], settingsOpen, rail) {
            onOpenSettings()
        }
    }
}

@Composable
private fun ShellSidebarHeader(label: String, rail: Boolean, action: (() -> Unit)? = null) {
    if (rail) {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(22.dp).height(1.dp).background(Color.White.copy(alpha = 0.10f)))
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, top = 18.dp, bottom = 4.dp).height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = DesktopSecondary,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp, letterSpacing = 0.2.sp),
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            ShellRoundButton(onClick = action, size = 24.dp, filled = false, description = "New playlist") {
                Icon(BitChordIcons.Plus, null, tint = DesktopSecondary, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun ShellSidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    rail: Boolean,
    accentIcon: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            selected -> Color.White.copy(alpha = 0.11f)
            hovered -> Color.White.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        tween(120),
    )
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (rail) 44.dp else 34.dp)
            .clip(shape)
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = if (rail) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rail) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(
            icon,
            null,
            tint = when {
                selected || accentIcon -> ShellAccent
                else -> DesktopSecondary
            },
            modifier = Modifier.size(if (rail) 21.dp else 18.dp),
        )
        if (!rail) {
            Spacer(Modifier.width(11.dp))
            Text(
                label,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Text(
                    badge,
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ShellPlaylistItem(
    title: String,
    artwork: String?,
    selected: Boolean,
    rail: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = when {
        selected -> Color.White.copy(alpha = 0.11f)
        hovered -> Color.White.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (rail) 52.dp else 44.dp)
            .clip(shape)
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = title }
            .padding(horizontal = if (rail) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rail) Arrangement.Center else Arrangement.Start,
    ) {
        val artShape = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(if (rail) 40.dp else 32.dp).clip(artShape).background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                DesktopArtwork(artwork, Modifier.fillMaxSize(), px = ROW_ART_PX)
            } else {
                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = DesktopSecondary, modifier = Modifier.size(18.dp))
            }
        }
        if (!rail) {
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------------------------

@Composable
internal fun DesktopShellPlayer(
    width: Dp,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    previousEnabled: Boolean,
    volume: Float,
    shuffle: Boolean,
    repeatMode: DesktopRepeatMode,
    sidePanel: DesktopSidePanel?,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenAudioOutput: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val roomy = width >= 820.dp
    val medium = width >= 660.dp
    val idle = song == null
    Row(
        Modifier
            .widthIn(max = 940.dp)
            .width(width)
            .height(PLAYER_HEIGHT)
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .desktopBarGlass(shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Transport.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (medium) {
                PlayerIconButton(
                    onClick = { onShuffleChange(!shuffle) },
                    description = DesktopStrings["shuffle", "Shuffle"],
                    enabled = !idle,
                ) {
                    Icon(
                        BitChordIcons.Shuffle,
                        null,
                        tint = if (shuffle) ShellAccent else DesktopSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            PlayerIconButton(onClick = onPrevious, description = DesktopStrings["widget_previous", "Previous"], enabled = previousEnabled && !idle) {
                Icon(Icons.Rounded.FastRewind, null, tint = playerTint(previousEnabled && !idle), modifier = Modifier.size(24.dp))
            }
            ShellRoundButton(
                onClick = onPlayPause,
                size = 40.dp,
                enabled = !idle,
                filled = false,
                description = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.padding(horizontal = 2.dp),
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (idle) Color.White.copy(alpha = 0.25f) else Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoading && !idle) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            PlayerIconButton(onClick = onNext, description = DesktopStrings["widget_next", "Next"], enabled = !idle) {
                Icon(Icons.Rounded.FastForward, null, tint = playerTint(!idle), modifier = Modifier.size(24.dp))
            }
            if (medium) {
                PlayerIconButton(
                    onClick = { onRepeatModeChange(repeatMode.next()) },
                    description = "Repeat ${repeatMode.label()}",
                    enabled = !idle,
                ) {
                    val tint = if (repeatMode != DesktopRepeatMode.OFF) ShellAccent else DesktopSecondary
                    Box(contentAlignment = Alignment.Center) {
                        Icon(BitChordIcons.Repeat, null, tint = tint, modifier = Modifier.size(17.dp))
                        if (repeatMode == DesktopRepeatMode.ONE) {
                            Text(
                                "1",
                                color = tint,
                                fontWeight = FontWeight.Bold,
                                fontSize = 7.sp,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        // What is playing, and where in it.
        NowPlayingLcd(
            song = song,
            position = position,
            durationMs = durationMs,
            onSeek = onSeek,
            onOpen = onOpenNowPlaying,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        // Everything else.
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton(onClick = onOpenLyrics, description = DesktopStrings["lyrics", "Lyrics"], enabled = !idle) {
                Icon(
                    BitChordIcons.LyricsQuote,
                    null,
                    tint = if (sidePanel == DesktopSidePanel.LYRICS) ShellAccent else playerTint(!idle, DesktopSecondary),
                    modifier = Modifier.size(18.dp),
                )
            }
            PlayerIconButton(onClick = onOpenQueue, description = DesktopStrings["queue", "Queue"], enabled = !idle) {
                Icon(
                    BitChordIcons.Queue,
                    null,
                    tint = if (sidePanel == DesktopSidePanel.QUEUE) ShellAccent else playerTint(!idle, DesktopSecondary),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (medium) {
                PlayerIconButton(onClick = onOpenAudioOutput, description = DesktopStrings["audio_output", "Audio output"]) {
                    Icon(Icons.Rounded.Headphones, null, tint = DesktopSecondary, modifier = Modifier.size(18.dp))
                }
            }
            if (roomy) {
                var lastVolume by remember { mutableStateOf(if (volume > 0f) volume else 1f) }
                PlayerIconButton(
                    onClick = {
                        if (volume > 0f) {
                            lastVolume = volume
                            onVolumeChange(0f)
                        } else {
                            onVolumeChange(lastVolume)
                        }
                    },
                    description = DesktopStrings["d_volume", "Volume"],
                ) {
                    Icon(
                        when {
                            volume <= 0f -> Icons.Rounded.VolumeOff
                            volume < 0.5f -> Icons.Rounded.VolumeDown
                            else -> Icons.Rounded.VolumeUp
                        },
                        null,
                        tint = DesktopSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DesktopThinSlider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    idleHeight = 4.dp,
                    activeHeight = 7.dp,
                    modifier = Modifier.width(84.dp).padding(end = 6.dp),
                )
            }
        }
    }
}

private fun playerTint(enabled: Boolean, on: Color = Color.White): Color =
    if (enabled) on else DesktopSecondary.copy(alpha = 0.35f)

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    description: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    ShellRoundButton(
        onClick = onClick,
        size = 34.dp,
        enabled = enabled,
        filled = false,
        description = description,
        content = content,
    )
}

@Composable
private fun NowPlayingLcd(
    song: Song?,
    position: PlaybackPosition,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artShape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .size(40.dp)
                .clip(artShape)
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(enabled = song != null, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            if (song != null) {
                DesktopArtwork(song.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
            } else {
                Icon(BitChordIcons.MusicNote, null, tint = DesktopSecondary.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        if (song == null) {
            Text(
                DesktopStrings["d_not_playing", "Not Playing"],
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            return@Row
        }
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.Center) {
            Row(
                Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    song.title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, letterSpacing = (-0.1).sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.artist.isNotBlank()) {
                    Text(
                        "  ·  " + song.artist,
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            PlayerScrubber(position, durationMs, onSeek)
        }
    }
}

@Composable
private fun PlayerScrubber(position: PlaybackPosition, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val live = if (durationMs > 0L) (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val fraction = dragging ?: live
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTime((fraction * durationMs).toLong()),
            color = DesktopSecondary,
            style = timeStyle,
            modifier = Modifier.width(34.dp),
        )
        DesktopThinSlider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { if (durationMs > 0L) onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            idleHeight = 3.dp,
            activeHeight = 6.dp,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (durationMs > 0L) "-" + formatTime(durationMs - (fraction * durationMs).toLong()) else "",
            color = DesktopSecondary,
            style = timeStyle,
            modifier = Modifier.width(40.dp).padding(start = 6.dp),
        )
    }
}
