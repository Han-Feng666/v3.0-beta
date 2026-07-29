package com.HanFeng.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.HanFeng.R
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.FeatureSettingsRepository

/**
 * 快捷设置磁贴 - 用户不必进 App,直接从通知栏下拉切换广告拦截开关。
 *
 * 行为与 WiFi/蓝牙磁贴一致:
 *   - 当前 VPN 在运行 → 显示"已开启"高亮态,点击关闭 (调 NetworkKernel.stop + setAdBlockEnabled=false)
 *   - 未运行 → 点亮"已开启" (调 NetworkKernel.start + setAdBlockEnabled=true)
 *   - 未授权 VPN.prepare → 立即把磁贴灭回去并 Toast 提示用户先进 App 授权一次
 *
 * 状态刷新机制:
 *   - onStartListening / onTileAdded 调 syncState() 立即读 NetworkKernel.isRunning 写入 qsTile
 *   - register statusChangedAction Receiver,VPN 启停时收到 ACTION_STATUS_CHANGED 后 pushState
 */
@RequiresApi(Build.VERSION_CODES.N)
class AdBlockTileService : TileService() {

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NetworkKernel.statusChangedAction) {
                pushState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            statusReceiver,
            IntentFilter(NetworkKernel.statusChangedAction)
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        pushState()
    }

    override fun onClick() {
        super.onClick()
        val ctx = this
        if (NetworkKernel.isRunning()) {
            // 关闭
            FeatureSettingsRepository.setAdBlockEnabled(ctx, false)
            NetworkKernel.stop(ctx)
            pushState()
        } else {
            // 开启前需先有 VPN 授权。如果未授权, 磁贴点亮后立即回退, Toast 提示。
            val prepareIntent = runCatching { VpnService.prepare(ctx) }.getOrNull()
            if (prepareIntent != null) {
                Toast.makeText(
                    ctx,
                    "请先开启一次寒枫应用以授予 VPN 权限",
                    Toast.LENGTH_LONG
                ).show()
                pushState()
                return
            }
            FeatureSettingsRepository.setAdBlockEnabled(ctx, true)
            NetworkKernel.start(ctx, userInitiated = true)
            pushState()
        }
    }

    private fun pushState() {
        val tile = qsTile ?: return
        val running = NetworkKernel.isRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "广告拦截"
        tile.contentDescription = if (running) "广告拦截已开启" else "广告拦截已关闭"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_ad_block_tile)
        tile.updateTile()
    }
}
