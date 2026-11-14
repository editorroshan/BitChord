package com.music.bitchord.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// Two-finger trackpad swipes, the way the Mac's own apps and Spotify answer them.
//
// macOS hands a two-finger swipe to the app as a scroll: as pan events with a start and an end
// where the system has them, and otherwise as plain scroll events — on this Mac's Java, scroll
// events, the sideways and the vertical part of a swipe arriving as separate alternating ones.
// Either way the system has already turned them into the way content should move, which is the way
// the fingers went only with natural scrolling on. These gestures are about the fingers — swipe
// down to close, up to open — so the system's setting is read and undone here.

/** Which way the fingers moved. */
internal enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Answers a two-finger swipe across this element once, when it has gone [distance] in one of
 * [directions] — or when the fingers lift short of that, but flicking fast enough to have got there.
 *
 * Whatever is inside gets the swipe first: one that a list or a row of cards scrolls with is
 * theirs for as long as it lasts. Of the rest, only a swipe mostly along an axis that one of
 * [directions] lies on is taken — and taken whole, so nothing around this element acts on it too.
 * The others pass through untouched.
 */
internal fun Modifier.desktopSwipe(
    directions: Set<SwipeDirection>,
    enabled: Boolean = true,
    distance: Dp = SWIPE_DISTANCE,
    /** How far an up or down swipe has to go, when that is less than a sideways one. */
    verticalDistance: Dp = distance,
    onSwipe: (SwipeDirection) -> Unit,
): Modifier = if (!enabled || directions.isEmpty()) this else composed {
    val density = LocalDensity.current
    val swipe = remember(directions, distance, verticalDistance, density) {
        with(density) {
            Swipe(directions, distance.toPx(), verticalDistance.toPx(), AXIS_DECISION.toPx(), WHEEL_STEP.toPx())
        }
    }
    swipe.onSwipe = onSwipe
    DisposableEffect(swipe) {
        val stop = DesktopTrackpad.onLift(swipe::lifted)
        onDispose { stop() }
    }
    pointerInput(swipe) {
        awaitPointerEventScope {
            while (true) swipe.step(readSwipe(swipe.reader))
        }
    }
}

/** One element's swipe, from its first scroll to its answer. Event thread only. */
private class Swipe(
    private val directions: Set<SwipeDirection>,
    private val neededSideways: Float,
    private val neededVertical: Float,
    private val decideAfter: Float,
    wheelStep: Float,
) {
    val reader = SwipeReader(wheelStep)
    var onSwipe: (SwipeDirection) -> Unit = {}

    private val horizontalWanted = SwipeDirection.LEFT in directions || SwipeDirection.RIGHT in directions
    private val verticalWanted = SwipeDirection.UP in directions || SwipeDirection.DOWN in directions
    private var state = SwipeState.UNDECIDED
    private var moved = Offset.Zero
    private var horizontal = false
    private val speed = FingerSpeed()

    private fun reset() {
        state = SwipeState.UNDECIDED
        moved = Offset.Zero
        speed.clear()
    }

    fun step(read: SwipeStep?) {
        if (read == null) {
            reset()
            return
        }
        val change = read.change
        if (read.coasting) {
            // The fingers are off: nothing more to read, but what was taken stays taken.
            if (DesktopTrackpad.isClaimed) change.consume()
            return
        }
        if (read.fresh) reset()
        when (state) {
            SwipeState.NOT_MINE -> return
            SwipeState.DONE -> {
                change.consume()
                return
            }
            SwipeState.UNDECIDED -> if (change.isConsumed) {
                // Something inside scrolled with it: theirs, for the whole swipe.
                state = SwipeState.NOT_MINE
                return
            }
            SwipeState.MINE -> Unit
        }
        moved += read.delta
        speed.add(change.uptimeMillis, read.delta)
        // A swipe's sideways and vertical parts arrive in alternation, and the first few say
        // little about which it is: wait until it is going clearly one way. Until then nothing is
        // taken, so whatever is around this can still have it.
        if (abs(moved.x) + abs(moved.y) < decideAfter) return
        val sideways = when {
            abs(moved.x) >= abs(moved.y) * AXIS_LEAN -> true
            abs(moved.y) >= abs(moved.x) * AXIS_LEAN -> false
            else -> return
        }
        if ((sideways && !horizontalWanted) || (!sideways && !verticalWanted)) {
            if (state == SwipeState.MINE) state = SwipeState.UNDECIDED
            return
        }
        state = SwipeState.MINE
        horizontal = sideways
        DesktopTrackpad.claim()
        change.consume()
        answer(if (sideways) moved.x else moved.y)
    }

    /** The fingers lifted: a flick that was on its way far enough counts as having got there. */
    fun lifted(gesture: Int, at: Long) {
        if (!reader.inSwipe || reader.gesture != gesture) return
        if (state == SwipeState.MINE) {
            val flick = speed.at(at) * FLICK_MS
            answer(if (horizontal) moved.x + flick.x else moved.y + flick.y)
        }
        reader.end()
    }

    private fun answer(along: Float) {
        if (abs(along) < (if (horizontal) neededSideways else neededVertical)) return
        val direction = when {
            horizontal && along > 0 -> SwipeDirection.RIGHT
            horizontal -> SwipeDirection.LEFT
            along > 0 -> SwipeDirection.DOWN
            else -> SwipeDirection.UP
        }
        // One answer per swipe, however far it carries on.
        state = SwipeState.DONE
        if (direction in directions) onSwipe(direction)
    }
}

