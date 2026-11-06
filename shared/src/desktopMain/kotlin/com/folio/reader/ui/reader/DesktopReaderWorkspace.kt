package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import java.awt.Dialog
import java.awt.Frame
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.KeyEvent
import javax.swing.WindowConstants
import kotlin.math.roundToInt

internal val LocalWindowsReaderOwner = staticCompositionLocalOf<Window?> { null }

/** An owned window paints above Chromium without repaginating the book underneath it. */
@Composable
fun DesktopReaderWorkspaceOwner(owner: Window, content: @Composable () -> Unit) {
    val host = remember(owner) { DesktopWorkspaceOverlay(owner) }
    var visible by remember(owner) { mutableStateOf(owner.isShowing && !owner.isMinimized()) }
    DisposableEffect(owner) {
        fun refresh() { visible = owner.isShowing && !owner.isMinimized() }
        val component = object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) = refresh()
            override fun componentHidden(e: ComponentEvent) = refresh()
        }
        val state = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) = refresh()
        }
        owner.addComponentListener(component)
        if (owner is Frame) owner.addWindowStateListener(state)
        onDispose {
            owner.removeComponentListener(component)
            if (owner is Frame) owner.removeWindowStateListener(state)
        }
    }
    CompositionLocalProvider(LocalReaderWorkspaceOverlay provides host,
        LocalDesktopReaderChrome provides WindowsReaderChrome,
        com.folio.reader.ui.theme.LocalFolioWindowVisible provides visible,
        LocalWindowsReaderOwner provides owner, content = content)
}

/** AWT sees keyboard input even while the heavyweight Chromium child owns focus. */
@Composable
internal fun DesktopReaderFocusShortcut(toolOpen: Boolean, onToggle: () -> Unit) {
    val owner = LocalWindowsReaderOwner.current
    val blocked by rememberUpdatedState(toolOpen)
    val toggle by rememberUpdatedState(onToggle)
    DisposableEffect(owner) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val handler = DesktopReaderFocusHandler(
            isReaderActive = { owner != null && owner.isShowing && !owner.isMinimized() &&
                manager.activeWindow === owner && !blocked && manager.focusOwner !is javax.swing.text.JTextComponent },
            onToggle = { toggle() },
        )
        if (owner != null) manager.addKeyEventDispatcher(handler)
        onDispose { if (owner != null) manager.removeKeyEventDispatcher(handler) }
    }
}

private class DesktopWorkspaceOverlay(private val owner: Window) : ReaderWorkspaceOverlay {
    @Composable
    override fun Show(visible: Boolean, onDismiss: () -> Unit, content: @Composable (ReaderWorkspaceLayout) -> Unit) {
        // Local function references can compare equal while capturing different
        // panel flags. Preserve the latest instance for native event callbacks.
        val dismiss by remember { mutableStateOf(onDismiss, referentialEqualityPolicy()) }.apply { value = onDismiss }
        val open by rememberUpdatedState(visible)
        var ownerBounds by remember { mutableStateOf(Rectangle(owner.bounds)) }
        var ownerAvailable by remember { mutableStateOf(owner.isShowing && !owner.isMinimized()) }
        var ownerClosed by remember { mutableStateOf(false) }
        var openedOnce by remember { mutableStateOf(false) }
        DisposableEffect(owner) {
            fun refresh() {
                ownerBounds = Rectangle(owner.bounds)
                ownerAvailable = owner.isShowing && !owner.isMinimized()
            }
            val listener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = refresh()
                override fun componentResized(e: ComponentEvent) = refresh()
                override fun componentShown(e: ComponentEvent) = refresh()
                override fun componentHidden(e: ComponentEvent) = refresh()
            }
            val windowListener = object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent) = refresh()
                override fun windowIconified(e: WindowEvent) = refresh()
                override fun windowDeiconified(e: WindowEvent) = refresh()
                override fun windowStateChanged(e: WindowEvent) = refresh()
                override fun windowClosing(e: WindowEvent) { ownerClosed = true }
                override fun windowClosed(e: WindowEvent) { ownerClosed = true }
            }
            owner.addComponentListener(listener)
            owner.addWindowListener(windowListener)
            if (owner is Frame) owner.addWindowStateListener(windowListener)
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val escape = DesktopReaderEscapeHandler(
                isOpen = { open && ownerAvailable && !ownerClosed },
                isOwnerActive = { manager.activeWindow?.type != Window.Type.POPUP &&
                    generateSequence(manager.activeWindow) { it.owner }.any { it === owner } },
                onDismiss = { if (!com.folio.reader.ui.components.consumeFolioBack()) dismiss() },
            )
            manager.addKeyEventDispatcher(escape)
            onDispose {
                owner.removeComponentListener(listener)
                owner.removeWindowListener(windowListener)
                if (owner is Frame) owner.removeWindowStateListener(windowListener)
                manager.removeKeyEventDispatcher(escape)
            }
        }
        if (ownerClosed) return
        val density = LocalDensity.current
        val transform = owner.graphicsConfiguration.defaultTransform
        val scaleX = density.density / transform.scaleX.toFloat()
        val scaleY = density.density / transform.scaleY.toFloat()
        val bounds = desktopReaderWorkspaceBounds(ownerBounds, owner.insets, scaleX, scaleY, density.fontScale)
        val showing = visible && ownerAvailable && !ownerClosed && bounds != null
        val navigator = com.folio.reader.ui.render.LocalReaderPageNavigator.current
        LaunchedEffect(showing) {
            if (!showing && openedOnce) java.awt.EventQueue.invokeLater { navigator?.focus() }
        }
        // DialogWindow creates a GL/native peer even with visible=false. Do not enter it
        // until the first real open, then retain that peer without retaining tool content.
        if (showing && !openedOnce) SideEffect { openedOnce = true }
        if (!openedOnce) return
        val nativeBackground = com.folio.reader.ui.theme.FolioTheme.colors.surface.copy(alpha = 1f).toArgb()
        val closingListener = remember {
            object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { dismiss() } }
        }
        DialogWindow(
            // Reuse the native peer and GL context across toggles; rebuilding them
            // for every open adds a cold frame and a visible black flash.
            visible = showing,
            create = {
                ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                    isUndecorated = true
                    // Keep this native surface opaque. Per-pixel translucent GL windows
                    // can remain blank above CEF on Windows, even when Compose draws them.
                    background = java.awt.Color(nativeBackground, true)
                    title = "Folio reader tools"
                    defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                    addWindowListener(closingListener)
                    if (bounds != null) setBounds(bounds)
                }
            },
            dispose = { it.removeWindowListener(closingListener); it.dispose() },
            update = {
                if (bounds != null && it.bounds != bounds) it.bounds = bounds
                val background = java.awt.Color(nativeBackground, true)
                if (it.background != background) it.background = background
                it.isEnabled = showing
            },
        ) {
            val windowDensity = LocalDensity.current
            Box(Modifier.fillMaxSize().background(com.folio.reader.ui.theme.FolioTheme.colors.surface.copy(alpha = 1f))) {
                com.folio.reader.ui.components.FolioSuppressibleBackdrop(suppress = true) {
                    CompositionLocalProvider(LocalReaderWorkspaceFloating provides true,
                        LocalDesktopReaderChrome provides WindowsReaderChrome,
                        LocalDensity provides Density(windowDensity.density, density.fontScale)) {
                        if (showing && bounds != null) content(ReaderWorkspaceLayout(
                            (bounds.width / scaleX).dp, (bounds.height / scaleY).dp))
                    }
                }
            }
        }
    }
}

