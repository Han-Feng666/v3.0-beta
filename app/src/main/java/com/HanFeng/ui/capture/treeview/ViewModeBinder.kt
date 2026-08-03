package com.HanFeng.ui.capture.treeview

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R

/**
 * ViewMode 切换: Raw / Tree / Preview
 * - 把三态绑定到一个父 View 的子控件上; 让 [bindViewModeSwitcher] 在任意 tab 创建时被调用即可生效
 */
fun bindViewModeSwitcher(
    root: View,
    bodyRaw: EditText,
    bodyTree: RecyclerView,
    bodyPreview: View,
    imagePreview: ImageView,
    hexView: RecyclerView,
    inputBytes: () -> ByteArray?,
    contentType: (() -> String?)?,
    /** 批次 E5: 响应 content-encoding 字段提供方; null 表示未压缩。若需要, 可解压后再 parse/render。 */
    contentEncoding: (() -> String?)? = null
) {
    val rg = root.findViewById<RadioGroup>(R.id.rgViewMode)
    if (rg == null) return

    /** 抓 E5 解压后字节, 失败时返回原字节(去 catch)。 */
    fun decompressedBytes(): ByteArray? {
        val raw = inputBytes() ?: return null
        val enc = contentEncoding?.invoke()
        if (enc.isNullOrBlank() || enc.lowercase() == "identity") return raw
        val r = com.HanFeng.capture.BodyDecompressor.decompress(raw, enc)
        return r.body
    }

    rg.setOnCheckedChangeListener { _, id ->
        when (id) {
            R.id.rbRaw -> {
                bodyRaw.visibility = View.VISIBLE
                bodyTree.visibility = View.GONE
                bodyPreview.visibility = View.GONE
            }
            R.id.rbTree -> {
                val bytes = decompressedBytes() ?: run {
                    bodyRaw.visibility = View.VISIBLE
                    bodyTree.visibility = View.GONE
                    return@setOnCheckedChangeListener
                }
                val nodes = TreeParser.parse(bytes, contentType?.invoke())
                if (nodes.isNullOrEmpty()) {
                    rg.check(R.id.rbRaw)
                    Toast.makeText(root.context, R.string.capture_view_tree_unsupported, Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                bodyRaw.visibility = View.GONE
                bodyTree.visibility = View.VISIBLE
                bodyPreview.visibility = View.GONE
                bodyTree.layoutManager = LinearLayoutManager(root.context)
                bodyTree.adapter = TreeAdapter(nodes)
            }
            R.id.rbPreview -> {
                renderPreview(root.context, decompressedBytes(), contentType?.invoke(), bodyPreview, imagePreview, hexView)
            }
        }
    }
}

private fun renderPreview(
    context: Context,
    bytes: ByteArray?,
    contentType: String?,
    previewContainer: View,
    imagePreview: ImageView,
    hexView: RecyclerView
) {
    var previewableImage = false
    if (bytes != null) {
        val ct = contentType?.lowercase() ?: ""
        if (ct.startsWith("image/") || isLikelyImage(bytes)) {
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    imagePreview.setImageBitmap(bmp)
                    imagePreview.visibility = View.VISIBLE
                    hexView.visibility = View.GONE
                    previewContainer.visibility = View.VISIBLE
                    previewableImage = true
                }
            }
        }
    }
    if (!previewableImage) {
        imagePreview.visibility = View.GONE
        hexView.visibility = View.VISIBLE
        previewContainer.visibility = View.VISIBLE
        if (bytes != null) {
            hexView.layoutManager = LinearLayoutManager(context)
            hexView.adapter = HexAdapter(bytes)
        }
    }
}

/** 文件签名 magic detection: PNG/JPEG/GIF/WebP/BMP(粗略)。 */
private fun isLikelyImage(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    return when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
            && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> true
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> true
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()
            && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte() -> true
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()
            && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
            && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
            && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> true
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> true
        else -> false
    }
}