/**
 * Scrolling over this element turns a value up or down, as Spotify's volume does: [onAdjust] is
 * handed how far, in pixels, with fingers moving up or right — or a wheel turned away from you —
 * counting as up. Every scroll over it is taken, so nothing behind it moves too; a swipe's coasting
 * is taken and dropped, so the value stops where the fingers left it.
 */
internal fun Modifier.desktopScrollAdjust(onAdjust: (Float) -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onAdjust)
    pointerInput(Unit) {
        val reader = SwipeReader(WHEEL_STEP.toPx())
        awaitPointerEventScope {
            while (true) {
                // Taken before anything around it, so nothing behind the slider scrolls too.
                val read = readSwipe(reader, PointerEventPass.Initial) ?: continue
                val change = read.change
                if (change.isConsumed) continue
                change.consume()
                if (read.coasting) continue
                DesktopTrackpad.claim()
                val amount = read.delta.x - read.delta.y
                if (amount != 0f) latest(amount)
            }
        }
    }
}

private enum class SwipeState { UNDECIDED, MINE, NOT_MINE, DONE }

/**
 * One step of a swipe: how the fingers moved, and the change to consume if it is taken.
 * [coasting] steps come after the fingers have lifted, and say nothing of how they moved.
 */
internal class SwipeStep(
    val delta: Offset,
    val change: PointerInputChange,
    val fresh: Boolean,
    val coasting: Boolean = false,
)

/**
 * Turns the pointer events a swipe arrives as into steps of one swipe after another.
 *
 * Where the trackpad says so itself — see [DesktopTrackpad] — a swipe begins when the fingers
 * start moving and ends when they lift, however soon the next one follows. Otherwise it begins
 * with a pan start, or with the first scroll after a pause, and ends with a pan end, or with a
 * pause of [SWIPE_GAP_MS].
 */
internal class SwipeReader(internal val wheelStep: Float) {
    internal var inSwipe = false
    internal var fresh = true
    internal var coastingUntil = 0L

    /** The trackpad's own count of this swipe; 0 for a wheel, which has none. */
    internal var gesture = 0

    /** When the last step of this swipe happened, as the event's own time. */
    internal var lastStepAt = 0L

    internal fun step(delta: Offset, change: PointerInputChange): SwipeStep {
        // Timed by when the scroll happened, not when it is got round to: a busy frame — the full
        // player being put together under a pull, say — holds events back and then hands them over
        // in a rush, and timed by arrival that read as the fingers having lifted.
        val now = change.uptimeMillis
        // For a wheel, a pause since the last step starts a new swipe, whether or not the timeout
        // below got round to saying so: on the desktop it only runs when the window has something
        // to draw. Fingers may rest mid-swipe for as long as they like.
        if (gesture == 0 && inSwipe && now - lastStepAt > SWIPE_GAP_MS) fresh = true
        lastStepAt = now
        val first = fresh || !inSwipe
        inSwipe = true
        fresh = false
        return SwipeStep(delta, change, first)
    }

    internal fun end() {
        inSwipe = false
        fresh = true
    }

    /** How long without a scroll before this swipe is given up on. */
    internal fun patience(): Long = swipePatience(gesture)
}

/**
 * A wheel's swipe is over after a short pause. Fingers still on the trackpad say when they lift,
 * so theirs is only given up on after a long one, in case that word never comes.
 */
