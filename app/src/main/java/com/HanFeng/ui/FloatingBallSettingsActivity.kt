package com.HanFeng.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.TextViewCompat
import com.HanFeng.R
import com.HanFeng.service.FloatingBallService

/**
 * 悬浮球设置页
 * - 总开关：开启/关闭悬浮球服务（需悬浮窗权限）
 * - 形状：圆形 / 横向胶囊
 * - 显示内容：拦截数量 / 内存-CPU 占用（二选一）
 */
class FloatingBallSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchCompat
    private lateinit var switchProcessMonitor: SwitchCompat
    private lateinit var rgShape: RadioGroup
    private lateinit var rgDataType: RadioGroup
    private lateinit var previewContainer: FrameLayout
    private lateinit var tvOverlayHint: TextView
    private lateinit var seekScale: SeekBar
    private lateinit var tvScaleLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating_ball_settings)

        findViewById<android.widget.Button>(R.id.btnBack).setOnClickListener { finish() }

        switchEnable = findViewById(R.id.switchEnable)
        switchProcessMonitor = findViewById(R.id.switchProcessMonitor)
        rgShape = findViewById(R.id.rgShape)
        rgDataType = findViewById(R.id.rgDataType)
        previewContainer = findViewById(R.id.previewContainer)
        tvOverlayHint = findViewById(R.id.tvOverlayPermissionHint)
        seekScale = findViewById(R.id.seekScale)
        tvScaleLabel = findViewById(R.id.tvScaleLabel)

        syncUiFromPrefs()

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            FloatingBallService.setEnabled(this, isChecked)
            handleEnableToggle(isChecked)
        }

        switchProcessMonitor.setOnCheckedChangeListener { _, isChecked ->
            FloatingBallService.setProcessMonitorEnabled(this, isChecked)
            restartBallIfRunning()
        }

        rgShape.setOnCheckedChangeListener { _, id ->
            val shape = when (id) {
                R.id.rbShapeCapsule -> FloatingBallService.SHAPE_CAPSULE
                else -> FloatingBallService.SHAPE_CIRCLE
            }
            FloatingBallService.setShape(this, shape)
            refreshPreview()
            restartBallIfRunning()
        }

        rgDataType.setOnCheckedChangeListener { _, id ->
            val type = when (id) {
                R.id.rbDataMemoryCpu -> FloatingBallService.DATA_MEMORY_CPU
                else -> FloatingBallService.DATA_BLOCK_COUNT
            }
            FloatingBallService.setDataType(this, type)
            refreshPreview()
            restartBallIfRunning()
        }

        seekScale.max = FloatingBallService.SCALE_LEVEL_MAX
        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                FloatingBallService.setScaleLevel(this@FloatingBallSettingsActivity, progress)
                updateScaleLabel()
                refreshPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                restartBallIfRunning()
            }
        })

        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能去系统设置补权限回来，重新同步状态
        syncUiFromPrefs()
        refreshPreview()
    }

    private fun syncUiFromPrefs() {
        val enabled = FloatingBallService.isEnabled(this)
        val hasPermission = FloatingBallService.hasOverlayPermission(this)
        switchEnable.isChecked = enabled && hasPermission
        tvOverlayHint.text = if (!hasPermission) {
            "需要悬浮窗权限，点击开关会自动跳转去授权。"
        } else {
            "开启后，屏幕上会显示一个小球，仅显示单项信息。点击可回到主界面，可拖动位置。"
        }

        when (FloatingBallService.getShape(this)) {
            FloatingBallService.SHAPE_CAPSULE -> rgShape.check(R.id.rbShapeCapsule)
            else -> rgShape.check(R.id.rbShapeCircle)
        }
        when (FloatingBallService.getDataType(this)) {
            FloatingBallService.DATA_MEMORY_CPU -> rgDataType.check(R.id.rbDataMemoryCpu)
            else -> rgDataType.check(R.id.rbDataBlockCount)
        }
        seekScale.progress = FloatingBallService.getScaleLevel(this)
        updateScaleLabel()

        // 同步进程监控开关状态
        switchProcessMonitor.isChecked = FloatingBallService.isProcessMonitorEnabled(this)
    }

    private fun updateScaleLabel() {
        tvScaleLabel.text = FloatingBallService.getScaleFactorLabel(this)
    }

    private fun handleEnableToggle(isChecked: Boolean) {
        if (!isChecked) {
            FloatingBallService.stop(this)
            return
        }
        if (!FloatingBallService.hasOverlayPermission(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            switchEnable.isChecked = false
            FloatingBallService.setEnabled(this, false)
            return
        }
        FloatingBallService.startIfEnabled(this)
    }

    private fun requestOverlayPermission() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
            }
        }
    }

    private fun refreshPreview() {
        previewContainer.removeAllViews()
        val shape = FloatingBallService.getShape(this)
        val dataType = FloatingBallService.getDataType(this)
        val layoutRes = if (shape == FloatingBallService.SHAPE_CAPSULE) {
            R.layout.floating_ball_capsule
        } else {
            R.layout.floating_ball_circle
        }
        val view = LayoutInflater.from(this).inflate(layoutRes, previewContainer, false)
        val (label, value) = previewContent(dataType)
        val factor = FloatingBallService.getScaleFactor(this)

        if (shape == FloatingBallService.SHAPE_CAPSULE) {
            val labelView = view.findViewById<TextView?>(R.id.tvBallLabelCapsule)
            val valueView = view.findViewById<TextView?>(R.id.tvBallValueCapsule)
            labelView?.text = label
            valueView?.text = value
            applyScaleToPreview(view, R.id.ballRootCapsule, factor, labelView, valueView)
        } else {
            val labelView = view.findViewById<TextView?>(R.id.tvBallTitleCircle)
            val valueView = view.findViewById<TextView?>(R.id.tvBallValueCircle)
            labelView?.text = label
            valueView?.text = value
            applyScaleToPreview(view, R.id.ballRootCircle, factor, labelView, valueView)
        }
        previewContainer.addView(view)
    }

    private fun applyScaleToPreview(
        root: android.view.View,
        rootId: Int,
        factor: Float,
        vararg textViews: TextView?
    ) {
        if (factor == 1.0f) return
        val density = resources.displayMetrics.density
        val containerView = root.findViewById<android.view.ViewGroup>(rootId) ?: return
        val newW = (containerView.layoutParams.width * factor).toInt().coerceAtLeast((40 * density).toInt())
        val newH = (containerView.layoutParams.height * factor).toInt().coerceAtLeast((40 * density).toInt())
        containerView.layoutParams = containerView.layoutParams.apply {
            width = newW
            height = newH
        }
        val padOrig = containerView.paddingLeft
        val padNew = (padOrig * factor).toInt()
        containerView.setPaddingRelative(padNew, padNew, padNew, padNew)
        textViews.forEach { tv ->
            tv?.let {
                val orig = it.textSize
                it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, orig * factor)
            }
        }
    }

    private fun previewContent(dataType: String): Pair<String, String> {
        return if (dataType == FloatingBallService.DATA_MEMORY_CPU) {
            "占用" to "355M/12%"
        } else {
            "拦截" to "1555"
        }
    }

    private fun restartBallIfRunning() {
        if (FloatingBallService.isEnabled(this) && FloatingBallService.hasOverlayPermission(this)) {
            FloatingBallService.stop(this)
            FloatingBallService.startIfEnabled(this)
        }
    }
}
