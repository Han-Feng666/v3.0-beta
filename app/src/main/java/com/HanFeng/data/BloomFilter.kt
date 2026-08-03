package com.HanFeng.data

/**
 * Bloom Filter 实现，用于快速预筛选域名是否在规则集中
 */
class BloomFilter private constructor(
    private val size: Int,
    private val hashCount: Int
) {
    private val bits = LongArray((size + 63) / 64)

    companion object {
        fun create(expectedElements: Int, falsePositiveRate: Double = 0.01): BloomFilter {
            val m = optimalSize(expectedElements, falsePositiveRate)
            val k = optimalHashCount(m, expectedElements)
            return BloomFilter(m.coerceAtLeast(1), k)
        }

        private fun optimalSize(n: Int, p: Double): Int {
            if (n <= 0) return 1
            val value = (-n * kotlin.math.ln(p)) / (kotlin.math.ln(2.0) * kotlin.math.ln(2.0))
            return kotlin.math.ceil(value).toInt().coerceAtLeast(1)
        }

        private fun optimalHashCount(m: Int, n: Int): Int {
            if (n <= 0 || m <= 0) return 1
            val value = (m.toDouble() / n) * kotlin.math.ln(2.0)
            val k = kotlin.math.round(value).toInt()
            return k.coerceIn(1, 30)
        }
    }

    fun put(element: String) {
        val h1 = hash1(element)
        val h2 = hash2(element)
        for (i in 0 until hashCount) {
            val bitIndex = safeBitIndex(h1, i, h2)
            val wordIndex = bitIndex / 64
            val bitInWord = bitIndex % 64
            bits[wordIndex] = bits[wordIndex] or (1L shl bitInWord)
        }
    }

    fun mightContain(element: String): Boolean {
        val h1 = hash1(element)
        val h2 = hash2(element)
        for (i in 0 until hashCount) {
            val bitIndex = safeBitIndex(h1, i, h2)
            val wordIndex = bitIndex / 64
            val bitInWord = bitIndex % 64
            if (bits[wordIndex] and (1L shl bitInWord) == 0L) {
                return false
            }
        }
        return true
    }

    fun getMemoryBytes(): Int = bits.size * 8

    /**
     * 计算位索引，使用 Math.floorMod 保证结果在 [0, size) 范围内
     * 防止 (h1 + i * h2) 整数溢出变成负数导致 % 返回负值
     */
    private fun safeBitIndex(h1: Int, i: Int, h2: Int): Int {
        val combined = h1.toLong() + i.toLong() * h2.toLong()
        return Math.floorMod(combined.toInt(), size)
    }

    private fun hash1(data: String): Int {
        var hash: Int = -2128832299
        for (i in 0 until data.length) {
            hash = hash xor data[i].code
            hash = hash * 16777619
        }
        return hash and Int.MAX_VALUE
    }

    private fun hash2(data: String): Int {
        var hash: Int = -375076303
        for (i in 0 until data.length) {
            hash = hash xor data[i].code
            hash = hash * 1099511628
        }
        return hash and Int.MAX_VALUE
    }
}