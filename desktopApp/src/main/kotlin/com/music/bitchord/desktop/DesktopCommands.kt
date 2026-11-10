package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import java.awt.KeyboardFocusManager

/**
 * The things the keyboard and the menu bar can ask of the app, in one place.
 *
 * The window's shortcuts and macOS' menu bar both live outside the composition that owns the
 * player and the pages, so neither can call into it. The app fills these in once it is up; until
 * then every one of them does nothing.
 */
internal object DesktopCommands {

    @Volatile var playPause: () -> Unit = {}
    @Volatile var next: () -> Unit = {}
    @Volatile var previous: () -> Unit = {}
    @Volatile var nudgeVolume: (Float) -> Unit = {}
    @Volatile var back: () -> Unit = {}
    @Volatile var focusSearch: () -> Unit = {}
    @Volatile var goHome: () -> Unit = {}
    @Volatile var goExplore: () -> Unit = {}
    @Volatile var goLibrary: () -> Unit = {}
    @Volatile var openSettings: () -> Unit = {}
    @Volatile var openPlayer: () -> Unit = {}
    @Volatile var toggleLyrics: () -> Unit = {}
    @Volatile var toggleQueue: () -> Unit = {}

    /** One press of ⌘↑ or ⌘↓: a sixteenth of the range, the step the system's own keys take. */
    private const val VOLUME_STEP = 1f / 16f

    /** Held so a key kept down toggles playback once rather than on every repeat. */
    private var spaceHeld = false

    /**
     * A key nothing on the page wanted.
     *
     * Bare keys are only taken while nothing is being typed into. A text field leaves the *press*
     * of an ordinary character unconsumed — it is the typed character that it takes — so without
     * that check a space in the search field would also pause the music.
     */
    fun onWindowKey(event: KeyEvent): Boolean {
        if (event.key == Key.Spacebar && event.type == KeyEventType.KeyUp) {
            spaceHeld = false
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false
        val primary = if (DesktopPlatform.isMac) event.isMetaPressed else event.isCtrlPressed
        val bare = !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed
        if (bare) {
            if (event.key != Key.Spacebar || event.isShiftPressed || typing()) return false
            if (!spaceHeld) {
                spaceHeld = true
                playPause()
            }
            return true
        }
        if (!primary || event.isAltPressed) return false
        if (event.isShiftPressed) {
            // On macOS these are the menu bar's own shortcuts, and it has already acted on them.
            if (DesktopPlatform.isMac) return false
            return when (event.key) {
                Key.L -> act(toggleLyrics)
                Key.U -> act(toggleQueue)
                Key.F -> act(openPlayer)
                else -> false
            }
        }
        // The arrows move the caret while something is being typed into; they are the transport's
        // only otherwise.
        if (!typing()) {
            when (event.key) {
                Key.DirectionRight -> return act(next)
                Key.DirectionLeft -> return act(previous)
                Key.DirectionUp -> return act { nudgeVolume(VOLUME_STEP) }
                Key.DirectionDown -> return act { nudgeVolume(-VOLUME_STEP) }
                else -> Unit
            }
        }
        if (DesktopPlatform.isMac) return false
        return when (event.key) {
            Key.F, Key.K -> act(focusSearch)
            Key.LeftBracket -> act(back)
            Key.One -> act(goHome)
            Key.Two -> act(goExplore)
            Key.Three -> act(goLibrary)
            Key.Comma -> act(openSettings)
            else -> false
        }
    }

    private inline fun act(command: () -> Unit): Boolean {
        command()
        return true
    }

    /**
     * Whether the keyboard is going into a text field. Compose gives the window's content an input
     * method to talk to for exactly as long as one of its text fields has the focus.
     */
    private fun typing(): Boolean = runCatching {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.inputMethodRequests != null
    }.getOrDefault(false)
}

/**
 * macOS' menu bar: the menus every Mac app is expected to have beside its own name, and where its
 * shortcuts can be found. The other platforms have no menu bar to put one in — a strip of menus
 * across the top of this window would be the one thing in it that did not belong — so there the
 * same shortcuts are answered by [DesktopCommands.onWindowKey] alone.
 */
@Composable
internal fun FrameWindowScope.DesktopMenuBar() {
    if (!DesktopPlatform.isMac) return
    MenuBar {
        Menu(DesktopStrings["d_menu_controls", "Controls"]) {
            // No shortcuts on these: Space and the ⌘-arrows also belong to whatever text field has
            // the focus, and a menu shortcut would take them from it. The window answers them.
            Item(DesktopStrings["d_menu_play_pause", "Play/Pause"]) { DesktopCommands.playPause() }
            Item(DesktopStrings["widget_next", "Next"]) { DesktopCommands.next() }
            Item(DesktopStrings["widget_previous", "Previous"]) { DesktopCommands.previous() }
            Separator()
            Item(DesktopStrings["d_menu_volume_up", "Increase Volume"]) { DesktopCommands.nudgeVolume(1f / 16f) }
            Item(DesktopStrings["d_menu_volume_down", "Decrease Volume"]) { DesktopCommands.nudgeVolume(-1f / 16f) }
        }
        Menu(DesktopStrings["d_menu_view", "View"]) {
            Item(
                DesktopStrings["d_menu_now_playing", "Show Now Playing"],
                shortcut = KeyShortcut(Key.F, meta = true, shift = true),
            ) { DesktopCommands.openPlayer() }
            Item(
                DesktopStrings["d_menu_lyrics", "Show Lyrics"],
                shortcut = KeyShortcut(Key.L, meta = true, shift = true),
            ) { DesktopCommands.toggleLyrics() }
            Item(
                DesktopStrings["d_menu_queue", "Show Queue"],
                shortcut = KeyShortcut(Key.U, meta = true, shift = true),
            ) { DesktopCommands.toggleQueue() }
        }
        Menu(DesktopStrings["d_menu_go", "Go"]) {
            Item(
                DesktopStrings["back", "Back"],
                shortcut = KeyShortcut(Key.LeftBracket, meta = true),
            ) { DesktopCommands.back() }
            Separator()
            Item(
                DesktopStrings["home", "Home"],
                shortcut = KeyShortcut(Key.One, meta = true),
            ) { DesktopCommands.goHome() }
            Item(
                DesktopStrings["d_explore", "Explore"],
                shortcut = KeyShortcut(Key.Two, meta = true),
            ) { DesktopCommands.goExplore() }
            Item(
                DesktopStrings["library", "Library"],
                shortcut = KeyShortcut(Key.Three, meta = true),
            ) { DesktopCommands.goLibrary() }
            Separator()
            Item(
                DesktopStrings["search", "Search"],
                shortcut = KeyShortcut(Key.F, meta = true),
            ) { DesktopCommands.focusSearch() }
        }
    }
}
