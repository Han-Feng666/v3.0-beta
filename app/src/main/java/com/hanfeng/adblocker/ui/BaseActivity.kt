package com.HanFeng.ui

import android.os.Bundle
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.HanFeng.data.AppSettingsRepository

open class BaseActivity : AppCompatActivity() {

    protected fun applyHideBackgroundPolicy(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!enabled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHideBackgroundPolicy(AppSettingsRepository.isHideBackgroundEnabled(this))
    }

    override fun onResume() {
        super.onResume()
        applyHideBackgroundPolicy(AppSettingsRepository.isHideBackgroundEnabled(this))
    }

}
