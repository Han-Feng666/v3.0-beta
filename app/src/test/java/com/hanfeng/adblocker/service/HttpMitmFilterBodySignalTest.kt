package com.HanFeng.service

import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMitmFilterBodySignalTest {
    @Test
    fun `reader card ad fields produce reader ad reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "reader_bottom_card": {"ad_position":"bottom", "ad_scene":"reader"},
              "material_url":"https://example.com/ad.jpg",
              "landing_url":"https://example.com/landing"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.any { it.contains("reader") || it.contains("novel-field") })
    }

    @Test
    fun `coolapk comment ad fields produce comment ad reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "comment_ad_card": {"ad_material":"x", "material_url":"https://example.com/ad.png"},
              "comment": {"id":"1"},
              "click_url":"https://example.com/click",
              "track_url":"https://example.com/track"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.any { it.startsWith("comment-") || it.contains("ad-field") })
    }

    private fun inspectBodySignals(body: String): BodySignalView {
        val method = HttpMitmFilter::class.java.getDeclaredMethod("inspectAdBodySignals", String::class.java)
        method.isAccessible = true
        val result = method.invoke(HttpMitmFilter, body.lowercase())
        val scoreField = result.javaClass.getDeclaredField("score")
        val reasonsField = result.javaClass.getDeclaredField("reasons")
        scoreField.isAccessible = true
        reasonsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return BodySignalView(
            score = scoreField.getInt(result),
            reasons = reasonsField.get(result) as List<String>
        )
    }

    private data class BodySignalView(
        val score: Int,
        val reasons: List<String>
    )
}
