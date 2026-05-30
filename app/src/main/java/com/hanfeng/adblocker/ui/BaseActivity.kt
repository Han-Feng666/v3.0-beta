package com.HanFeng.ui

import android.os.Bundle
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ProcessLifecycleOwner
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

    override fun onStop() {
        super.onStop()
        if (!AppSettingsRepository.isHideBackgroundEnabled(this) || isChangingConfigurations || isFinishing) return
        window.decorView.post {
            val appStillInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
            if (!appStillInForeground && !isFinishing && !isDestroyed) {
                finishAndRemoveTask()
            }
        }
    }
}
