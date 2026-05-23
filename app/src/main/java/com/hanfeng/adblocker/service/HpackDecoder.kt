package com.HanFeng.service

object HpackDecoder {
    private const val DEFAULT_MAX_DYNAMIC_TABLE_SIZE = 4096

    data class HeaderField(
        val name: String,
        val value: String
    )

    data class DecoderState(
        val dynamicTable: MutableList<HeaderField> = mutableListOf(),
        var maxDynamicTableSize: Int = DEFAULT_MAX_DYNAMIC_TABLE_SIZE,
        var currentDynamicTableSize: Int = 0
    )

    data class DecodeResult(
        val headers: List<HeaderField>,
        val huffmanEncodedStrings: Int,
        val dynamicTableSizeUpdates: Int,
        val truncated: Boolean,
        val error: String? = null
    )

    fun decode(block: ByteArray, state: DecoderState): DecodeResult {
        val headers = mutableListOf<HeaderField>()
        var offset = 0
        var huffmanEncodedStrings = 0
        var dynamicTableSizeUpdates = 0
        while (offset < block.size) {
            val first = block[offset].toInt() and 0xFF
            when {
                (first and 0x80) != 0 -> {
                    val indexed = decodeInteger(block, offset, 7) ?: return DecodeResult(headers, huffmanEncodedStrings, dynamicTableSizeUpdates, true, "indexed-int")
                    offset = indexed.nextOffset
                    resolveHeader(indexed.value, state)?.let { headers += it }
                        ?: return DecodeResult(headers, huffmanEncodedStrings, dynamicTableSizeUpdates, true, "indexed-header-${indexed.value}")
                }

                (first and 0x40) != 0 -> {
                    val literal = decodeLiteralHeader(block, offset, 6, state) ?: return DecodeResult(headers, huffmanEncodedStrings, dynamicTableSizeUpdates, true, "literal-incremental")
                    offset = literal.nextOffset
                    headers += literal.header
                    huffmanEncodedStrings += literal.huffmanEncodedStrings
                    addDynamicEntry(state, literal.header)
                }

                (first and 0x20) != 0 -> {
                    val sizeUpdate = decodeInteger(block, offset, 5) ?: return DecodeResult(headers, huffmanEncodedStrings, dynamicTableSizeUpdates, true, "table-size-update")
                    offset = sizeUpdate.nextOffset
                    state.maxDynamicTableSize = sizeUpdate.value
                    evictDynamicEntries(state)
                    dynamicTableSizeUpdates += 1
                }

                (first and 0x10) != 0 -> {
                    val literal = decodeLiteralHeader(block, offset, 4, state) ?: return DecodeResult(headers, huffmanEncodedStrings, dynamicTableSizeUpdates, true, "literal-never-indexed")
                    offset = literal.nextOffset
                    headers += literal.header
                    huffmanEncodedStrings += literal.huffmanEncodedStrings
                }

                else -> {
                    val literal = decodeLiteralHeader(block, offset, 4, state) ?: return DecodeResult(headers, huffmanEncodedStrings, dynamicTableSizeUpdates, true, "literal-without-indexing")
                    offset = literal.nextOffset
                    headers += literal.header
                    huffmanEncodedStrings += literal.huffmanEncodedStrings
                }
            }
        }
        return DecodeResult(headers, huffmanEncodedStrings, dynamicTableSizeUpdates, false)
    }

    private fun decodeLiteralHeader(
        block: ByteArray,
        offset: Int,
        prefixBits: Int,
        state: DecoderState
    ): DecodedLiteralHeader? {
        val nameIndex = decodeInteger(block, offset, prefixBits) ?: return null
        var nextOffset = nameIndex.nextOffset
        var huffmanCount = 0
        val name = if (nameIndex.value == 0) {
            val decoded = decodeString(block, nextOffset) ?: return null
            nextOffset = decoded.nextOffset
            huffmanCount += if (decoded.huffmanEncoded) 1 else 0
            decoded.value
        } else {
            resolveHeaderName(nameIndex.value, state) ?: return null
        }
        val valueDecoded = decodeString(block, nextOffset) ?: return null
        nextOffset = valueDecoded.nextOffset
        huffmanCount += if (valueDecoded.huffmanEncoded) 1 else 0
        return DecodedLiteralHeader(
            header = HeaderField(name, valueDecoded.value),
            nextOffset = nextOffset,
            huffmanEncodedStrings = huffmanCount
        )
    }

