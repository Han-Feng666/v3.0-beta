package com.HanFeng.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.HanFeng.core.network.NetworkKernel
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.databinding.ActivityLocalProxyCoexistBinding

class LocalProxyCoexistActivity : BaseActivity() {
    private lateinit var binding: ActivityLocalProxyCoexistBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLocalProxyCoexistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.localProxyRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16.dp, systemBars.top + 16.dp, 16.dp, systemBars.bottom + 16.dp)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnDisable.setOnClickListener { disableLocalProxyCoexist() }
        binding.btnSave.setOnClickListener { saveLocalProxyCoexist() }
        binding.hostInput.doAfterTextChanged { updatePreview() }
        binding.portInput.doAfterTextChanged { updatePreview() }
        binding.controllerPackageInput.doAfterTextChanged { updatePreview() }
        binding.remarksInput.doAfterTextChanged { updatePreview() }
        bindCurrentConfig()
    }

    private fun bindCurrentConfig() {
        val current = WhitelistRepository.getLocalProxyCoexistConfig(this)
        binding.hostInput.setText(current.host)
        binding.portInput.setText(current.port?.toString().orEmpty())
        binding.controllerPackageInput.setText(current.controllerPackageName.orEmpty())
        binding.remarksInput.setText(current.remarks.orEmpty())
        binding.detectedText.text = buildString {
            val detectedApp = current.detectedAppLabel?.takeIf { it.isNotBlank() }
            val detectedPackage = current.detectedPackageName?.takeIf { it.isNotBlank() }
            val detectionSource = current.detectionSource?.takeIf { it.isNotBlank() }
            if (detectedApp != null || detectedPackage != null || detectionSource != null) {
                append("自动识别结果：")
                append(detectedApp ?: "未识别应用")
                detectedPackage?.let {
                    append("\n包名：")
                    append(it)
                }
                detectionSource?.let {
                    append("\n来源：")
                    append(it)
                }
            } else {
                append("当前未记录自动识别结果，直接手动填写即可。")
            }
        }
        updatePreview()
    }

    private fun updatePreview() {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty().ifBlank { "127.0.0.1" }
        val port = binding.portInput.text?.toString()?.trim().orEmpty()
        val controllerPackage = binding.controllerPackageInput.text?.toString()?.trim().orEmpty()
        val remarks = binding.remarksInput.text?.toString()?.trim().orEmpty()
        val coexistPackages = WhitelistRepository.getCoexistPackages(this)
        val targetPackages = WhitelistRepository.getLocalProxyTargetPackages(this)
        binding.previewText.text = buildString {
            append("当前配置预览\n")
            append("地址：")
            append(host)
            append('\n')
            append("端口：")
            append(if (port.isBlank()) "未填写" else port)
            append('\n')
            append("代理应用包名：")
            append(if (controllerPackage.isBlank()) "未填写" else controllerPackage)
            append('\n')
            append("备注：")
            append(if (remarks.isBlank()) "未填写" else remarks)
            append("\n\n共存诊断\n")
            append("代理本体：")
            append(if (controllerPackage.isBlank()) "未指定，保存后会尝试按已选加速器识别" else "将从寒枫 VPN 排除")
            append('\n')
            append("目标应用：")
            append(
                when {
                    targetPackages.isNotEmpty() -> "${targetPackages.size} 个目标会经寒枫转发到本地代理，仍可保留 DNS/MITM 拦截"
                    port.isNotBlank() -> "未单独选择目标应用，保存后只记录代理配置，不承接全 TCP 流量"
                    else -> "未配置端口，当前不会启用本地代理共存"
                }
            )
            append('\n')
            append("共存列表：")
            append(if (coexistPackages.isEmpty()) "暂无应用" else "${coexistPackages.size} 个应用已参与共存/跟随代理")
        }
    }

    private fun disableLocalProxyCoexist() {
        val current = WhitelistRepository.getLocalProxyCoexistConfig(this)
        WhitelistRepository.saveLocalProxyCoexistConfig(this, current.copy(enabled = false))
        Toast.makeText(this, "配置信息已关闭", Toast.LENGTH_SHORT).show()
        reloadVpnIfNeeded()
        finish()
    }

    private fun saveLocalProxyCoexist() {
        val current = WhitelistRepository.getLocalProxyCoexistConfig(this)
        val host = binding.hostInput.text?.toString()?.trim().orEmpty().ifBlank { "127.0.0.1" }
        val port = binding.portInput.text?.toString()?.trim()?.toIntOrNull()
        val controllerPackageName = binding.controllerPackageInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        val remarks = binding.remarksInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "端口格式不正确", Toast.LENGTH_SHORT).show()
            return
        }
        WhitelistRepository.saveLocalProxyCoexistConfig(
            this,
            current.copy(
                enabled = true,
                host = host,
                port = port,
                controllerPackageName = controllerPackageName,
                remarks = remarks
            )
        )
        Toast.makeText(this, "配置信息已保存", Toast.LENGTH_SHORT).show()
        reloadVpnIfNeeded()
        finish()
    }

    private fun reloadVpnIfNeeded() {
        NetworkKernel.reloadIfRunning(this)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
