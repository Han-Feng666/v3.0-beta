package com.HanFeng.capture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Batch E5: 响应体自动解压(gzip / deflate / identity / brotli)。
 *
 * 项目已含 `org.brotli:dec:0.1.2` 依赖; gzip / deflate 走 JDK 标准库。
 *
 * 调用方: [com.HanFeng.ui.capture.treeview.TreeParser] / TreeView 加载流程。
 * 入参 contentEncoding = "gzip" / "deflate" / "br" / null
 * 返回原始(未压缩)字节; 解压失败或未知编码 → 原字节 + failed 标志。
 */
data class DecompressionResult(
    val body: ByteArray,
    val encoding: String,
    val decompressed: Boolean,
    val error: String? = null
)

object BodyDecompressor {

    /**
     * @param body raw 字节
     * @param contentEncoding HTTP "Content-Encoding" header 原值(大小写敏感)
     */
    fun decompress(body: ByteArray, contentEncoding: String?): DecompressionResult {
        val enc = contentEncoding?.trim()?.lowercase().orEmpty()
        if (enc.isEmpty() || enc == "identity") {
            return DecompressionResult(body = body, encoding = "identity", decompressed = false)
        }
        // 多层编码优先分隔常用编码: gzip, deflate → 依次解; 但本项目实际只看一级
        val first = enc.substringBefore(',').trim()
        return try {
            val out: ByteArray = when (first) {
                "gzip", "x-gzip" -> ungzip(body)
                "deflate" -> inflate(body)
                "br" -> unbrotli(body)
                else -> return DecompressionResult(body = body, encoding = first, decompressed = false)
            }
            DecompressionResult(body = out, encoding = first, decompressed = out.isNotEmpty())
        } catch (e: Throwable) {
            DecompressionResult(body = body, encoding = first, decompressed = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ungzip(input: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(input)).use { gz ->
            val out = ByteArrayOutputStream(input.size.coerceAtLeast(64))
            gz.copyTo(out)
            return out.toByteArray()
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        // raw deflate, RFC 1951
        InflaterInputStream(ByteArrayInputStream(input)).use { inf ->
            val out = ByteArrayOutputStream(input.size.coerceAtLeast(64))
            inf.copyTo(out)
            return out.toByteArray()
        }
    }

    private fun unbrotli(input: ByteArray): ByteArray {
        // org.brotli:dec 0.1.2 — BrotliInputStream
        val cls = runCatching { Class.forName("org.brotli.dec.BrotliInputStream") }
            .getOrNull() ?: return input
        val ctor = cls.getConstructor(java.io.InputStream::class.java)
        val out = ByteArrayOutputStream(input.size.coerceAtLeast(64))
        val brotli = ctor.newInstance(ByteArrayInputStream(input)) as java.io.InputStream
        brotli.use { br ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = br.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