private fun swipePatience(gesture: Int): Long =
    if (gesture != 0 && DesktopTrackpad.fingersDown && DesktopTrackpad.gesture == gesture) {
        FINGERS_DOWN_GAP_MS
    } else {
        SWIPE_GAP_MS
    }

/** The next step of a swipe; null when one has just ended, so the caller can let go of it. */
internal suspend fun AwaitPointerEventScope.readSwipe(
    reader: SwipeReader,
    pass: PointerEventPass = PointerEventPass.Main,
): SwipeStep? {
    while (true) {
        // Only scrolling keeps a swipe going. Other pointer events keep coming without it — Compose
        // sends one each frame something animates under a still pointer — and counting those, a
        // swipe over the player never ended, and every swipe after it was taken as more of it.
        val event: PointerEvent? = if (reader.inSwipe) {
            val left = reader.patience() - (System.currentTimeMillis() - reader.lastStepAt)
            if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent(pass) }
        } else {
            awaitPointerEvent(pass)
        }
        if (event == null) {
            // The fingers lifting may have ended it already while this waited.
            if (!reader.inSwipe) continue
            reader.end()
            return null
        }
        val change = event.changes.firstOrNull() ?: continue
        when (event.type) {
            PointerEventType.PanStart -> {
                reader.inSwipe = true
                reader.fresh = true
                reader.gesture = 0
                reader.lastStepAt = change.uptimeMillis
            }
            PointerEventType.PanEnd -> {
                reader.end()
                reader.coastingUntil = change.uptimeMillis + COASTING_MS
                return null
            }
            PointerEventType.PanMove -> return reader.step(fingers(change.panOffset), change)
            PointerEventType.Scroll -> when (DesktopTrackpad.kind) {
                DesktopTrackpad.Kind.MOMENTUM -> return SwipeStep(Offset.Zero, change, fresh = false, coasting = true)
                DesktopTrackpad.Kind.FINGERS -> {
                    // A new swipe, however soon after the last.
                    if (reader.gesture != DesktopTrackpad.gesture) {
                        reader.gesture = DesktopTrackpad.gesture
                        reader.fresh = true
                    }
                    return reader.step(fingers(-change.scrollDelta * reader.wheelStep), change)
                }
                DesktopTrackpad.Kind.WHEEL -> {
                    if (reader.gesture != 0) {
                        reader.gesture = 0
                        reader.fresh = true
                    }
                    if (change.uptimeMillis < reader.coastingUntil) continue
                    return reader.step(fingers(-change.scrollDelta * reader.wheelStep), change)
                }
            }
            else -> Unit
        }
    }
}

/**
 * How fast the fingers are going, from the last few steps of a swipe — so that when they lift, a
 * quick flick can be told from a slow drag that stopped at the same place.
 */
internal class FingerSpeed {
    private val times = ArrayDeque<Long>()
    private val moves = ArrayDeque<Offset>()

    fun add(time: Long, move: Offset) {
        times.addLast(time)
        moves.addLast(move)
        while (times.size > 1 && time - times.first() > SPEED_WINDOW_MS) {
            times.removeFirst()
            moves.removeFirst()
        }
    }

    fun clear() {
        times.clear()
        moves.clear()
    }

    /** Pixels a millisecond at [time]; nothing if the fingers had come to rest before then. */
    fun at(time: Long): Offset {
        if (times.size < 2 || time - times.last() > SPEED_STALE_MS) return Offset.Zero
        var travelled = Offset.Zero
        // The first step's own move was made before the stretch being timed.
        for (i in 1 until moves.size) travelled += moves[i]
        val took = (times.last() - times.first()).coerceAtLeast(SPEED_SHORTEST_MS)
        return travelled / took.toFloat()
    }

    private companion object {
        const val SPEED_WINDOW_MS = 90L
        const val SPEED_STALE_MS = 70L
        const val SPEED_SHORTEST_MS = 8L
    }
}

/** How far a swipe has to go before it skips a track or opens the player. */
internal val SWIPE_DISTANCE = 90.dp

/**
 * How the fingers moved, from how the system says content should move: the same with natural
 * scrolling on, the other way round with it off.
 */
internal fun fingers(contentDelta: Offset): Offset =
    if (NaturalScrolling.on) contentDelta else -contentDelta

/**
 * System Settings › Trackpad › Scroll direction: Natural. On unless it has been turned off, and
 * looked at again now and then, off the UI thread, in case it is changed while the app runs.
 */
