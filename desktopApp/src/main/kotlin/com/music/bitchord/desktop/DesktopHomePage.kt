package com.music.bitchord.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.launch

// Home, the way Apple Music lays out its own: a large title, a row of tall "Top Picks" cards
// captioned in a colour taken from their artwork, then shelves of plain square cards, and shelves
// of songs as columns of rows. Same data and paging as the phone's Home; only the drawing differs.

private val HOME_GUTTER = 24.dp
private val CARD_WIDTH = 176.dp
private val VIDEO_CARD_WIDTH = 296.dp
private val PICK_WIDTH = 248.dp
private val TRACK_COLUMN_WIDTH = 340.dp
private const val TRACKS_PER_COLUMN = 4
private val CARD_RADIUS = 10.dp
private val PICK_KINDS = setOf("album", "single", "ep", "playlist", "mix", "station", "radio", "artist")

@Composable
internal fun DesktopHomePage(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemMenu: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
    recentsLoading: Boolean,
    currentSong: Song?,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 14.dp, bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "title") {
            Text(
                DesktopStrings["home", "Home"],
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 32.sp, letterSpacing = (-0.6).sp),
                color = Color.White,
                modifier = Modifier.padding(horizontal = HOME_GUTTER).padding(bottom = 18.dp),
            )
        }
        if (!signedIn) {
            item(key = "signin") { HomeSignIn(onSignIn) }
        }
        when (state) {
            is UiState.Loading -> {
                item(key = "skeleton-picks") { PicksSkeleton() }
                items(3, key = { "skeleton-$it" }) { ShelfSkeleton() }
            }
            is UiState.Error -> item(key = "error") {
                Column(
                    Modifier.fillMaxWidth().padding(HOME_GUTTER).padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.message, color = DesktopSecondary, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(14.dp))
                    HomePillButton(DesktopStrings["retry", "Try Again"], onRetry)
                }
            }
            is UiState.Success -> {
                if (recentsLoading) item(key = "skeleton-recents") { ShelfSkeleton() }
                var picksShown = false
                state.data.forEachIndexed { index, shelf ->
                    if (shelf.items.isEmpty()) return@forEachIndexed
                    val open: (ShelfItem) -> Unit = { onItemClick(it, shelf.title) }
                    val kind = when {
                        shelf.items.all { it.isTrack() } && shelf.items.size > TRACKS_PER_COLUMN -> ShelfKind.TRACKS
                        !picksShown && shelf.items.none { it.isVideo() } && shelf.items.count { !it.isTrack() } >= 3 &&
                            !shelf.title.equals("Recents", ignoreCase = true) -> ShelfKind.PICKS
                        else -> ShelfKind.CARDS
                    }
                    if (kind == ShelfKind.PICKS) picksShown = true
                    item(key = "shelf-$index-${shelf.title}") {
                        HomeShelfSection(shelf, kind, currentSong, open, onItemMenu)
                    }
                }
                if (loadingMore) item(key = "skeleton-more") { ShelfSkeleton() }
            }
        }
    }

    if (state is UiState.Success) {
        val loadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(listState, loadingMore) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }
}

private enum class ShelfKind { PICKS, CARDS, TRACKS }

private fun ShelfItem.isTrack(): Boolean = videoId != null && browseId == null

/** YouTube's own video stills are 16:9 and look wrong cropped square. */
private fun ShelfItem.isVideo(): Boolean = thumbnailUrl?.contains("i.ytimg.com/vi") == true

