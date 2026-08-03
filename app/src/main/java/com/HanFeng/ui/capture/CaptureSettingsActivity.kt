package com.HanFeng.ui.capture

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.HanFeng.R
import com.HanFeng.capture.CaptureController
import com.HanFeng.capture.CaptureRepository
import com.HanFeng.ui.BaseActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 联动批次: 抓包功能在 SettingsActivity 内的独立面板入口。
 *
 * 暴露以下抓包专用设置(持久化到 [CaptureRepository]):
 *  1. body 预览上限(ALL_APPS / BY_APP 两档 SeekBar 与文字预览)
 *  2. 批量重放间隔(SeekBar, 0..2000ms, 100ms 单位)
 *  3. 列表自动滚动到最新(开/关)
 *  4. 导出脱敏默认开关(HAR / cURL)
 *  5. 批量重放前确认风险弹窗开关
 *  6. 抓包进行中通知栏常驻开关
 *
 * 关闭页面时按"应用"持久化; 若用户已在抓包 active 中调整 body 预览上限,
 * 当前会话不会生效(不影响已写入 ring 的 entry), 下次 enable 时按新上限截断。
 */
class CaptureSettingsActivity : BaseActivity() {

    private lateinit var skAllPreview: SeekBar
    private lateinit var tvAllPreviewValue: TextView
    private lateinit var skByAppPreview: SeekBar
    private lateinit var tvByAppPreviewValue: TextView
    private lateinit var skBatchInterval: SeekBar
    private lateinit var tvBatchIntervalValue: TextView
    private lateinit var cbAutoScroll: CheckBox
    private lateinit var cbRedactExport: CheckBox
    private lateinit var cbConfirmRiskyReplay: CheckBox
    private lateinit var cbNotifyActive: CheckBox
    private lateinit var etMaxStoreEntries: EditText
    private lateinit var etMaxAgeDays: EditText

    /** 50% = 8KB(默认 ALL_APPS), 70% = 32KB(默认 BY_APP), 90% = 256KB, 100% = 1MB — 用户友好三档+兜底。 */
    private val previewLevels = intArrayOf(4 * 1024, 8 * 1024, 16 * 1024, 32 * 1024, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1024 * 1024)
    private val previewLabels = previewLevels.map { prettyBytes(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_settings)
        title = getString(R.string.capture_settings_title)

        val cfg = CaptureRepository.loadConfig(this)

        skAllPreview = findViewById(R.id.skAllPreview)
        tvAllPreviewValue = findViewById(R.id.tvAllPreviewValue)
        skByAppPreview = findViewById(R.id.skByAppPreview)
        tvByAppPreviewValue = findViewById(R.id.tvByAppPreviewValue)
        skBatchInterval = findViewById(R.id.skBatchInterval)
        tvBatchIntervalValue = findViewById(R.id.tvBatchIntervalValue)
        cbAutoScroll = findViewById(R.id.cbAutoScroll)
        cbRedactExport = findViewById(R.id.cbRedactExport)
        cbConfirmRiskyReplay = findViewById(R.id.cbConfirmRiskyReplay)
        cbNotifyActive = findViewById(R.id.cbNotifyActive)
        etMaxStoreEntries = findViewById(R.id.etMaxStoreEntries)
        etMaxAgeDays = findViewById(R.id.etMaxAgeDays)
        etMaxStoreEntries.setText(cfg.maxStoreEntries.takeIf { it > 0 }?.toString() ?: "5000")
        etMaxAgeDays.setText(cfg.maxAgeDays.takeIf { it > 0 }?.toString() ?: "7")

        // body 预览上限: 9 档滑杆
        val allPreviewIdx = previewLevels.indexOfFirst { it == cfg.bodyPreviewBytesAll }.coerceAtLeast(0)
        val byAppPreviewIdx = previewLevels.indexOfFirst { it == cfg.bodyPreviewBytesByApp }.coerceAtLeast(0)
        skAllPreview.max = previewLevels.size - 1
        skAllPreview.progress = allPreviewIdx
        tvAllPreviewValue.text = previewLabels[allPreviewIdx]
        skAllPreview.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvAllPreviewValue.text = previewLabels[p.coerceIn(0, previewLabels.lastIndex)]
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        skByAppPreview.max = previewLevels.size - 1
        skByAppPreview.progress = byAppPreviewIdx
        tvByAppPreviewValue.text = previewLabels[byAppPreviewIdx]
        skByAppPreview.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvByAppPreviewValue.text = previewLabels[p.coerceIn(0, previewLabels.lastIndex)]
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 批量重放间隔: 0..4000ms 单位 100ms (SeekBar max=40)
        skBatchInterval.max = 40
        val intervalStep = (cfg.batchIntervalMs / 100L).toInt().coerceIn(0, 40)
        skBatchInterval.progress = intervalStep
        tvBatchIntervalValue.text = "${cfg.batchIntervalMs}ms"
        skBatchInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val ms = p.toLong() * 100L
                tvBatchIntervalValue.text = "${ms}ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        cbAutoScroll.isChecked = cfg.autoScroll
        cbRedactExport.isChecked = cfg.redactExport
        cbConfirmRiskyReplay.isChecked = cfg.confirmRiskyReplay
        cbNotifyActive.isChecked = cfg.notifyActive

        val btnSave = findViewById<android.widget.Button>(R.id.btnSaveCaptureSettings)
        btnSave.setOnClickListener {
            val allBytes = previewLevels[skAllPreview.progress.coerceIn(0, previewLevels.lastIndex)]
            val byAppBytes = previewLevels[skByAppPreview.progress.coerceIn(0, previewLevels.lastIndex)]
            val intervalMs = skBatchInterval.progress.toLong() * 100L
            CaptureRepository.saveCaptureSettings(
                context = this,
                bodyPreviewBytesAll = allBytes,
                bodyPreviewBytesByApp = byAppBytes,
                batchIntervalMs = intervalMs,
                autoScroll = cbAutoScroll.isChecked,
                redactExport = cbRedactExport.isChecked,
                confirmRiskyReplay = cbConfirmRiskyReplay.isChecked,
                notifyActive = cbNotifyActive.isChecked,
                maxStoreEntries = etMaxStoreEntries.text.toString().toIntOrNull() ?: 5000,
                maxAgeDays = etMaxAgeDays.text.toString().toIntOrNull() ?: 7
            )
            // 应用 retention: 保存按钮一按立即触发历史清理(异步单线程)
            lifecycleScope.launch(Dispatchers.Default) {
                runCatching { CaptureController.applyStoreRetention(this@CaptureSettingsActivity) }
            }
            // 若抓包 active 中, 即时刷新 body preview 上限 — 已写入 ring 的 entry 不影响, 但新来 entry 按 new 上限截断
            val active = CaptureController.current.value
            CaptureController.updateBodyPreviewCaps(allBytes, byAppBytes)
            // 复用通知栏开关
            if (!cbNotifyActive.isChecked) {
                runCatching { com.HanFeng.service.CaptureFloatingService.stop(this) }
            } else if (active.active) {
                runCatching {
                    val ctx = this
                    com.HanFeng.service.CaptureFloatingService.startIfCaptureActive(ctx)
                }
            }
            finish()
        }

        // 批次 E6: 改写规则库入口
        findViewById<android.widget.Button>(R.id.btnRewriteRules).setOnClickListener {
            startActivity(Intent(this, RewriteRulesActivity::class.java))
        }
    }

    private fun prettyBytes(n: Int): String {
        if (n >= 1024 * 1024) return "${n / (1024 * 1024)}MB"
        if (n >= 1024) return "${n / 1024}KB"
        return "${n}B"
    }
}