private fun Window.isMinimized(): Boolean = this is Frame && extendedState and Frame.ICONIFIED != 0

/** AWT bounds/insets use screen user units, not Compose's device pixels. */
internal fun desktopReaderWorkspaceBounds(owner: Rectangle, insets: Insets, scaleX: Float = 1f,
    scaleY: Float = scaleX, fontScale: Float = 1f): Rectangle? {
    if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) return null
    val clientWidth = (owner.width - insets.left - insets.right).coerceAtLeast(0)
    val clientHeight = (owner.height - insets.top - insets.bottom).coerceAtLeast(0)
    val top = (desktopReaderTopBarHeight.value * scaleY).roundToInt()
    val bottom = (desktopReaderBottomBarHeight.value * scaleY).roundToInt()
    val height = clientHeight - top - bottom
    if (clientWidth < 1 || height < 1) return null
    val typeScale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 1.2f) else 1f
    val width = minOf((416 * scaleX * typeScale).roundToInt().coerceAtLeast(1), clientWidth)
    return Rectangle(owner.x + insets.left + clientWidth - width, owner.y + insets.top + top, width, height)
}

internal class DesktopReaderFocusHandler(private val isReaderActive: () -> Boolean,
    private val onToggle: () -> Unit) : KeyEventDispatcher {
    private var pressed = false
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pressed && event.id == KeyEvent.KEY_TYPED && event.keyChar.lowercaseChar() == 'f') {
            event.consume()
            return true
        }
        if (event.keyCode != KeyEvent.VK_F) return false
        if (pressed) {
            if (event.id == KeyEvent.KEY_RELEASED) pressed = false
            event.consume()
            return true
        }
        val modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.META_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
        if (event.id != KeyEvent.KEY_PRESSED || event.modifiersEx and modifiers != 0 || !isReaderActive()) return false
        pressed = true
        onToggle()
        event.consume()
        return true
    }
}

/** Consume the release too: the main window uses Escape-up to navigate back. */
internal class DesktopReaderEscapeHandler(
    private val isOpen: () -> Boolean,
    private val isOwnerActive: () -> Boolean,
    private val onDismiss: () -> Unit,
) : KeyEventDispatcher {
    private var pressed = false
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.VK_ESCAPE) return false
        if (pressed) {
            if (event.id == KeyEvent.KEY_RELEASED) pressed = false
            event.consume()
            return true
        }
        if (event.id != KeyEvent.KEY_PRESSED || !isOpen() || !isOwnerActive()) return false
        pressed = true
        onDismiss()
        event.consume()
        return true
    }
}
