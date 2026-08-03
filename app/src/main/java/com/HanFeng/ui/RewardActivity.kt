package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.HanFeng.R

/**
 * 显示开发者赞赏码的入口页，整体沉浸式黑色背景点按返回键关闭。
 *
 * 赞赏码与首页/统计页背景图统一放在 app/src/main/assets/custom/ 目录，
 * 支持的文件名后缀：reward_qr.png / reward_qr.jpg / reward_qr.jpeg / reward_qr.webp。
 * 未放入任一文件时，ImageView 显示占位背景色 + 文字提示。
 */
class RewardActivity : BaseActivity() {

    private lateinit var imgReward: ImageView
    private lateinit var txtRewardHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reward)
        runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        imgReward = findViewById(R.id.imgReward)
        txtRewardHint = findViewById(R.id.txtRewardHint)
        loadRewardQr()
    }

    private fun loadRewardQr() {
        val drawable: Drawable? = loadCustomAssetDrawable(this, "custom/reward_qr")
        if (drawable != null) {
            imgReward.setImageDrawable(drawable)
            imgReward.visibility = View.VISIBLE
            txtRewardHint.visibility = View.GONE
        } else {
            imgReward.setImageDrawable(null)
            txtRewardHint.visibility = View.VISIBLE
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, RewardActivity::class.java)
        }
    }
}
