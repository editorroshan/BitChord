package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.AppReopenedListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What makes the window a Mac window rather than a Java one.
 *
 * Left alone, AWT gives the app the system's light title bar with "BitChord" in the middle of it,
 * sitting on top of a dark page. macOS' own apps have no such strip: the content runs to the top of
 * the window and the three window buttons float over its corner. The same is asked for here through
 * the client properties AWT has for it, so no native code is involved and nothing can be got wrong
 * at that level.
 *
 * Nothing in this object does anything on Windows or Linux.
 */
internal object DesktopMacWindow {

    /**
     * Has to run before AWT starts: these are read once, when the toolkit loads.
     */
    fun configureBeforeAwt() {
        if (!DesktopPlatform.isMac) return
        // The app is dark whatever the system is set to, so its window frame, menus and the
        // system's own dialogs are asked to be dark too rather than following the system.
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
        // What the menu bar and the Dock call the app when it is run unpackaged.
        System.setProperty("apple.awt.application.name", "BitChord")
        // The window's menus belong in the menu bar at the top of the screen, not in the window.
        System.setProperty("apple.laf.useScreenMenuBar", "true")
    }

    /**
     * Lets the content run under the title bar and takes the title out of it. The three window
     * buttons stay where the system puts them; the chrome leaves their corner clear — see
     * [DesktopPlatform.hasInsetWindowButtons].
     */
    fun install(window: JFrame) {
        if (!DesktopPlatform.isMac) return
        window.rootPane.apply {
            putClientProperty("apple.awt.fullWindowContent", true)
            putClientProperty("apple.awt.transparentTitleBar", true)
            putClientProperty("apple.awt.windowTitleVisible", false)
        }
    }

    /**
     * The things the Dock and the application menu ask of an app.
     *
     * [onReopen] is the Dock icon being clicked while the window is closed, which on a Mac always
     * brings the window back — closing a music player's window is not how it is quit.
     * [onPreferences] is "Settings…" in the application menu, and ⌘, with it.
     */
    fun installAppHandlers(onReopen: () -> Unit, onPreferences: () -> Unit) {
        if (!DesktopPlatform.isMac || !Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                desktop.addAppEventListener(AppReopenedListener { EventQueue.invokeLater(onReopen) })
            }
        }
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                desktop.setPreferencesHandler { EventQueue.invokeLater(onPreferences) }
            }
        }
    }

    /**
     * Whether the title strip is the tall, unified one: 52pt, with the three buttons centred in it.
     * Until then — or if the system refused — it is the plain 28pt strip.
     */
    private val _unified = MutableStateFlow(false)
    val unified: StateFlow<Boolean> = _unified

    /**
     * An empty toolbar in the unified style. Nothing is shown in it; what it does is make the
     * system's draggable title strip as tall as the app's own top bar, and centre the three window
     * buttons in it, the way Music, Finder and Xcode look. Dragging anywhere empty in that strip then
     * moves the window natively.
     *
     * Runs on AppKit's thread. Called once the window is on screen; harmless to call again.
     */
    fun installUnifiedToolbar(title: String) {
        if (!DesktopPlatform.isMac) return
        runCatching {
            ObjC.onMain {
                val window = ObjC.findWindow(title) ?: return@onMain
                if (ObjC.send(window, "toolbar") == null) {
                    val identifier = ObjC.nsString("bitchord.main")
                    val toolbar = ObjC.send(ObjC.send(ObjC.cls("NSToolbar"), "alloc"), "initWithIdentifier:", identifier)
                        ?: return@onMain
                    ObjC.send(toolbar, "setShowsBaselineSeparator:", 0L)
                    ObjC.send(window, "setToolbar:", toolbar)
                }
                if (ObjC.responds(window, "setToolbarStyle:")) {
                    ObjC.send(window, "setToolbarStyle:", TOOLBAR_STYLE_UNIFIED)
                    _unified.value = true
                }
            }
        }.onFailure { DesktopTrackLog.log("mac window: toolbar not installed (${it.message})") }
    }

    private val holds = AtomicInteger(0)
    @Volatile private var windowTitle = "BitChord"

    /**
     * Something under the pointer in the title strip wants presses for itself — a text field's
     * selection — rather than having them move the window. Counted, so two of them can overlap.
     */
    fun holdStill(hold: Boolean) {
        if (!DesktopPlatform.isMac) return
        val count = if (hold) holds.incrementAndGet() else holds.updateAndGet { (it - 1).coerceAtLeast(0) }
        val movable = count == 0
        runCatching {
            ObjC.onMain {
                val window = ObjC.findWindow(windowTitle) ?: return@onMain
                ObjC.send(window, "setMovable:", if (movable) 1L else 0L)
            }
        }
    }

    /** NSWindowToolbarStyleUnified. */
    private const val TOOLBAR_STYLE_UNIFIED = 3L

    /** Just enough of the Objective-C runtime to talk to the window. */
    internal object ObjC {
        private val objc: NativeLibrary by lazy { NativeLibrary.getInstance("objc") }
        private val system: NativeLibrary by lazy { NativeLibrary.getInstance("System") }
        private val msgSend: Function by lazy { objc.getFunction("objc_msgSend") }

        fun cls(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
        fun sel(name: String): Pointer? = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))

        fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? {
            if (receiver == null) return null
            return msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))
        }

        fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long {
            if (receiver == null) return 0L
            return msgSend.invokeLong(arrayOf(receiver, sel(selector), *args))
        }

        fun responds(receiver: Pointer?, selector: String): Boolean =
            (sendLong(receiver, "respondsToSelector:", sel(selector)) and 0xff) != 0L

        fun nsString(value: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", value)

        private fun string(ns: Pointer?): String? = send(ns, "UTF8String")?.getString(0)

        fun findWindow(title: String): Pointer? {
            val app = send(cls("NSApplication"), "sharedApplication")
            val windows = send(app, "windows") ?: return null
            val count = sendLong(windows, "count")
            for (i in 0 until count) {
                val window = send(windows, "objectAtIndex:", i)
                if (string(send(window, "title")) == title) return window
            }
            return null
        }

        /** Kept, so a block is never collected while AppKit still has it queued. */
        private val pending = CopyOnWriteArrayList<Work>()

        fun interface Work : Callback {
            fun invoke(context: Pointer?)
        }

        fun onMain(block: () -> Unit) {
            lateinit var work: Work
            work = Work {
                try {
                    block()
                } catch (t: Throwable) {
                    DesktopTrackLog.log("mac window: ${t.message}")
                } finally {
                    pending.remove(work)
                }
            }
            pending.add(work)
            val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
            system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, Pointer.NULL, work))
        }
    }
}
