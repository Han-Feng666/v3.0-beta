package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts

// Legacy forwarding entry kept for old links and historical activity names.
class LegacySuspiciousDomainsActivity : BaseActivity() {
    private val forwardLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        setResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (savedInstanceState == null) {
            forwardLauncher.launch(SuspiciousDomainsActivity.createIntent(this))
        } else {
            finish()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, LegacySuspiciousDomainsActivity::class.java)
    }
}
