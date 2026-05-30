package com.HanFeng.service

import java.io.ByteArrayOutputStream

object HpackEncoder {
    fun encodeHeadersWithoutIndexing(headers: List<HpackDecoder.HeaderField>): ByteArray {
        return encodeLiteralHeadersWithoutIndexing(headers)
    }

    fun encodeLiteralHeadersWithoutIndexing(headers: List<HpackDecoder.HeaderField>): ByteArray {
        if (headers.isEmpty()) return ByteArray(0)
        val output = ByteArrayOutputStream(headers.size * 32)
        headers.forEach { header ->
            val staticIndex = findStaticHeaderNameIndex(header.name)
            if (staticIndex > 0) {
                writeInteger(output, staticIndex, 4, 0x00)
            } else {
                output.write(0x00)
                writeString(output, header.name)
            }
            writeString(output, header.value)
        }
        return output.toByteArray()
    }

    private fun findStaticHeaderNameIndex(name: String): Int {
        val normalized = name.trim().lowercase()
        if (normalized.isBlank()) return -1
        return STATIC_HEADER_NAME_INDEX[normalized] ?: -1
    }

    private fun writeString(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInteger(output, bytes.size, 7, 0x00)
        output.write(bytes)
    }

    private fun writeInteger(output: ByteArrayOutputStream, value: Int, prefixBits: Int, prefixMaskBits: Int) {
        val maxPrefixValue = (1 shl prefixBits) - 1
        if (value < maxPrefixValue) {
            output.write(prefixMaskBits or value)
            return
        }
        output.write(prefixMaskBits or maxPrefixValue)
        var remaining = value - maxPrefixValue
        while (remaining >= 128) {
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        output.write(remaining)
    }

    private val STATIC_HEADER_NAME_INDEX = mapOf(
        ":authority" to 1,
        ":method" to 2,
        ":path" to 4,
        ":scheme" to 6,
        ":status" to 8,
        "accept-charset" to 15,
        "accept-encoding" to 16,
        "accept-language" to 17,
        "accept-ranges" to 18,
        "accept" to 19,
        "access-control-allow-origin" to 20,
        "age" to 21,
        "allow" to 22,
        "authorization" to 23,
        "cache-control" to 24,
        "content-disposition" to 25,
        "content-encoding" to 26,
        "content-language" to 27,
        "content-length" to 28,
        "content-location" to 29,
        "content-range" to 30,
        "content-type" to 31,
        "cookie" to 32,
        "date" to 33,
        "etag" to 34,
        "expect" to 35,
        "expires" to 36,
        "from" to 37,
        "host" to 38,
        "if-match" to 39,
        "if-modified-since" to 40,
        "if-none-match" to 41,
        "if-range" to 42,
        "if-unmodified-since" to 43,
        "last-modified" to 44,
        "link" to 45,
        "location" to 46,
        "max-forwards" to 47,
        "proxy-authenticate" to 48,
        "proxy-authorization" to 49,
        "range" to 50,
        "referer" to 51,
        "refresh" to 52,
        "retry-after" to 53,
        "server" to 54,
        "set-cookie" to 55,
        "strict-transport-security" to 56,
        "transfer-encoding" to 57,
        "user-agent" to 58,
        "vary" to 59,
        "via" to 60,
        "www-authenticate" to 61
    )
}