private object NaturalScrolling {
    @Volatile private var value: Boolean = read()
    @Volatile private var readAt: Long = System.currentTimeMillis()

    val on: Boolean
        get() {
            if (System.currentTimeMillis() - readAt > REFRESH_MS) {
                readAt = System.currentTimeMillis()
                Thread({ value = read() }, "natural-scrolling").apply { isDaemon = true }.start()
            }
            return value
        }

    private fun read(): Boolean {
        if (!DesktopPlatform.isMac) return true
        return runCatching {
            val process = ProcessBuilder("defaults", "read", "-g", "com.apple.swipescrolldirection")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            // Absent when it has never been changed — and then it is natural.
            if (process.waitFor() != 0) true else text != "0"
        }.getOrDefault(true)
    }

    private const val REFRESH_MS = 15_000L
}

/** How far a swipe has to go before it is clear which way it is going. */
private val AXIS_DECISION = 14.dp

/** How much more one axis has to have moved than the other for a swipe to count as along it. */
private const val AXIS_LEAN = 1.4f

/** What one notch of a mouse wheel moves, as Compose scrolls a list by on macOS. */
internal val WHEEL_STEP = 10.dp

/** A pause this long between scroll events ends a swipe that sent no end of its own. */
internal const val SWIPE_GAP_MS = 220L

/** How long after a pan ends its coasting scrolls are ignored. */
internal const val COASTING_MS = 700L

/** How long fingers known to be on the trackpad may go without scrolling before they are given up on. */
private const val FINGERS_DOWN_GAP_MS = 3_000L

/**
 * How far ahead a lifting finger's speed is carried: a swipe counts as having gone as far as it
 * would have in this many more milliseconds.
 */
private const val FLICK_MS = 150f

/**
 * A two-finger swipe up — or down, unless [upward] — that drags something with it: [onPull] is
 * told how far the fingers have taken it, in pixels and never past [maxPx], for as long as they are
 * on the trackpad, and [onRelease] — the moment they lift — where it was heading: how far it had
 * got, carried on by how fast the fingers were going, so a quick flick lands further than it reached.
 *
 * Whatever is inside gets the swipe first: one a list scrolls with is the list's for the rest of
 * that swipe. Only a swipe clearly that way, not sideways, is taken; once it is, it is held, back
 * included, until the fingers lift. The coasting after they lift moves nothing.
 */
