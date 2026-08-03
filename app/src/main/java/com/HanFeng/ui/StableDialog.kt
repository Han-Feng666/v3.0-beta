package com.HanFeng.ui

import android.content.Context
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AlertDialog
import com.HanFeng.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * StableDialog：统一的弹窗工厂，保证所有调用入口圆角/阴影/按钮配色/入场动画一致。
 *
 * Material 风格弹窗优先用 R.style.ThemeOverlay_HanFeng_Dialog，该主题已挂上 bg_dialog_window
 * （含阴影与圆角）；AppCompat 兜底路径会显式补一次背景和入场动画以保持视觉一致。
 */
object StableDialog {

    fun builder(context: Context): AlertDialog.Builder {
        return AlertDialog.Builder(context, R.style.ThemeOverlay_HanFeng_AppCompatDialog)
    }

    /**
     * 返回 MaterialAlertDialogBuilder，用于 setIcon / setMultiChoiceItems 等场景。
     */
    fun materialBuilder(context: Context): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_HanFeng_Dialog)
    }
}

private fun AlertDialog.applyEnhancedWindow(): AlertDialog {
    window?.let { w ->
        w.setBackgroundDrawableResource(R.drawable.bg_dialog_window)
        w.setWindowAnimations(R.style.HanFengDialogAnimation)
    }
    return this
}

private fun AlertDialog.playEnterAnimation(): AlertDialog {
    runCatching {
        val cardView = findViewById<View>(androidx.appcompat.R.id.parentPanel)
            ?: findViewById<View>(android.R.id.content)
        cardView?.let {
            val anim: Animation = AnimationUtils.loadAnimation(it.context, R.anim.dialog_enter)
            it.startAnimation(anim)
        }
    }
    return this
}

fun AlertDialog.Builder.showSafely(context: Context, logLabel: String): AlertDialog? {
    return runCatching {
        create().applyEnhancedWindow().let { dlg ->
            dlg.show()
            dlg.playEnterAnimation()
            dlg
        }
    }
        .onFailure { Log.w("StableDialog", "$logLabel: ${it.message}", it) }
        .getOrNull()
}

fun AlertDialog.showSafely(context: Context, logLabel: String): AlertDialog? {
    return runCatching {
        show()
        applyEnhancedWindow()
        playEnterAnimation()
        this
    }.onFailure { Log.w("StableDialog", "$logLabel: ${it.message}", it) }.getOrNull()
}
