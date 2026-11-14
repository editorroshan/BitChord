package com.music.bitchord.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.sun.jna.Callback
import com.sun.jna.Pointer
import java.awt.EventQueue

/**
 * What the trackpad is doing, as AppKit tells it and Java does not.
 *
 * Java hands a two-finger swipe over as plain scroll events: nothing says where one swipe ends and
 * the next begins, when the fingers lift, or which scrolls are the fingers and which the coasting
 * macOS keeps sending for a second or so afterwards. AppKit's own scroll events carry all of that
 * as phases, so they are read here, ahead of Java, and passed along on the event thread: each note
 * lands just before the scroll event it is about.
 *
 * Everything but [install] and the monitor itself is for the event thread only. Where the monitor
 * is not there — Windows, Linux, or macOS refusing it — every scroll is a [Kind.WHEEL] one, and
 * the gestures tell swipes apart by the pauses between them as they always did.
 */
internal object DesktopTrackpad {

    enum class Kind {
        /** A mouse wheel, or anything that says nothing about fingers. */
        WHEEL,

        /** Fingers moving on the trackpad. */
        FINGERS,

        /** The coasting after the fingers have lifted. */
        MOMENTUM,
    }

    /** What the scroll events arriving now are. */
    var kind = Kind.WHEEL
        private set

    /** Which swipe they belong to, coasting included; counted up as each one begins. */
    var gesture = 0
        private set

    /** Whether the fingers of [gesture] are still on the trackpad. */
    var fingersDown = false
        private set

    /** The swipe one of the app's gestures took for itself, so nothing else acts on the rest of it. */
    private var claimed = 0

    private val liftListeners = ArrayList<(gesture: Int, at: Long) -> Unit>()

    /** The swipe arriving now is taken: its coasting is kept from whatever is under the pointer. */
    fun claim() {
        if (kind != Kind.WHEEL) claimed = gesture
    }

    val isClaimed: Boolean get() = kind != Kind.WHEEL && gesture != 0 && claimed == gesture

    /** Told, on the event thread, the moment the fingers lift; the returned call stops it. */
    fun onLift(listener: (gesture: Int, at: Long) -> Unit): () -> Unit {
        liftListeners.add(listener)
        return { liftListeners.remove(listener) }
    }

    /**
     * What AppKit says of the scroll about to arrive: NSEvent's `phase` and `momentumPhase`, and
     * when, on the clock AWT stamps its events with.
     */
    fun announce(phase: Int, momentum: Int, at: Long) {
        when {
            momentum != 0 -> {
                lift(at)
                kind = Kind.MOMENTUM
            }
            phase and PHASE_BEGAN != 0 -> {
                lift(at)
                gesture++
                fingersDown = true
                kind = Kind.FINGERS
            }
            phase and (PHASE_ENDED or PHASE_CANCELLED) != 0 -> lift(at)
            phase and (PHASE_CHANGED or PHASE_STATIONARY) != 0 -> {
                // The beginning was missed — it went to another window, say.
                if (!fingersDown) {
                    gesture++
                    fingersDown = true
                }
                kind = Kind.FINGERS
            }
            // Fingers resting on the trackpad, nothing moving yet.
            phase != 0 -> Unit
            else -> kind = Kind.WHEEL
        }
    }

    private fun lift(at: Long) {
        if (!fingersDown) return
        fingersDown = false
        // Copied: a listener may leave while it is being told.
        for (listener in liftListeners.toList()) {
            runCatching { listener(gesture, at) }
        }
    }

    private const val PHASE_BEGAN = 1
    private const val PHASE_STATIONARY = 2
    private const val PHASE_CHANGED = 4
    private const val PHASE_ENDED = 8
    private const val PHASE_CANCELLED = 16

    /** NSEventMaskScrollWheel. */
    private const val MASK_SCROLL_WHEEL = 1L shl 22

    private fun interface Monitor : Callback {
        fun invoke(block: Pointer?, event: Pointer?): Pointer?
    }

    @Volatile private var installed = false

    /** Held for good: AppKit keeps calling it for as long as the app runs. */
    private var monitor: Monitor? = null

    /**
     * Starts listening to AppKit's scroll events. They are only looked at, and always handed back
     * as they came. Harmless to call again, and does nothing off macOS.
     */
    fun install() {
        if (!DesktopPlatform.isMac || installed) return
        installed = true
        runCatching {
            val objc = DesktopMacWindow.ObjC
            val phase = objc.sel("phase")
            val momentumPhase = objc.sel("momentumPhase")
            val handler = Monitor { _, event ->
                try {
                    if (event != null) {
                        val fingers = objc.sendLong(event, phase).toInt()
                        val momentum = objc.sendLong(event, momentumPhase).toInt()
                        val at = System.currentTimeMillis()
                        EventQueue.invokeLater { announce(fingers, momentum, at) }
                    }
                } catch (_: Throwable) {
                    // Whatever went wrong, the scroll itself must still get through.
                }
                event
            }
            monitor = handler
            val block = objc.globalBlock(handler)
            objc.onMain {
                val token = objc.send(
                    objc.cls("NSEvent"),
                    "addLocalMonitorForEventsMatchingMask:handler:",
                    MASK_SCROLL_WHEEL,
                    block,
                )
                DesktopTrackLog.log(
                    if (token != null) "trackpad: reading swipe phases" else "trackpad: no swipe phases, timing instead",
                )
            }
        }.onFailure { DesktopTrackLog.log("trackpad: no swipe phases, timing instead (${it.message})") }
    }
}

/**
 * For the root of the window: the coasting of a swipe one of the app's gestures took goes no
 * further. Without it, a swipe down that closed the full player carried on scrolling the page that
 * came up from under it.
 */
internal fun Modifier.desktopSwipeCoastingGuard(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            if (DesktopTrackpad.kind == DesktopTrackpad.Kind.MOMENTUM && DesktopTrackpad.isClaimed) {
                event.changes.forEach { it.consume() }
            }
        }
    }
}
