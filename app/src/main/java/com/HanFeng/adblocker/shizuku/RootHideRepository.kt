package com.HanFeng.adblocker.shizuku

import android.content.Context
import com.HanFeng.data.WhitelistRepository

object RootHideRepository {
    private const val PREFS = "root_hide_repo"
    private const val KEY_SCOPE_PACKAGES = "root_hide_scope"
    private const val KEY_MODULE_KEYS = "root_hide_module_keys"
    private const val KEY_PROP_DISGUISE_ENABLED = "prop_disguise_enabled"
    private const val KEY_AUTO_WATCHER_ENABLED = "auto_watcher_enabled"

    fun getScopePackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SCOPE_PACKAGES, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun toggleScope(context: Context, packageName: String, enabled: Boolean) {
        val set = getScopePackages(context).toMutableSet()
        if (enabled) set += packageName else set -= packageName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SCOPE_PACKAGES, set)
            .apply()
    }

    fun replaceScopePackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SCOPE_PACKAGES, packages.toSet())
            .apply()
    }

    fun getHiddenModuleKeys(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_MODULE_KEYS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun setHiddenModuleKeys(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MODULE_KEYS, keys)
            .apply()
    }

    fun isPropDisguiseEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROP_DISGUISE_ENABLED, false)
    }

    fun setPropDisguiseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROP_DISGUISE_ENABLED, enabled)
            .apply()
    }

    fun isAutoWatcherEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_WATCHER_ENABLED, false)
    }

    fun setAutoWatcherEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_WATCHER_ENABLED, enabled)
            .apply()
    }

    fun loadInstalledAppsWithHideState(context: Context): List<com.HanFeng.model.InstalledApp> {
        val apps = WhitelistRepository.loadInstalledApps(context, prioritizeCoexist = false)
        val scopePackages = getScopePackages(context)
        return apps.map { it.copy(rootHideSelected = it.packageName in scopePackages) }
    }

    /**
     * 预设作用域：包含常见银行/支付、游戏、社交、短视频 App 的包名子串，
     * 调用方传入设备上已安装 App 的 packageName 集合，
     * 用 [filterInstalled] 拿到属于预设类别的 packageNames。
     */
    object Presets {
        // 银行/支付完整包名
        val BANK_PAY = setOf(
            "com.icbc.mobilebank", "com.icbc", "com.chinamworld.main",
            "com.ccb.ccbnetpay", "com.ccb", "com.ccb.life",
            "com.boc.bocmb", "com.boc.bocpas", "com.boc.bocdigi",
            "com.chinamworld.bocmbci",
            "com.abc.mobilebank", "com.android.bankabc", "com.chinamworld.creditcard",
            "com.bankcomm.multihelper", "com.bankcomm.Bankcomm",
            "com.cmbchina.CMB", "com.cmbchina.cmb", "com.cmbchina.ccp",
            "com.ceb.mobilebank", "com.cebbank.lakala",
            "com.spdb.mobilebank", "com.spdb.spdbank",
            "com.cib.cibmb", "com.cmbc.cibmb", "com.cmbc.mobile",
            "com.citicbank.mobilebank", "com.citicbank.creditcard", "com.chinamworld.citic",
            "com.pingan.bank", "com.pingan.paces.ccms",
            "com.hkbea", "com.hxbank.mbank", "com.psbc", "com.bankofshanghai",
            "com.eg.android.AlipayGphone", "com.tencent.mm",
            "com.unionpay.mobilepay", "com.unionpay.tsm", "com.unionpay.tsmservice",
            "com.lakala.mobile", "com.lakala.dlian",
            "com.jingdong.finance", "com.jdztapp.main",
            "com.ryt.hbf", "com.yitong.founder"
        )

        // 证券/基金
        val SECURITIES_PREFIXES = setOf(
            "com.eastmoney.android.", "com.eastmoney.fund",
            "com.hundsun.", "com.hszq.phone",
            "com.pingansec.", "com.pingan.lifeinsurance",
            "com.antfortune.android", "com.antgroup.antforest",
            "com.tencent.wealth", "com.lexin.finance",
            "com.mycreditcard", "com.tiantianjijin",
            "com.taobao.finance", "com.alipay.togetheraho"
        )

        // 保险
        val INSURANCE_PREFIXES = setOf(
            "com.chinainsurance.mobile", "com.pingan.lifeinsurance",
            "com.pingan.jzgj", "com.sinosig.mobile",
            "com.taikanglife.mobile", "com.taikang.app",
            "com.picc.life", "com.chinalife.mobile",
            "com.cpic.app", "com.cpic.life"
        )

        // 常见包名前缀 / 子串匹配
        val BANK_PAY_PREFIXES = setOf(
            "com.icbc", "com.ccb", "com.boc", "com.abc", "com.bcm", "com.cmbc", "com.cmbchina",
            "com.spdb", "com.cib", "com.citicbank", "com.pingan.bank", "com.ceb.mobilebank",
            "com.bankcomm", "com.unionpay", "com.eg.android.AlipayGphone", "com.lakala",
            "com.hkbea", "com.hxb", "com.psbc", "com.bankof"
        )

        // 游戏（按发行商/常见游戏包名前缀）
        val GAME_PREFIXES = setOf(
            "com.tencent.tmgp", "com.tencent.game", "com.tencent.mobileqq.game",
            "com.tencent.qqgame", "com.tencent.qqgamesdk", "com.tencent.qqgame.hall",
            "com.netease.game", "com.netease.minecraft", "com.neteaseGames",
            "com.miHoYo.", "com.mihoyo.", "com.bilibili.game", "com.tencent.hofo",
            "com.tencent.Kiwi", "com.tencent.tmgp.pubgmhd", "com.tencent.tmgp.lot", "com.tencent.tmgp.cfm",
            "com.tencent.tmgp.sgame", "com.tencent.tmgp.cf", "com.tencent.tmgp.kof", "com.tencent.tmgp.yj",
            "com.tencent.tmgp.bns", "com.tencent.tmgp.bxd", "com.tencent.tmgp.hawk", "com.tencent.tmgp.cqjy",
            "com.tencent.tmgp.ssk", "com.tencent.tmgp.mxd", "com.tencent.tmgp.oa",
            "com.netease.mj", "com.netease.g34", "com.netease.szgcck", "com.netease.zjz",
            "com.netease.biz.MiniGameSDK", "com.netease.daergz", "com.netease.h55",
            "com.netease.g78", "com.netease.daozh", "com.netease.mrzh", "com.netease.wyc",
            "com.netease.xyq", "com.netease.xy", "com.netease.ldoversea", "com.netease.game.",
            "com.dts.freefireth", "com.dts.sup", "com.garena.game",
            "com.activision.callofduty.shooter",
            "com.ea.game.pvzfree_row", "com.ea.games.pvz2_na", "com.ea.game.pvzfree_ch",
            "com.popcap.pvz2", "com.rovio.angrybirds", "com.supercell.",
            "com.mojang.minecraftpe", "com.king.com",
            "com.unity3d.Player", "com.eg.games", "com.lilithgame.",
            "com.gameabc.", "com.hero.", "com.funplus.", "com.habby.", "com.longtugame.",
            "com.ledu.", "com.youxigame.", "com.gaming.", "com.ngames."
        )

        // 社交
        val SOCIAL_PREFIXES = setOf(
            "com.tencent.mobileqq", "com.tencent.wework", "com.tencent.wefriend",
            "com.tencent.mm", "com.tencent.sinablog", "com.sina.weibo", "com.sina.sinablog",
            "com.sina.oasis", "com.netease.mail",
            "com.netease.mobimail", "com.netease.caesar", "com.netease.zx",
            "com.netease.cloudmusic", "com.netease.yanxuan",
            "cn.com.iresearch", "com.xiaomi.xmsf",
            "com.taobao.taobao", "com.taobao.cart", "com.taobao.live",
            "com.taobao.idlefish", "com.alibaba.aliyun", "com.alibaba.aliwgt",
            "com.alibaba.wireless", "com.alibaba.android.pistol",
            "com.alibaba.aliexpress", "com.alibaba.aliexpress.mobile",
            "com.alibaba.android.rpc", "jp.naver.line.android", "com.nhn.android.search",
            "com.naver.linewebtoon", "com.naver.tmap", "com.naver.ttf", "com.naver.kbuzz",
            "com.twitter.android", "com.facebook.katana", "com.facebook.orca",
            "com.facebook.lite", "com.instagram.android", "com.snapchat.android",
            "com.whatsapp", "com.tumblr", "com.discord", "com.skype.raider",
            "com.linkedin.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.aweme",
            "com.ss.android.article.news", "com.ss.android.article.video", "com.ss.android.lark",
            "com.ss.android.ugc.aweme.mobilecommerce", "com.ss.android.bytedancemall",
            "com.smile.gifmaker", "com.kuaishou.nebula", "com.kwai.video",
            "com.kuaixia", "com.yxcorp.gx", "com.yxcorp.plugin.dev", "com.smile.kxcommunity",
            "com.xunmeng.pinduoduo", "com.xunmeng.sebspider",
            "com.xiaomi.smarthome", "com.xiaomi.shop", "com.xiaomi.vip"
        )

        // 短视频
        val SHORT_VIDEO_PREFIXES = setOf(
            "com.ss.android.ugc.aweme", "com.smile.gifmaker", "com.kuaishou.nebula",
            "com.kwai.video", "com.smile.kxcommunity", "com.smile.kxcommunity.watermark",
            "com.estrongs.android.pop", "com.bilibili.app", "com.bilibili.app.blue",
            "com.bilibili.live", "com.bilibili.comic", "com.tencent.qqlive",
            "com.tencent.qqlivei18n", "com.tencent.qqlive.hijkl", "com.tencent.qqlive.hijkl.uiw",
            "com.qiyi.video", "com.qiyi.video.i", "com.qiyi.apk", "com.qiyi.bangzhu",
            "com.sohu.sohuvideo", "com.sohu.live", "com.letv.android.client", "com.letv.letvapp",
            "com.youku.phone", "com.youku.ui", "com.youku.uiabs", "com.youku.phone.x",
            "com.mgtv.tv", "com.hunantv.imgo.activity", "com.hunantv.imgo.cs.activity",
            "com.pptv.tvsports", "com.pptv.iphone", "com.snmany.richvideo",
            "com.xunmeng.pinduoduo", "com.taobao.live", "com.taobao.taobao.live",
            "com.smile.shortvideo", "com.bjsdk.shortvideo.export", "com.zhiliaoapp.musically"
        )

        /**
         * 根据已安装 packageNames，筛选属于指定类别（按前缀匹配+完整包名匹配）的全部包名。
         */
        fun matchesCategory(packageName: String): String? {
            for (p in BANK_PAY_PREFIXES) if (packageName == p || packageName.startsWith(p)) return "Bank/Pay"
            for (p in SECURITIES_PREFIXES) if (packageName == p || packageName.startsWith(p)) return "Securities"
            for (p in INSURANCE_PREFIXES) if (packageName == p || packageName.startsWith(p)) return "Insurance"
            for (p in SOCIAL_PREFIXES) if (packageName == p || packageName.startsWith(p)) return "Social"
            for (p in GAME_PREFIXES) if (packageName == p || packageName.startsWith(p)) return "Game"
            for (p in SHORT_VIDEO_PREFIXES) if (packageName == p || packageName.startsWith(p)) return "ShortVideo"
            // 兜底关键字匹配
            val lowerPkg = packageName.lowercase()
            if (lowerPkg.contains("bank") || lowerPkg.contains("pay") || lowerPkg.contains("finance") ||
                lowerPkg.contains("wallet") || lowerPkg.contains("fund") || lowerPkg.contains("stock") ||
                lowerPkg.contains("insurance") || lowerPkg.contains("jijin")) return "Bank/Pay"
            return null
        }

        /**
         * 一键预设：把所有匹配 Bank/Pay + Social + Game + ShortVideo 的已安装 App 加入作用域。
         * @param installedPackageNames 设备上已安装的 package names
         * @return 加入预设范畴的包名集合
         */
        fun filterInteresting(installedPackageNames: Collection<String>): Set<String> {
            return installedPackageNames.filterTo(mutableSetOf()) { pkg ->
                matchesCategory(pkg) != null ||
                    pkg.startsWith("com.tencent.") ||
                    pkg.startsWith("com.netease.") ||
                    pkg.startsWith("com.alibaba.") ||
                    pkg.startsWith("com.sina.") ||
                    pkg.startsWith("com.ss.android.") ||
                    pkg.startsWith("com.smile.") ||
                    pkg.startsWith("com.bilibili.") ||
                    pkg.startsWith("com.kuaishou.") ||
                    pkg.startsWith("com.kwai.") ||
                    pkg.startsWith("com.taobao.") ||
                    pkg.startsWith("com.xunmeng.") ||
                    pkg.startsWith("com.miHoYo.") ||
                    pkg.startsWith("com.mihoyo.") ||
                    pkg.startsWith("com.supercell.") ||
                    pkg.startsWith("com.mojang.") ||
                    pkg.startsWith("com.ea.game.") ||
                    pkg.startsWith("com.king.com.") ||
                    pkg.startsWith("com.habby.") ||
                    pkg.startsWith("com.lilithgame.") ||
                    pkg.startsWith("com.longtugame.")
            }
        }
    }
}
