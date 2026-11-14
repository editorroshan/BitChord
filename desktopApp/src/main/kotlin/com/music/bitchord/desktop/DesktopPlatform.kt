package com.music.bitchord.desktop

/** Which desktop this is running on. */
internal object DesktopPlatform {

    private val name: String = System.getProperty("os.name").orEmpty()

    val isWindows: Boolean = name.startsWith("Windows", ignoreCase = true)

    val isLinux: Boolean = name.contains("linux", ignoreCase = true)

    val isMac: Boolean = name.startsWith("Mac", ignoreCase = true) || name.contains("darwin", ignoreCase = true)

    /** Whether the application draws the window's own frame instead of the system drawing it. */
    val drawsOwnWindowFrame: Boolean = isWindows

    /**
     * Whether the system's own close, minimize and zoom buttons float over the top-left corner of
     * the content. True on macOS, where the title bar is made transparent and the page runs under
     * it — see [DesktopMacWindow] — so the chrome has to leave that corner clear.
     */
    val hasInsetWindowButtons: Boolean = isMac

    /**
     * Whether empty chrome moves the window when it is dragged. Wherever there is no system title
     * bar to take hold of: the frame Windows draws for itself, and macOS' transparent one.
     */
    val chromeMovesWindow: Boolean = drawsOwnWindowFrame || isMac
}
