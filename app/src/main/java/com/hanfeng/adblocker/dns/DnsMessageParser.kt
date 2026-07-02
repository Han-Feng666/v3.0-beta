package com.HanFeng.dns

import com.HanFeng.model.DnsQuestion
import java.io.ByteArrayOutputStream
import java.net.InetAddress

object DnsMessageParser {
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val TYPE_CNAME = 5
    private const val TYPE_SVCB = 64
    private const val TYPE_HTTPS = 65

    fun parseQuestion(payload: ByteArray): DnsQuestion? {
        if (payload.size < 12) return null
        val questionCount = readShort(payload, 4)
        if (questionCount < 1) return null
        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < payload.size) {
            val len = payload[offset].toInt() and 0xFF
            if (len == 0) {
                offset++
                break
            }
            if (len and 0xC0 != 0) return null
            if (offset + 1 + len > payload.size) return null
            labels += payload.copyOfRange(offset + 1, offset + 1 + len).toString(Charsets.UTF_8)
            offset += len + 1
        }
        if (offset + 4 > payload.size) return null
        val id = readShort(payload, 0)
        val qType = readShort(payload, offset)
        return DnsQuestion(id, labels.joinToString("."), qType, System.currentTimeMillis())
    }

    fun buildSinkholeResponse(queryPayload: ByteArray, question: DnsQuestion): ByteArray? {
        val out = ByteArrayOutputStream()
        out.writeHeader(queryPayload, rCode = 0, answerCount = if (question.qType == 1 || question.qType == 28) 1 else 0)

        val questionBytes = encodeQuestion(question.domain, question.qType)
        out.write(questionBytes)
        if (question.qType != 1 && question.qType != 28) {
            return out.toByteArray()
        }
        out.write(byteArrayOf(0xC0.toByte(), 0x0C))
        out.write(shortBytes(question.qType))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x3C))

        val data = if (question.qType == 28) ByteArray(16) else ByteArray(4)
        out.write(shortBytes(data.size))
        out.write(data)
        return out.toByteArray()
    }

    fun buildRewriteResponse(queryPayload: ByteArray, question: DnsQuestion, rewriteIp: String): ByteArray? {
        if (question.qType != 1 && question.qType != 28) {
            return buildSinkholeResponse(queryPayload, question)
        }
        val address = runCatching { InetAddress.getByName(rewriteIp) }.getOrNull() ?: return null
        val addrBytes = address.address
        if ((question.qType == 1 && addrBytes.size != 4) || (question.qType == 28 && addrBytes.size != 16)) {
            return null
        }
        val out = ByteArrayOutputStream()
        out.writeHeader(queryPayload, rCode = 0, answerCount = 1)
        val questionBytes = encodeQuestion(question.domain, question.qType)
        out.write(questionBytes)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C))
        out.write(shortBytes(question.qType))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x3C))
        out.write(shortBytes(addrBytes.size))
        out.write(addrBytes)
        return out.toByteArray()
    }

    fun buildServerFailureResponse(queryPayload: ByteArray, question: DnsQuestion): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeHeader(queryPayload, rCode = 2, answerCount = 0)
        out.write(encodeQuestion(question.domain, question.qType))
        return out.toByteArray()
    }

    fun buildCacheKey(question: DnsQuestion): String {
        val d = question.domain
        val normalized = if (d == d.lowercase()) d else d.lowercase()
        return "${normalized}|${question.qType}"
    }

    fun isCacheableResponse(response: ByteArray, question: DnsQuestion): Boolean {
        if (response.size < 12) return false
        val flags = readShort(response, 2)
        val isResponse = flags and 0x8000 != 0
        val truncated = flags and 0x0200 != 0
        val rCode = flags and 0x000F
        val answerCount = readShort(response, 6)
        if (!isResponse || truncated || rCode != 0 || answerCount <= 0) return false
        val parsedQuestion = parseQuestion(response) ?: return false
        return parsedQuestion.domain.equals(question.domain, ignoreCase = true) && parsedQuestion.qType == question.qType
    }

    fun isNegativeCacheableResponse(response: ByteArray, question: DnsQuestion): Boolean {
        if (response.size < 12) return false
        val flags = readShort(response, 2)
        val isResponse = flags and 0x8000 != 0
        val truncated = flags and 0x0200 != 0
        val rCode = flags and 0x000F
        val answerCount = readShort(response, 6)
        if (!isResponse || truncated || rCode != 3 || answerCount != 0) return false
        val parsedQuestion = parseQuestion(response) ?: return false
        return parsedQuestion.domain.equals(question.domain, ignoreCase = true) && parsedQuestion.qType == question.qType
    }

    fun normalizeResponseForCache(response: ByteArray): ByteArray {
        val normalized = response.copyOf()
        if (normalized.size >= 2) {
            normalized[0] = 0
            normalized[1] = 0
        }
        return normalized
    }

    fun restoreCachedResponseForQuery(cachedResponse: ByteArray, queryPayload: ByteArray): ByteArray {
        val restored = cachedResponse.copyOf()
        if (restored.size >= 2 && queryPayload.size >= 2) {
            restored[0] = queryPayload[0]
            restored[1] = queryPayload[1]
        }
        return restored
    }

    fun extractCacheTtlMillis(response: ByteArray, fallbackSeconds: Long = 20L): Long {
        val fallbackMillis = fallbackSeconds * 1000L
        if (response.size < 12) return fallbackMillis
        val questionCount = readShort(response, 4)
        val answerCount = readShort(response, 6)
        if (questionCount <= 0 || answerCount <= 0) return fallbackMillis
        var offset = 12
        repeat(questionCount) {
            offset = skipName(response, offset) ?: return fallbackMillis
            if (offset + 4 > response.size) return fallbackMillis
            offset += 4
        }
        var minTtl: Long? = null
        repeat(answerCount) {
            offset = skipName(response, offset) ?: return@repeat
            if (offset + 10 > response.size) return@repeat
            val ttl = readInt(response, offset + 4).toLong().coerceAtLeast(0L)
            val dataLength = readShort(response, offset + 8)
            offset += 10
            if (offset + dataLength > response.size) return@repeat
            offset += dataLength
            minTtl = minTtl?.coerceAtMost(ttl) ?: ttl
        }
        return ((minTtl ?: fallbackSeconds).coerceIn(5L, 600L)) * 1000L
    }

    fun extractCnameTargets(response: ByteArray, question: DnsQuestion): List<String> {
        val targets = linkedSetOf<String>()
        forEachAnswerRecord(response, question) { _, type, offset, _ ->
            if (type == TYPE_CNAME) {
                decodeName(response, offset)?.let { targets += it.lowercase() }
            }
        }
        return targets.toList()
    }

    fun extractAliasTargets(response: ByteArray, question: DnsQuestion): List<String> {
        val targets = linkedSetOf<String>()
        forEachAnswerRecord(response, question) { _, type, offset, dataLength ->
            when (type) {
                TYPE_CNAME -> {
                    decodeName(response, offset)?.let { targets += it.lowercase() }
                }

                TYPE_SVCB, TYPE_HTTPS -> {
                    extractSvcbTarget(response, offset, dataLength)?.let { targets += it.lowercase() }
                }
            }
        }
        return targets.toList()
    }

    fun negativeCacheTtlMillis(fallbackSeconds: Long = 15L): Long {
        return fallbackSeconds.coerceIn(5L, 30L) * 1000L
    }

    fun extractAnswerAddresses(response: ByteArray, question: DnsQuestion): List<ByteArray> {
        val results = mutableListOf<ByteArray>()
        forEachAnswerRecord(response, question) { _, type, offset, dataLength ->
            when {
                type == TYPE_A && dataLength == 4 -> results += response.copyOfRange(offset, offset + 4)
                type == TYPE_AAAA && dataLength == 16 -> results += response.copyOfRange(offset, offset + 16)
            }
        }
        return results
    }

    private fun forEachAnswerRecord(
        response: ByteArray,
        question: DnsQuestion,
        block: (index: Int, type: Int, dataOffset: Int, dataLength: Int) -> Unit
    ) {
        if (response.size < 12) return
        val parsedQuestion = parseQuestion(response) ?: return
        if (!parsedQuestion.domain.equals(question.domain, ignoreCase = true)) return
        val questionCount = readShort(response, 4)
        val totalRecordCount = totalRecordCount(response)
        if (questionCount <= 0 || totalRecordCount <= 0) return
        var offset = 12
        repeat(questionCount) {
            offset = skipName(response, offset) ?: return
            if (offset + 4 > response.size) return
            offset += 4
        }
        repeat(totalRecordCount) { index ->
            offset = skipName(response, offset) ?: return@repeat
            if (offset + 10 > response.size) return@repeat
            val type = readShort(response, offset)
            val dataLength = readShort(response, offset + 8)
            offset += 10
            if (offset + dataLength > response.size) return@repeat
            block(index, type, offset, dataLength)
            offset += dataLength
        }
    }

    private fun extractSvcbTarget(buffer: ByteArray, offset: Int, dataLength: Int): String? {
        if (dataLength < 3 || offset + dataLength > buffer.size) return null
        val targetOffset = offset + 2
        val target = decodeName(buffer, targetOffset)?.trim('.') ?: return null
        if (target.isBlank()) return null
        return target
    }

    private fun totalRecordCount(response: ByteArray): Int {
        return readShort(response, 6) + readShort(response, 8) + readShort(response, 10)
    }

    private fun encodeQuestion(domain: String, qType: Int): ByteArray {
        val out = ByteArrayOutputStream()
        domain.split('.').filter { it.isNotBlank() }.forEach { part ->
            out.write(part.length)
            out.write(part.toByteArray(Charsets.UTF_8))
        }
        out.write(0)
        out.write(shortBytes(qType))
        out.write(byteArrayOf(0x00, 0x01))
        return out.toByteArray()
    }

    private fun shortBytes(value: Int): ByteArray {
        return byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
    }

    private fun readInt(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }

    private fun skipName(buffer: ByteArray, startOffset: Int): Int? {
        var offset = startOffset
        while (offset < buffer.size) {
            val len = buffer[offset].toInt() and 0xFF
            if (len == 0) return offset + 1
            if (len and 0xC0 == 0xC0) {
                if (offset + 1 >= buffer.size) return null
                return offset + 2
            }
            offset += 1
            if (offset + len > buffer.size) return null
            offset += len
        }
        return null
    }

    private fun decodeName(buffer: ByteArray, startOffset: Int, depth: Int = 0): String? {
        if (depth > 8) return null
        var offset = startOffset
        val labels = mutableListOf<String>()
        while (offset < buffer.size) {
            val len = buffer[offset].toInt() and 0xFF
            when {
                len == 0 -> return labels.joinToString(".")
                len and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= buffer.size) return null
                    val pointer = ((len and 0x3F) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                    val pointed = decodeName(buffer, pointer, depth + 1) ?: return null
                    if (pointed.isNotBlank()) labels += pointed
                    return labels.joinToString(".")
                }
                else -> {
                    if (offset + 1 + len > buffer.size) return null
                    labels += buffer.copyOfRange(offset + 1, offset + 1 + len).toString(Charsets.UTF_8)
                    offset += len + 1
                }
            }
        }
        return null
    }

    private fun ByteArrayOutputStream.writeHeader(queryPayload: ByteArray, rCode: Int, answerCount: Int) {
        write(queryPayload, 0, 2)
        write(byteArrayOf(0x81.toByte(), (0x80 or (rCode and 0x0F)).toByte()))
        write(byteArrayOf(0x00, 0x01))
        write(shortBytes(answerCount))
        write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }
}
