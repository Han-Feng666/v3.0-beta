package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import com.HanFeng.R

/**
 * 显示开发者赞赏码的入口页，整体沉浸式黑色背景点按返回键关闭。
 * 赞赏码图片资源位于 res/drawable/reward_qr.png，未替换时显示 ic_launcher_foreground_shape 作占位符。
 */
class RewardActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reward)
        runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, RewardActivity::class.java)
        }
    }
}