@Composable
private fun HomeShelfSection(
    shelf: HomeShelf,
    kind: ShelfKind,
    currentSong: Song?,
    onOpen: (ShelfItem) -> Unit,
    onMenu: (ShelfItem) -> Unit,
) {
    val rowState = rememberLazyListState()
    Column(Modifier.fillMaxWidth().padding(bottom = 30.dp)) {
        ShelfHeader(shelf, rowState)
        val spacing = if (kind == ShelfKind.TRACKS) 24.dp else 18.dp
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = HOME_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            when (kind) {
                ShelfKind.PICKS -> items(shelf.items, key = { it.key() }) { item ->
                    PickCard(item, shelf.title, onOpen, onMenu)
                }
                ShelfKind.CARDS -> items(shelf.items, key = { it.key() }) { item ->
                    HomeCard(item, playing = item.matches(currentSong), onOpen, onMenu)
                }
                ShelfKind.TRACKS -> itemsIndexed(shelf.items.chunked(TRACKS_PER_COLUMN)) { _, column ->
                    Column(Modifier.width(TRACK_COLUMN_WIDTH)) {
                        column.forEachIndexed { i, item ->
                            HomeTrackRow(item, playing = item.matches(currentSong), divider = i < column.lastIndex, onOpen, onMenu)
                        }
                    }
                }
            }
        }
    }
}

private fun ShelfItem.key(): String = (videoId ?: browseId ?: title) + "|" + subtitle

private fun ShelfItem.matches(song: Song?): Boolean =
    song != null && videoId != null && (videoId == song.videoId || (title == song.title && subtitle.contains(song.artist)))

@Composable
private fun ShelfHeader(shelf: HomeShelf, rowState: LazyListState) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val page = with(density) { (maxWidth - HOME_GUTTER * 3).toPx() }
        Row(
            Modifier.fillMaxWidth().padding(start = HOME_GUTTER, end = HOME_GUTTER - 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    shelf.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 21.sp, letterSpacing = (-0.35).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (shelf.subtitle.isNotBlank()) {
                    Text(
                        shelf.subtitle,
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val canBack = rowState.canScrollBackward
            val canForward = rowState.canScrollForward
            if (canBack || canForward) {
                ShellRoundButton(
                    onClick = { scope.launch { rowState.animateScrollBy(-page) } },
                    size = 28.dp,
                    enabled = canBack,
                    filled = false,
                    description = "Scroll back",
                ) {
                    Icon(Icons.Rounded.ChevronLeft, null, tint = if (canBack) Color.White else DesktopSecondary.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                }
                ShellRoundButton(
                    onClick = { scope.launch { rowState.animateScrollBy(page) } },
                    size = 28.dp,
                    enabled = canForward,
                    filled = false,
                    description = "Scroll forward",
                ) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = if (canForward) Color.White else DesktopSecondary.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Right click on a card is what holding one is on the phone: its menu. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.secondaryClick(onClick: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.buttons.isSecondaryPressed) onClick()
    }

/** A "Top Picks" card: label above, square artwork, and a caption in the artwork's own colour. */
@Composable
private fun PickCard(
    item: ShelfItem,
    shelfTitle: String,
    onOpen: (ShelfItem) -> Unit,
    onMenu: (ShelfItem) -> Unit,
) {
    // "Album • Sai Abhyankkar": the kind goes above the card, as Apple labels its picks, and only
    // the names stay in the caption.
    val parts = item.subtitle.split(" • ", " · ").map(String::trim).filter(String::isNotEmpty)
    val kind = parts.firstOrNull()?.takeIf { it.lowercase() in PICK_KINDS }
    val caption = if (kind != null) parts.drop(1).joinToString(" · ") else item.subtitle
    val eyebrow = when {
        kind == null -> ""
        shelfTitle.contains("new", ignoreCase = true) -> "New $kind"
        else -> kind
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val palette = rememberDesktopArtworkPalette(item.thumbnailUrl)
    val tone by animateColorAsState(lerp(palette.primary, Color.Black, 0.45f), tween(400))
    val lift by animateFloatAsState(if (hovered) 1.015f else 1f, tween(160))
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(PICK_WIDTH)) {
        Text(
            eyebrow.ifEmpty { " " },
            color = DesktopSecondary,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 7.dp, start = 2.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .scale(lift)
                .shadow(if (hovered) 18.dp else 6.dp, shape)
                .clip(shape)
                .background(tone)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { onOpen(item) }
                .secondaryClick { onMenu(item) },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                DesktopArtwork(item.thumbnailUrl, Modifier.fillMaxSize(), px = HEADER_ART_PX)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, tone))),
                )
                HoverPlay(hovered, Modifier.align(Alignment.BottomStart).padding(12.dp)) { onOpen(item) }
            }
            Column(
                Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 14.dp).padding(top = 6.dp, bottom = 12.dp),
            ) {
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    caption,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeCard(
    item: ShelfItem,
    playing: Boolean,
    onOpen: (ShelfItem) -> Unit,
    onMenu: (ShelfItem) -> Unit,
) {
    val video = item.isVideo()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dim by animateFloatAsState(if (hovered) 0.22f else 0f, tween(140))
    val shape = RoundedCornerShape(CARD_RADIUS)
    Column(
        Modifier
            .width(if (video) VIDEO_CARD_WIDTH else CARD_WIDTH)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onOpen(item) }
            .secondaryClick { onMenu(item) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (video) 16f / 9f else 1f)
                .shadow(4.dp, shape)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.06f)),
        ) {
            DesktopArtwork(item.thumbnailUrl, Modifier.fillMaxSize(), px = CARD_ART_PX)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Color.Black))
            HoverPlay(hovered, Modifier.align(Alignment.BottomStart).padding(10.dp)) { onOpen(item) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            color = if (playing) ShellAccent else Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                item.subtitle,
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                maxLines = if (video) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HoverPlay(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(140))
    if (alpha == 0f) return
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .size(34.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(if (hovered) ShellAccent else Color(0xE6202022))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun HomeTrackRow(
    item: ShelfItem,
    playing: Boolean,
    divider: Boolean,
    onOpen: (ShelfItem) -> Unit,
    onMenu: (ShelfItem) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent, tween(120))
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(fill)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { onOpen(item) }
                .secondaryClick { onMenu(item) }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f))) {
                DesktopArtwork(item.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
                if (hovered) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = if (playing) ShellAccent else Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.subtitle,
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (divider) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp)
                    .width(TRACK_COLUMN_WIDTH - 62.dp)
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
        }
    }
}

