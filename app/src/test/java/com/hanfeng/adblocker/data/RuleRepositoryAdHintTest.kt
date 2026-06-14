package com.HanFeng.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRepositoryAdHintTest {
    @Test
    fun `short drama and comic apps are treated as aggressive ad contexts`() {
        assertTrue(RuleRepository.isAggressiveAdAppHint("红果免费短剧"))
        assertTrue(RuleRepository.isAggressiveAdAppHint("免费漫画大全"))
        assertTrue(RuleRepository.isAggressiveAdAppHint("免费小说大全"))
        assertTrue(RuleRepository.isAggressiveAdAppHint("com.example.duanju"))
        assertTrue(RuleRepository.isAggressiveAdAppHint("com.example.shortdrama"))
        assertTrue(RuleRepository.isAggressiveAdAppHint("com.example.minidrama.episode"))
        assertTrue(RuleRepository.isAggressiveAdAppHint("com.example.manga.reader"))
        assertTrue(RuleRepository.isAggressiveAdAppHint("com.example.xiaoshuo.mianfei"))
    }

    @Test
    fun `free novel app identifiers are treated as novel app contexts`() {
        assertTrue(RuleRepository.isNovelAppHint("免费小说大全"))
        assertTrue(RuleRepository.isNovelAppHint("短剧大全"))
        assertTrue(RuleRepository.isNovelAppHint("com.example.short_drama"))
        assertTrue(RuleRepository.isNovelAppHint("com.example.bookreader"))
        assertTrue(RuleRepository.isNovelAppHint("com.example.xiaoshuo.mianfei"))
    }

    @Test
    fun `novel protected domains exclude numbered ad subdomains`() {
        assertFalse(RuleRepository.isProtectedNovelAppDomain("ads5-normal-lq.zijieapi.com"))
        assertFalse(RuleRepository.isProtectedNovelAppDomain("ad3-normal-lq.fqnovel.com"))
        assertTrue(RuleRepository.isProtectedNovelAppDomain("api5-normal-lq.fqnovel.com"))
    }

    @Test
    fun `ordinary utility app is not treated as aggressive ad context`() {
        assertFalse(RuleRepository.isAggressiveAdAppHint("com.example.notes"))
    }

    @Test
    fun `general ad traffic detects known ad sdk infrastructure`() {
        assertTrue(
            RuleRepository.shouldTreatAsGeneralAdTraffic(
                domain = "pangolin.snssdk.com",
                vendor = "穿山甲/Pangle"
            )
        )
        assertTrue(
            RuleRepository.shouldTreatAsGeneralAdTraffic(
                domain = "gdt.qq.com",
                vendor = "优量汇/GDT"
            )
        )
    }

    @Test
    fun `general ad traffic detects generic mobile sdk ad infrastructure`() {
        assertTrue(RuleRepository.looksLikeAdSdkInfraDomain("adrequest.moloco.example.com"))
        assertTrue(RuleRepository.looksLikeAdSdkInfraDomain("creative-data.bidmachine.example.net"))
        assertTrue(RuleRepository.looksLikeAdSdkInfraDomain("adrouter.loopme.example.com"))
        assertTrue(RuleRepository.looksLikeAdSdkInfraDomain("skadnetwork.verve.example.net"))
        assertTrue(RuleRepository.looksLikeAdSdkInfraDomain("bidresponse.bidswitch.example.org"))
        assertTrue(
            RuleRepository.shouldTreatAsGeneralAdTraffic(
                domain = "sdkconfig.adjoe.example.org",
                vendor = "其它 (Other)"
            )
        )
    }

    @Test
    fun `general ad traffic detects advanced ad delivery channels`() {
        val domains = listOf(
            "adhttpdns.example.com",
            "adstream.example.net",
            "grpcad.example.org",
            "protobufad.example.com",
            "adplugin.cdn.example.net",
            "dynamicad.example.org",
            "adquic-gateway.example.com",
            "marketad.example.net",
            "shakead.example.org",
            "notifyad.example.com",
            "taskad.example.com",
            "offerwallad.example.net",
            "gamead.example.org",
            "liveroomad.example.com",
            "searchad.example.net",
            "hotwordad.example.org",
            "experimentad.example.com",
            "miniappad.example.net",
            "fakebuttonad.example.com",
            "clipboardad.example.net",
            "sharead.example.org",
            "serviceworkerad.example.com",
            "widgetad.example.net",
            "lockscreenad.example.org",
            "commentad.example.com",
            "replyad.example.net",
            "profilead.example.org",
            "followad.example.com",
            "inboxad.example.net",
            "templatead.example.org",
            "cloudcontrolad.example.com",
            "assetpackad.example.net",
            "resourcepackad.example.org",
            "hotpatchad.example.com",
            "couponad.example.net",
            "redpacketad.example.org",
            "productad.example.com",
            "affiliatead.example.net",
            "commissionad.example.org",
            "locallifead.example.com",
            "nearbyad.example.net",
            "weatherad.example.org",
            "toolad.example.com",
            "leadgenad.example.net",
            "surveyad.example.org",
            "calendarreminderad.example.com",
            "calendarsubscribead.example.net",
            "browserad.example.com",
            "startpagead.example.net",
            "newtabad.example.org",
            "hotsearchad.example.com",
            "appstoread.example.net",
            "appinstallad.example.org",
            "promotedappad.example.com",
            "oemad.example.net",
            "systemmanagerad.example.org",
            "virusscanad.example.com",
            "tvad.example.net",
            "ottad.example.org",
            "wearad.example.com",
            "carad.example.net",
            "cnamead.example.org",
            "adalias.example.com",
            "cnamecloakad.example.net",
            "dohad.example.org",
            "dnsqueryad.example.com",
            "encrypteddnsad.example.net",
            "httpdnsad.example.org",
            "adquic443.example.com",
            "quicad443.example.net",
            "wasmad.example.org",
            "wasmloaderad.example.com",
            "jsloaderad.example.net",
            "obfuscatedad.example.org",
            "phashad.example.com",
            "imagehashad.example.net",
            "watermarkad.example.org",
            "videofingerprintad.example.com",
            "http3ad.example.net",
            "udp443ad.example.org",
            "quicgatewayad.example.com",
            "http3gatewayad.example.net"
        )

        domains.forEach { domain ->
            assertTrue(
                domain,
                RuleRepository.shouldTreatAsGeneralAdTraffic(
                    domain = domain,
                    vendor = "其它 (Other)",
                    appName = "免费小说大全"
                )
            )
        }
    }

    @Test
    fun `general ad traffic detects open screen and launch ad domains`() {
        assertTrue(
            RuleRepository.shouldTreatAsGeneralAdTraffic(
                domain = "open-screen-ad.example.com",
                vendor = "其它 (Other)",
                appName = "免费小说大全"
            )
        )
        assertTrue(
            RuleRepository.shouldTreatAsGeneralAdTraffic(
                domain = "launchad.example.net",
                vendor = "其它 (Other)",
                appName = "com.example.shortdrama"
            )
        )
    }

    @Test
    fun `general ad traffic keeps normal core domains protected`() {
        assertFalse(
            RuleRepository.shouldTreatAsGeneralAdTraffic(
                domain = "api.weixin.qq.com",
                vendor = "优量汇/GDT"
            )
        )
        assertFalse(
            RuleRepository.shouldTreatAsGeneralAdTraffic(
                domain = "api.example.com",
                vendor = "其它 (Other)"
            )
        )
    }

    @Test
    fun `known httpdns providers are treated as bypass protection domains`() {
        assertTrue(RuleRepository.isBypassProtectionDomain("httpdns.aliyun.com"))
        assertTrue(RuleRepository.isBypassProtectionDomain("dns.weixin.qq.com"))
        assertTrue(RuleRepository.isBypassProtectionDomain("httpdns.qq.com"))
    }
}
