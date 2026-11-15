package com.ledpiano.app.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 16 (targetSdk 36) always draws apps edge to edge, under the status
 * and navigation bars. Pads [this] root view by the system bar and display
 * cutout sizes so buttons and the keyboard stay clear of them.
 */
fun View.padForSystemBars() {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
        insets
    }
    requestApplyInsets()
}
