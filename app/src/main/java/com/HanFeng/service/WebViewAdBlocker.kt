package com.HanFeng.service

import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/**
 * P1.6 WebView 广告拦截增强
 * 
 * 功能：
 * 1. 反广告拦截检测绕过
 * 2. 广告元素自动隐藏
 * 3. 广告请求拦截
 * 4. 弹窗广告阻止
 */
object WebViewAdBlocker {
    private const val TAG = "WebViewAdBlocker"
    
    // 已注入的 WebView（弱引用避免内存泄漏）
    private val injectedWebViews = ConcurrentHashMap<Int, Boolean>()
    
    // 反广告拦截检测脚本
    private const val ANTI_ADBLOCK_BYPASS = """
(function() {
    // 隐藏广告拦截检测弹窗
    const originalConfirm = window.confirm;
    const originalAlert = window.alert;
    
    window.confirm = function(msg) {
        if (msg && (msg.toLowerCase().includes('adblock') || msg.toLowerCase().includes('广告拦截'))) {
            return false;
        }
        return originalConfirm.call(this, msg);
    };
    
    window.alert = function(msg) {
        if (msg && (msg.toLowerCase().includes('adblock') || msg.toLowerCase().includes('广告拦截'))) {
            return;
        }
        return originalAlert.call(this, msg);
    };
    
    // 屏蔽广告检测函数
    const blockedDetectors = [
        'checkAdBlock', 'detectAdblock', 'isAdblock', 'adblockDetected',
        'showAdBlockMessage', 'onAdBlockDetected'
    ];
    
    blockedDetectors.forEach(funcName => {
        if (typeof window[funcName] === 'function') {
            window[funcName] = function() { return false; };
        }
    });
    
    // 屏蔽常见广告检测库
    if (typeof window.BlockAdblockTech !== 'undefined') {
        window.BlockAdblockTech = function() { return false; };
    }
})();
"""

    // 广告元素自动隐藏脚本
    private const val AUTO_HIDE_ADS = """
(function() {
    const adSelectors = [
        // 常见广告类名
        '.ad', '.ads', '.advert', '.advertisement',
        '.ad-banner', '.ad-banner-container',
        '.ad-container', '.ad-content', '.ad-wrapper',
        '.ad-sidebar', '.ad-footer', '.ad-header',
        '.banner-ad', '.text-ad', '.image-ad',
        '.google-ad', '.adsbygoogle',
        '[class*="ad-container"]', '[class*="ad-banner"]',
        '[class*="advert"]', '[class*="sponsor"]',
        
        // 常见广告 ID
        '#ad', '#ads', '#advert', '#advertisement',
        '#ad-banner', '#ad-container', '#ad-wrapper',
        '#google-ad', '[id*="ad-container"]',
        
        // 浮层/弹窗广告
        '.popup-ad', '.popunder', '.overlay-ad',
        '[class*="popup"]', '[class*="overlay"]',
        
        // 信息流广告
        '.feed-ad', '.feed-advert', '.native-ad',
        '[class*="feed-ad"]', '[class*="native-ad"]',
        
        // 视频贴片广告
        '.video-ad', '.pre-roll', '.mid-roll', '.post-roll',
        '[class*="video-ad"]', '[class*="pre-roll"]'
    ];
    
    function hideAds() {
        adSelectors.forEach(selector => {
            try {
                const elements = document.querySelectorAll(selector);
                elements.forEach(el => {
                    el.style.display = 'none';
                    el.style.visibility = 'hidden';
                    el.style.opacity = '0';
                    el.style.pointerEvents = 'none';
                });
            } catch (e) {
                // Ignore invalid selectors
            }
        });
    }
    
    // 立即执行一次
    hideAds();
    
    // 使用 MutationObserver 监听 DOM 变化，动态移除广告
    const observer = new MutationObserver((mutations) => {
        let shouldHide = false;
        mutations.forEach(mutation => {
            if (mutation.addedNodes.length > 0) {
                shouldHide = true;
            }
        });
        
        if (shouldHide) {
            hideAds();
        }
    });
    
    observer.observe(document.body, {
        childList: true,
        subtree: true
    });
})();
"""

    // 广告请求拦截脚本
    private const val AD_REQUEST_BLOCKER = """
(function() {
    // 保存原始 fetch 和 XMLHttpRequest
    const originalFetch = window.fetch;
    const originalXHROpen = XMLHttpRequest.prototype.open;
    const originalXHRSend = XMLHttpRequest.prototype.send;
    
    // 广告域名黑名单
    const adDomains = [
        'googleadservices.com', 'doubleclick.net', 'adservice.google.com',
        'googlesyndication.com', 'adsbygoogle.com', 'google-analytics.com',
        'facebook.com/ads', 'fbcdn.net/ads',
        'umeng.com', 'umengcloud.com', 'umtrack.com',
        'growthpush.com', 'pushwoosh.com',
        'bugly.qcloud.com', 'tencent.com/ads'
    ];
    
    // 拦截 fetch 请求
    window.fetch = function(url, options) {
        if (isAdUrl(url)) {
            console.log('WebViewAdBlocker: Blocked fetch request to', url);
            return Promise.resolve(new Response('', { status: 403 }));
        }
        return originalFetch.call(this, url, options);
    };
    
    // 拦截 XMLHttpRequest
    XMLHttpRequest.prototype.open = function(method, url, ...args) {
        this._url = url;
        return originalXHROpen.apply(this, [method, url, ...args]);
    };
    
    XMLHttpRequest.prototype.send = function(...args) {
        if (this._url && isAdUrl(this._url)) {
            console.log('WebViewAdBlocker: Blocked XHR request to', this._url);
            return;
        }
        return originalXHRSend.apply(this, args);
    };
    
    function isAdUrl(url) {
        if (!url) return false;
        const lowerUrl = url.toLowerCase();
        return adDomains.some(domain => lowerUrl.includes(domain));
    }
})();
"""

    // 合并所有注入脚本
    private const val ALL_INJECTION_SCRIPT = """
$ANTI_ADBLOCK_BYPASS
$AUTO_HIDE_ADS
$AD_REQUEST_BLOCKER
"""

    /**
     * 注入广告拦截脚本到 WebView
     */
    fun inject(
        webView: WebView,
        enableAntiDetect: Boolean = true,
        enableAutoHide: Boolean = true,
        enableRequestBlock: Boolean = true
    ) {
        if (!webView.isAttachedToWindow) return
        
        val webViewId = webView.hashCode()
        if (injectedWebViews[webViewId] == true) return
        
        // 构建注入脚本
        val scripts = buildString {
            if (enableAntiDetect) append(ANTI_ADBLOCK_BYPASS)
            if (enableAutoHide) append(AUTO_HIDE_ADS)
            if (enableRequestBlock) append(AD_REQUEST_BLOCKER)
        }
        
        if (scripts.isNotEmpty()) {
            webView.evaluateJavascript(scripts, null)
            injectedWebViews[webViewId] = true
            android.util.Log.d(TAG, "Injected ad blocking scripts to WebView $webViewId")
        }
    }

    /**
     * 移除 WebView 的注入脚本记录
     */
    fun remove(webView: WebView) {
        val webViewId = webView.hashCode()
        injectedWebViews.remove(webViewId)
    }

    /**
     * 清理所有注入记录
     */
    fun clear() {
        injectedWebViews.clear()
    }

    /**
     * 获取已注入的 WebView 数量
     */
    fun getInjectedCount(): Int = injectedWebViews.size
}
