package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 悬浮窗权限管理
 */
object OverlayPermissionManager {
    
    /**
     * 检查是否有悬浮窗权限
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    /**
     * 请求悬浮窗权限
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
    
    /**
     * 检查并提示权限
     */
    fun checkAndRequestPermission(context: Context, activity: AppCompatActivity): Boolean {
        if (!hasOverlayPermission(context)) {
            Toast.makeText(context, "需要悬浮窗权限才能显示记牌器", Toast.LENGTH_LONG).show()
            activity.startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ),
                REQUEST_CODE_OVERLAY
            )
            return false
        }
        return true
    }
    
    const val REQUEST_CODE_OVERLAY = 1001
}
