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
    fun `ordinary utility app is not treated as aggressive ad context`() {
        assertFalse(RuleRepository.isAggressiveAdAppHint("com.example.notes"))
    }
}
