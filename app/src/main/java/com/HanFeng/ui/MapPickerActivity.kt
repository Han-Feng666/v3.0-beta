package com.HanFeng.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * 全屏地图选点 Activity。
 *
 * 之前用 AlertDialog 承载 WebView 存在两个顽疾:
 * 1) dialog.show() 之前 loadUrl, WebView clientWidth=0, 瓦片渲染范围为空 → 地图空白仅剩网格+准星;
 * 2) Dialog 内 WebView <input> 点击偶发不弹软键盘 (系统 IME 对 Dialog window 的 focus 处理不一致)。
 *
 * 改为独立 Activity + adjustResize 后:
 * - WebView 全屏, clientHeight 正确, 瓦片正常渲染;
 * - windowSoftInputMode=adjustResize 保证点击搜索框必弹软键盘;
 * - 通过 setResult + finish 回传坐标, 调用方用 registerForActivityResult 接收。
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        webView = WebView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(false)
                builtInZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = (userAgentString ?: "") + " HanFengLocationPicker/1.0"
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPick(lat: Double, lng: Double) {
                    runOnUiThread {
                        val data = Intent().apply {
                            putExtra(EXTRA_LAT, lat)
                            putExtra(EXTRA_LNG, lng)
                        }
                        setResult(Activity.RESULT_OK, data)
                        finish()
                    }
                }
            }, "AndroidPicker")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/location_picker.html")
        // 首帧后主动给 WebView 焦点, 配合 manifest 的 adjustResize, 点击搜索框即弹软键盘
        webView.post { webView.requestFocus() }
    }

    override fun onDestroy() {
        runCatching {
            webView.removeJavascriptInterface("AndroidPicker")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"

        fun createIntent(context: Context): Intent =
            Intent(context, MapPickerActivity::class.java)
    }
}
