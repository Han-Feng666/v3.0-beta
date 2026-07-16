package com.HanFeng.data

/**
 * Bloom Filter 实现，用于快速预筛选域名是否在规则集中
 *
 * 特点：
 * - 极小的内存占用（100万条规则只需 1-2MB）
 * - O(k) 查询时间复杂度，k 为哈希函数个数
 * - 允许误判（可能将非广告域名误判为广告），但不会漏判
 *
 * @param size 位数组大小
 * @param hashCount 哈希函数个数
 */
class BloomFilter private constructor(
    private val size: Int,
    private val hashCount: Int
) {
    private val bits = LongArray((size + 63) / 64)

    companion object {
        /**
         * 根据预期元素数量和误判率计算最优的位数组大小和哈希函数个数
         *
         * @param expectedElements 预期插入的元素数量
         * @param falsePositiveRate 可接受的误判率（0.01 = 1%）
         */
        fun create(expectedElements: Int, falsePositiveRate: Double = 0.01): BloomFilter {
            val size = optimalSize(expectedElements, falsePositiveRate)
            val hashCount = optimalHashCount(size, expectedElements)
            return BloomFilter(size, hashCount)
        }

        private fun optimalSize(n: Int, p: Double): Int {
            return ceil((-n * ln(p)) / (ln(2.0) * ln(2.0))).toInt()
        }

        private fun optimalHashCount(m: Int, n: Int): Int {
            return maxOf(1, round((m.toDouble() / n) * ln(2.0)).toInt())
        }

        private fun ceil(d: Double) = kotlin.math.ceil(d)
        private fun ln(d: Double) = kotlin.math.ln(d)
        private fun round(d: Double) = kotlin.math.round(d)
    }

    /**
     * 添加元素到位数组
     */
    fun put(element: String) {
        val hash1 = hash1(element)
        val hash2 = hash2(element)
        for (i in 0 until hashCount) {
            val bitIndex = ((hash1 + i * hash2) and 0x7FFFFFFF) % size
            bits[bitIndex / 64] = bits[bitIndex / 64] or (1L shl (bitIndex % 64))
        }
    }

    /**
     * 检查元素是否可能在集合中
     * @return true 表示可能存在（需要进一步验证），false 表示肯定不存在
     */
    fun mightContain(element: String): Boolean {
        val hash1 = hash1(element)
        val hash2 = hash2(element)
        for (i in 0 until hashCount) {
            val bitIndex = ((hash1 + i * hash2) and 0x7FFFFFFF) % size
            if (bits[bitIndex / 64] and (1L shl (bitIndex % 64)) == 0L) {
                return false
            }
        }
        return true
    }

    /**
     * 获取位数组占用的字节数
     */
    fun getMemoryBytes(): Int = bits.size * 8

    /**
     * MurmurHash3 变体 - 哈希函数 1
     */
    private fun hash1(data: String): Long {
        var hash = 0xcbf29ce484222325L
        for (i in 0 until data.length) {
            hash = hash xor data[i].code.toLong()
            hash = hash * 0x100000001b3L
        }
        return hash
    }

    /**
     * FNV-1a 变体 - 哈希函数 2
     */
    private fun hash2(data: String): Long {
        var hash = 0x811c9dc5L
        for (i in 0 until data.length) {
            hash = hash xor data[i].code.toLong()
            hash = hash * 0x01000193
        }
        return hash
    }
}