internal fun Modifier.desktopPullBySwipe(
    upward: Boolean,
    maxPx: Float,
    enabled: Boolean = true,
    onPull: (Float) -> Unit,
    onRelease: (Float) -> Unit,
): Modifier = if (!enabled) this else composed {
    // While a pull is held, keep the window drawing: the timeout that gives up on a wheel's pull
    // only runs when it does, and a still window might not draw again until the pointer moved.
    var holdingPull by remember { mutableStateOf(false) }
    LaunchedEffect(holdingPull) {
        while (holdingPull) withFrameNanos { }
    }
    val pull = remember { Pull() }
    pull.maxPx = maxPx
    pull.onPull = onPull
    pull.onRelease = onRelease
    pull.onHeld = { holdingPull = it }
    DisposableEffect(pull) {
        val stop = DesktopTrackpad.onLift(pull::lifted)
        onDispose { stop() }
    }
    pointerInput(upward, maxPx) {
        val wheelStep = WHEEL_STEP.toPx()
        val decideAfter = PULL_AXIS_DECISION.toPx()
        val sign = if (upward) -1f else 1f
        awaitPointerEventScope {
            while (true) {
                // Seen on the way down to whatever is inside, so a held pull can be kept from it.
                val event = if (pull.inSwipe) {
                    val left = swipePatience(pull.gesture) - (System.currentTimeMillis() - pull.lastScrollAt)
                    if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Initial) }
                } else {
                    awaitPointerEvent(PointerEventPass.Initial)
                }
                if (event == null) {
                    if (pull.inSwipe) pull.finish(System.currentTimeMillis())
                    continue
                }
                val change = event.changes.firstOrNull() ?: continue
                val now = change.uptimeMillis
                val move = when (event.type) {
                    PointerEventType.PanStart -> {
                        pull.finish(now)
                        pull.gesture = 0
                        pull.inSwipe = true
                        pull.lastScrollAt = now
                        continue
                    }
                    PointerEventType.PanEnd -> {
                        pull.finish(now)
                        pull.coastingUntil = now + COASTING_MS
                        continue
                    }
                    PointerEventType.PanMove -> fingers(change.panOffset)
                    PointerEventType.Scroll -> when (DesktopTrackpad.kind) {
                        DesktopTrackpad.Kind.MOMENTUM -> {
                            // The fingers are off. What was taken stays taken; a list's own
                            // coasting goes on to the list.
                            if (DesktopTrackpad.isClaimed) change.consume()
                            continue
                        }
                        DesktopTrackpad.Kind.FINGERS -> {
                            // A new swipe, however soon after the last.
                            if (pull.gesture != DesktopTrackpad.gesture) {
                                pull.finish(now)
                                pull.gesture = DesktopTrackpad.gesture
                                // Already someone's — the bar's, which opened the player this
                                // now finds itself under: the rest of it does nothing here.
                                pull.foreign = DesktopTrackpad.isClaimed
                            }
                            if (pull.foreign) {
                                change.consume()
                                continue
                            }
                            fingers(-change.scrollDelta * wheelStep)
                        }
                        DesktopTrackpad.Kind.WHEEL -> {
                            if (pull.gesture != 0) {
                                pull.finish(now)
                                pull.gesture = 0
                            }
                            if (now < pull.coastingUntil) continue
                            fingers(-change.scrollDelta * wheelStep)
                        }
                    }
                    else -> continue
                }
                // For a wheel, a pause since the last scroll is a new swipe, whether or not the
                // timeout above got round to saying so: on the desktop it only runs when the
                // window draws. Timed by when the scroll happened — see [SwipeReader.step].
                if (pull.gesture == 0 && pull.inSwipe && now - pull.lastScrollAt > SWIPE_GAP_MS) pull.finish(now)
                pull.lastScrollAt = now
                val delta = move.y * sign
                pull.inSwipe = true
                if (pull.holding) {
                    change.consume()
                    pull.speed.add(now, Offset(0f, delta))
                    pull.pulled = (pull.pulled + delta).coerceIn(0f, maxPx)
                    pull.onPull(pull.pulled)
                    continue
                }
                if (pull.ignoring) continue
                // Let what is inside have it first, and see whether it took it.
                val after = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull() ?: continue
                if (after.isConsumed) {
                    pull.ignoring = true
                    continue
                }
                // Wait until it is clear which way the fingers are going: mostly sideways is not
                // a pull, and the other way is nothing here.
                pull.undecided += Offset(move.x, delta)
                pull.speed.add(now, Offset(0f, delta))
                if (abs(pull.undecided.x) + abs(pull.undecided.y) < decideAfter) continue
                if (abs(pull.undecided.x) * 1.2f >= abs(pull.undecided.y) || pull.undecided.y <= 0f) {
                    pull.ignoring = true
                    continue
                }
                pull.holding = true
                pull.onHeld(true)
                DesktopTrackpad.claim()
                after.consume()
                pull.pulled = pull.undecided.y.coerceIn(0f, maxPx)
                pull.onPull(pull.pulled)
            }
        }
    }
}

/** One element's pull, from its first scroll to the fingers lifting. Event thread only. */
private class Pull {
    var maxPx = 0f
    var onPull: (Float) -> Unit = {}
    var onRelease: (Float) -> Unit = {}
    var onHeld: (Boolean) -> Unit = {}

    var pulled = 0f

    /** This swipe has hold of what is pulled / belongs to something inside / to something else. */
    var holding = false
    var ignoring = false
    var foreign = false
    var inSwipe = false
    var undecided = Offset.Zero

    /** The trackpad's own count of this swipe; 0 for a wheel, which has none. */
    var gesture = 0

    /** When this swipe last scrolled, as the event's own time. Only scrolling keeps it going. */
    var lastScrollAt = 0L

    /** After a pan's end, its coasting arrives as plain scrolls: not a new swipe. */
    var coastingUntil = 0L
    val speed = FingerSpeed()

    fun lifted(gesture: Int, at: Long) {
        if (inSwipe && this.gesture == gesture) finish(at)
    }

    fun finish(at: Long) {
        if (holding) {
            onHeld(false)
            onRelease((pulled + speed.at(at).y * FLICK_MS).coerceIn(0f, maxPx))
        }
        undecided = Offset.Zero
        holding = false
        ignoring = false
        foreign = false
        inSwipe = false
        pulled = 0f
        speed.clear()
    }
}

/** How far a pull has to go before it is clear which way it is going. */
private val PULL_AXIS_DECISION = 12.dp
