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
            output.write(0x00)
            writeString(output, header.name)
            writeString(output, header.value)
        }
        return output.toByteArray()
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
}
