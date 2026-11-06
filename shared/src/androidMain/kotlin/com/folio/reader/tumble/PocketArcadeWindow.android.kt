package com.folio.reader.tumble

import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.folio.reader.ui.theme.FolioTheme

@Composable internal actual fun PocketArcadeWindow() {
    val view=LocalView.current
    val window=(view.parent as? DialogWindowProvider)?.window
    val background=FolioTheme.colors.background
    DisposableEffect(window,background) {
        if(window!=null) {
            WindowCompat.setDecorFitsSystemWindows(window,false)
            window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
            window.setFormat(PixelFormat.OPAQUE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.statusBarColor=android.graphics.Color.TRANSPARENT
            window.navigationBarColor=android.graphics.Color.TRANSPARENT
            val controller=WindowCompat.getInsetsController(window,view)
            controller.isAppearanceLightStatusBars=background.luminance()>.5f
            controller.isAppearanceLightNavigationBars=background.luminance()>.5f
        }
        onDispose { /* The dialog owns and destroys this window. */ }
    }
}