    private fun decodeString(block: ByteArray, offset: Int): DecodedString? {
        if (offset >= block.size) return null
        val first = block[offset].toInt() and 0xFF
        val huffman = (first and 0x80) != 0
        val lengthInfo = decodeInteger(block, offset, 7) ?: return null
        val endOffset = lengthInfo.nextOffset + lengthInfo.value
        if (endOffset > block.size) return null
        val bytes = block.copyOfRange(lengthInfo.nextOffset, endOffset)
        val value = if (huffman) {
            decodeHuffman(bytes) ?: "<huffman:${bytes.size}>"
        } else {
            bytes.toString(Charsets.UTF_8)
        }
        return DecodedString(value = value, nextOffset = endOffset, huffmanEncoded = huffman)
    }

    private fun decodeHuffman(bytes: ByteArray): String? {
        val builder = StringBuilder()
        var current = HUFFMAN_ROOT
        var consumedBits = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            for (bitIndex in 7 downTo 0) {
                val bit = (value shr bitIndex) and 1
                current = current.children[bit] ?: return null
                consumedBits += 1
                val symbol = current.symbol
                if (symbol >= 0) {
                    if (symbol == HUFFMAN_EOS) return null
                    builder.append(symbol.toChar())
                    current = HUFFMAN_ROOT
                }
            }
        }
        var paddingBits = 0
        var paddingNode = current
        while (paddingNode !== HUFFMAN_ROOT) {
            val next = paddingNode.children[1] ?: break
            paddingNode = next
            paddingBits += 1
            if (paddingBits > 7) return null
            if (paddingNode.symbol >= 0) return null
        }
        if (current !== HUFFMAN_ROOT && paddingNode !== HUFFMAN_ROOT) return null
        return builder.toString()
    }

    private fun decodeInteger(block: ByteArray, offset: Int, prefixBits: Int): DecodedInteger? {
        if (offset >= block.size) return null
        val prefixMask = (1 shl prefixBits) - 1
        val first = block[offset].toInt() and 0xFF
        var value = first and prefixMask
        if (value < prefixMask) {
            return DecodedInteger(value, offset + 1)
        }
        var nextOffset = offset + 1
        var shift = 0
        while (nextOffset < block.size) {
            val current = block[nextOffset].toInt() and 0xFF
            value += (current and 0x7F) shl shift
            nextOffset += 1
            if ((current and 0x80) == 0) {
                return DecodedInteger(value, nextOffset)
            }
            shift += 7
        }
        return null
    }

    private fun resolveHeader(index: Int, state: DecoderState): HeaderField? {
        if (index <= 0) return null
        if (index <= STATIC_TABLE.size) return STATIC_TABLE[index - 1]
        val dynamicIndex = index - STATIC_TABLE.size - 1
        return state.dynamicTable.getOrNull(dynamicIndex)
    }

    private fun resolveHeaderName(index: Int, state: DecoderState): String? {
        return resolveHeader(index, state)?.name
    }

    private fun addDynamicEntry(state: DecoderState, header: HeaderField) {
        val entrySize = entrySize(header)
        if (entrySize > state.maxDynamicTableSize) {
            state.dynamicTable.clear()
            state.currentDynamicTableSize = 0
            return
        }
        state.dynamicTable.add(0, header)
        state.currentDynamicTableSize += entrySize
        evictDynamicEntries(state)
    }

    private fun evictDynamicEntries(state: DecoderState) {
        while (state.currentDynamicTableSize > state.maxDynamicTableSize && state.dynamicTable.isNotEmpty()) {
            val removed = state.dynamicTable.removeAt(state.dynamicTable.lastIndex)
            state.currentDynamicTableSize -= entrySize(removed)
        }
    }

    private fun entrySize(header: HeaderField): Int = 32 + header.name.toByteArray(Charsets.UTF_8).size + header.value.toByteArray(Charsets.UTF_8).size

    private data class DecodedInteger(
        val value: Int,
        val nextOffset: Int
    )

    private data class DecodedString(
        val value: String,
        val nextOffset: Int,
        val huffmanEncoded: Boolean
    )

    private data class DecodedLiteralHeader(
        val header: HeaderField,
        val nextOffset: Int,
        val huffmanEncodedStrings: Int
    )

    private class HuffmanNode(
        var symbol: Int = -1,
        val children: Array<HuffmanNode?> = arrayOfNulls(2)
    )

    private val STATIC_TABLE = listOf(
        HeaderField(":authority", ""),
        HeaderField(":method", "GET"),
        HeaderField(":method", "POST"),
        HeaderField(":path", "/"),
        HeaderField(":path", "/index.html"),
        HeaderField(":scheme", "http"),
        HeaderField(":scheme", "https"),
        HeaderField(":status", "200"),
        HeaderField(":status", "204"),
        HeaderField(":status", "206"),
        HeaderField(":status", "304"),
        HeaderField(":status", "400"),
        HeaderField(":status", "404"),
        HeaderField(":status", "500"),
        HeaderField("accept-charset", ""),
        HeaderField("accept-encoding", "gzip, deflate"),
        HeaderField("accept-language", ""),
        HeaderField("accept-ranges", ""),
        HeaderField("accept", ""),
        HeaderField("access-control-allow-origin", ""),
        HeaderField("age", ""),
        HeaderField("allow", ""),
        HeaderField("authorization", ""),
        HeaderField("cache-control", ""),
        HeaderField("content-disposition", ""),
        HeaderField("content-encoding", ""),
        HeaderField("content-language", ""),
        HeaderField("content-length", ""),
        HeaderField("content-location", ""),
        HeaderField("content-range", ""),
        HeaderField("content-type", ""),
        HeaderField("cookie", ""),
        HeaderField("date", ""),
        HeaderField("etag", ""),
        HeaderField("expect", ""),
        HeaderField("expires", ""),
        HeaderField("from", ""),
        HeaderField("host", ""),
        HeaderField("if-match", ""),
        HeaderField("if-modified-since", ""),
        HeaderField("if-none-match", ""),
        HeaderField("if-range", ""),
        HeaderField("if-unmodified-since", ""),
        HeaderField("last-modified", ""),
        HeaderField("link", ""),
        HeaderField("location", ""),
        HeaderField("max-forwards", ""),
        HeaderField("proxy-authenticate", ""),
        HeaderField("proxy-authorization", ""),
        HeaderField("range", ""),
        HeaderField("referer", ""),
        HeaderField("refresh", ""),
        HeaderField("retry-after", ""),
        HeaderField("server", ""),
        HeaderField("set-cookie", ""),
        HeaderField("strict-transport-security", ""),
        HeaderField("transfer-encoding", ""),
        HeaderField("user-agent", ""),
        HeaderField("vary", ""),
        HeaderField("via", ""),
        HeaderField("www-authenticate", "")
    )

    private val HUFFMAN_ROOT: HuffmanNode = buildHuffmanTree()

    private fun buildHuffmanTree(): HuffmanNode {
        val root = HuffmanNode()
        for (index in HUFFMAN_CODES.indices) {
            var node = root
            val bitLength = HUFFMAN_CODE_LENGTHS[index]
            val code = HUFFMAN_CODES[index]
            for (bitIndex in bitLength - 1 downTo 0) {
                val bit = (code ushr bitIndex) and 1
                val child = node.children[bit] ?: HuffmanNode().also { node.children[bit] = it }
                node = child
            }
            node.symbol = index
        }
        return root
    }

    private const val HUFFMAN_EOS = 256

    private val HUFFMAN_CODES = intArrayOf(
        0x1ff8, 0x7fffd8, 0xfffffe2, 0xfffffe3, 0xfffffe4, 0xfffffe5, 0xfffffe6, 0xfffffe7,
        0xfffffe8, 0xffffea, 0x3ffffffc, 0xfffffe9, 0xfffffea, 0x3ffffffd, 0xfffffeb, 0xfffffec,
        0xfffffed, 0xfffffee, 0xfffffef, 0xffffff0, 0xffffff1, 0xffffff2, 0x3ffffffe, 0xffffff3,
        0xffffff4, 0xffffff5, 0xffffff6, 0xffffff7, 0xffffff8, 0xffffff9, 0xffffffa, 0xffffffb,
        0x14, 0x3f8, 0x3f9, 0xffa, 0x1ff9, 0x15, 0xf8, 0x7fa,
        0x3fa, 0x3fb, 0xf9, 0x7fb, 0xfa, 0x16, 0x17, 0x18,
        0x0, 0x1, 0x2, 0x19, 0x1a, 0x1b, 0x1c, 0x1d,
        0x1e, 0x1f, 0x5c, 0xfb, 0x7ffc, 0x20, 0xffb, 0x3fc,
        0x1ffa, 0x21, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62,
        0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
        0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72,
        0xfc, 0x73, 0xfd, 0x1ffb, 0x7fff0, 0x1ffc, 0x3ffc, 0x22,
        0x7ffd, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26,
        0x27, 0x6, 0x74, 0x75, 0x28, 0x29, 0x2a, 0x7,
        0x2b, 0x76, 0x2c, 0x8, 0x9, 0x2d, 0x77, 0x78,
        0x79, 0x7a, 0x7b, 0x7ffe, 0x7fc, 0x3ffd, 0x1ffd, 0xffffffc,
        0xfffe6, 0x3fffd2, 0xfffe7, 0xfffe8, 0x3fffd3, 0x3fffd4, 0x3fffd5, 0x7fffd9,
        0x3fffd6, 0x7fffda, 0x7fffdb, 0x7fffdc, 0x7fffdd, 0x7fffde, 0xffffeb, 0x7fffdf,
        0xffffec, 0xffffed, 0x3fffd7, 0x7fffe0, 0xffffee, 0x7fffe1, 0x7fffe2, 0x7fffe3,
        0x7fffe4, 0x1fffdc, 0x3fffd8, 0x7fffe5, 0x3fffd9, 0x7fffe6, 0x7fffe7, 0xffffef,
        0x3fffda, 0x1fffdd, 0xfffe9, 0x3fffdb, 0x3fffdc, 0x7fffe8, 0x7fffe9, 0x1fffde,
        0x7fffea, 0x3fffdd, 0x3fffde, 0xfffff0, 0x1fffdf, 0x3fffdf, 0x7fffeb, 0x7fffec,
        0x1fffe0, 0x1fffe1, 0x3fffe0, 0x1fffe2, 0x7fffed, 0x3fffe1, 0x7fffee, 0x7fffef,
        0xfffea, 0x3fffe2, 0x3fffe3, 0x3fffe4, 0x7ffff0, 0x3fffe5, 0x3fffe6, 0x7ffff1,
        0x3ffffe0, 0x3ffffe1, 0xfffeb, 0x7fff1, 0x3fffe7, 0x7ffff2, 0x3fffe8, 0x1ffffec,
        0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x7ffffde, 0x7ffffdf, 0x3ffffe5, 0xfffff1, 0x1ffffed,
        0x7fff2, 0x1fffe3, 0x3ffffe6, 0x7ffffe0, 0x7ffffe1, 0x3ffffe7, 0x7ffffe2, 0xfffff2,
        0x1fffe4, 0x1fffe5, 0x3ffffe8, 0x3ffffe9, 0xffffffd, 0x7ffffe3, 0x7ffffe4, 0x7ffffe5,
        0xfffec, 0xfffff3, 0xfffed, 0x1fffe6, 0x3fffe9, 0x1fffe7, 0x1fffe8, 0x7ffff3,
        0x3fffea, 0x3fffeb, 0x1ffffee, 0x1ffffef, 0xfffff4, 0xfffff5, 0x3ffffea, 0x7ffff4,
        0x3ffffeb, 0x7ffffe6, 0x3ffffec, 0x3ffffed, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea,
        0x7ffffeb, 0xffffffe, 0x7ffffec, 0x7ffffed, 0x7ffffee, 0x7ffffef, 0x7fffff0, 0x3ffffee,
        0x3fffffff
    )

    private val HUFFMAN_CODE_LENGTHS = intArrayOf(
        13, 23, 28, 28, 28, 28, 28, 28,
        28, 24, 30, 28, 28, 30, 28, 28,
        28, 28, 28, 28, 28, 28, 30, 28,
        28, 28, 28, 28, 28, 28, 28, 28,
        6, 10, 10, 12, 13, 6, 8, 11,
        10, 10, 8, 11, 8, 6, 6, 6,
        5, 5, 5, 6, 6, 6, 6, 6,
        6, 6, 7, 8, 15, 6, 12, 10,
        13, 6, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        8, 7, 8, 13, 19, 13, 14, 6,
        15, 5, 6, 5, 6, 5, 6, 6,
        6, 5, 7, 7, 6, 6, 6, 5,
        6, 7, 6, 5, 5, 6, 7, 7,
        7, 7, 7, 15, 11, 14, 13, 28,
        20, 22, 20, 20, 22, 22, 22, 23,
        22, 23, 23, 23, 23, 23, 24, 23,
        24, 24, 22, 23, 24, 23, 23, 23,
        23, 21, 22, 23, 22, 23, 23, 24,
        22, 21, 20, 22, 22, 23, 23, 21,
        23, 22, 22, 24, 21, 22, 23, 23,
        21, 21, 22, 21, 23, 22, 23, 23,
        20, 22, 22, 22, 23, 22, 22, 23,
        26, 26, 20, 19, 22, 23, 22, 25,
        26, 26, 26, 27, 27, 26, 24, 25,
        19, 21, 26, 27, 27, 26, 27, 24,
        21, 21, 26, 26, 28, 27, 27, 27,
        20, 24, 20, 21, 22, 21, 21, 23,
        22, 22, 25, 25, 24, 24, 26, 23,
        26, 27, 26, 26, 27, 27, 27, 27,
        27, 28, 27, 27, 27, 27, 27, 26,
        30
    )
}
