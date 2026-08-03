package com.HanFeng.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 *   - 当前 VPN 在运行 -> 显示"已开启"高亮态,点击关闭
 *   - 未运行 -> 点亮"已开启"
 *   - 未授权 VPN.prepare -> 立即把磁贴灭回去并 Toast 提示用户先进 App 授权一次
 *
 * Android 14+ 注意事项:
 *   - onClick 默认跑在 binder 线程, Toast/updateTile 必须 post 到 main thread
 *   - 部分定制 ROM (HyperOS 等) 在 TileService 上下文弹 Toast 会抛 BadTokenException,
 *     需 try-catch 兜底, 否则 onClick 异常退出表现为"点击无反应"
 *   - VPN 启动是异步的, pushState() 同步调用时 isRunning 仍为 false,
 *     需要给一个乐观临时态 + 等 broadcast 真值修正
 */
@RequiresApi(Build.VERSION_CODES.N)
class AdBlockTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NetworkKernel.statusChangedAction) {
                pushState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Android 13+ 强制要求 registerReceiver 显式声明 RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED,
        // 否则抛 SecurityException 导致 TileService 启动崩溃, app 会被系统反复尝试拉起又失败.
        // 本 receiver 只接收 app 进程内本地 broadcast, 必然为 NOT_EXPORTED.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(statusReceiver, IntentFilter(NetworkKernel.statusChangedAction), flags)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        pushState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        pushState()
    }

    override fun onClick() {
        super.onClick()
        // 整个 onClick 包 try-catch, 任何异常都吞掉并刷新 Tile 状态;
        // 否则 HyperOS 上某一步抛异常会让用户感觉"点击无反应"
        try {
            handleClick()
        } catch (t: Throwable) {
            // 静默回滚到真实状态
            pushState()
        }
    }

    private fun handleClick() {
        val ctx = this
        if (NetworkKernel.isRunning()) {
            // 关闭 - 立即给一个乐观临时态, broadcast 修正真值
            optimisticUpdate(newState = Tile.STATE_INACTIVE)
            FeatureSettingsRepository.setAdBlockEnabled(ctx, false)
            NetworkKernel.stop(ctx)
            pushState()
        } else {
            // 开启前需先有 VPN 授权。如果未授权, 磁贴点亮后立即回退, Toast 提示。
            val prepareIntent = runCatching { VpnService.prepare(ctx) }.getOrNull()
            if (prepareIntent != null) {
                // 没有 VPN 授权, 不能从 Tile 直接拉起 Activity (TileService 无 Activity 上下文),
                // 只能 Toast 提示用户进 App 授权一次
                safeToast("请先开启一次寒枫应用以授予 VPN 权限")
                pushState()
                return
            }
            // 立即给一个乐观临时态, 防止 onClick 返回时 isRunning 还没变导致 Tile 闪回 INACTIVE
            optimisticUpdate(newState = Tile.STATE_ACTIVE)
            FeatureSettingsRepository.setAdBlockEnabled(ctx, true)
            NetworkKernel.start(ctx, userInitiated = true)
            // 等 broadcast 修正真值; 兜底 2s 后再刷新一次防止 broadcast 丢失
            mainHandler.postDelayed({ pushState() }, 2000)
        }
    }

    /**
     * 乐观临时态: 立即把 Tile 切到目标 state, 提供点击反馈;
     * 真实状态由后续 broadcast 或 pushState() 兜底修正
     */
    private fun optimisticUpdate(newState: Int) {
        try {
            val tile = qsTile ?: return
            tile.state = newState
            tile.label = "寒枫"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_ad_block_tile)
            tile.updateTile()
        } catch (_: Throwable) {}
    }

    /**
     * 安全 Toast: HyperOS 等 ROM 在 TileService 上下文 makeText/show 可能抛
     * BadTokenException / NullPointerException, 全 try-catch 兜底
     */
    private fun safeToast(msg: String) {
        try {
            mainHandler.post {
                try {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun pushState() {
        // qsTile.updateTile 必须在 main thread 调用, 否则部分 ROM 上静默无效
        mainHandler.post {
            try {
                val tile = qsTile ?: return@post
                val running = NetworkKernel.isRunning()
                tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = "寒枫"
                tile.contentDescription = if (running) "寒枫已开启" else "寒枫已关闭"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_ad_block_tile)
                tile.updateTile()
            } catch (_: Throwable) {}
        }
    }
}
