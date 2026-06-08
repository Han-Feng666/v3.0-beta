package com.HanFeng.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object StableDialog {
    fun builder(context: Context): AlertDialog.Builder {
        // 使用标准 AlertDialog 而不是 MaterialAlertDialog
        // 原因：MaterialAlertDialog 的布局文件有兼容性问题
        // "You must supply a layout_width attribute"
        return AlertDialog.Builder(context)
    }
}

fun AlertDialog.Builder.showSafely(context: Context, logLabel: String): AlertDialog? {
    return runCatching { show() }
        .onFailure {
            // 静默失败，对话框显示问题不影响主流程
        }
        .getOrNull()
}

fun AlertDialog.showSafely(context: Context, logLabel: String): AlertDialog? {
    return runCatching {
        show()
        this
    }.onFailure {
        // 静默失败
    }.getOrNull()
}