@Composable
private fun HomeSignIn(onSignIn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HOME_GUTTER)
            .padding(bottom = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(listOf(ShellAccent.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))),
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Sign in to YouTube Music",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Get recommendations made for you, and your playlists everywhere.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )
        }
        HomePillButton("Sign In", onSignIn)
    }
}

@Composable
private fun HomePillButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (hovered) Color.White.copy(alpha = 0.88f) else Color.White)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.Black, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun SkeletonBlock(width: Dp, height: Dp, radius: Dp = CARD_RADIUS) {
    Box(Modifier.size(width, height).clip(RoundedCornerShape(radius)).background(Color.White.copy(alpha = 0.06f)))
}

@Composable
private fun PicksSkeleton() {
    Column(Modifier.padding(bottom = 30.dp)) {
        Box(Modifier.padding(start = HOME_GUTTER, bottom = 14.dp)) { SkeletonBlock(160.dp, 20.dp, 6.dp) }
        Row(Modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true).padding(horizontal = HOME_GUTTER), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(5) { SkeletonBlock(PICK_WIDTH, PICK_WIDTH + 86.dp, 12.dp) }
        }
    }
}

@Composable
private fun ShelfSkeleton() {
    Column(Modifier.padding(bottom = 30.dp)) {
        Box(Modifier.padding(start = HOME_GUTTER, bottom = 14.dp)) { SkeletonBlock(200.dp, 20.dp, 6.dp) }
        Row(Modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true).padding(horizontal = HOME_GUTTER), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(7) {
                Column {
                    SkeletonBlock(CARD_WIDTH, CARD_WIDTH)
                    Spacer(Modifier.height(10.dp))
                    SkeletonBlock(120.dp, 11.dp, 4.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBlock(84.dp, 11.dp, 4.dp)
                }
            }
        }
    }
}
