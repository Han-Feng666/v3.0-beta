package com.HanFeng.ui.capture.treeview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R

/**
 * 把字节流渲染为 Hex 视图(每行 16 字节, 含 ASCII column)。
 *
 * - 默认渲染前 4KB(避免 OOM)
 * - 编辑场景由 [HexEditorDialog] 处理, 这里仅查看
 */
class HexAdapter(private val data: ByteArray, private val maxBytes: Int = 4 * 1024) :
    RecyclerView.Adapter<HexAdapter.VH>() {

    private val bytesPerRow = 16
    private val rows: Int

    init {
        val effective = minOf(data.size, maxBytes)
        rows = (effective + bytesPerRow - 1) / bytesPerRow
        setHasStableIds(true)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLine: TextView = itemView.findViewById(R.id.tvHexLine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_hex_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tvLine.text = formatRow(position * bytesPerRow)
    }

    override fun getItemCount(): Int = rows
    override fun getItemId(position: Int): Long = position.toLong()

    private fun formatRow(offset: Int): String {
        val sb = StringBuilder(80)
        sb.append(String.format("%08X  ", offset))
        val ascii = StringBuilder()
        for (i in 0 until bytesPerRow) {
            val idx = offset + i
            if (idx >= data.size) {
                sb.append("   ")
                continue
            }
            val b = data[idx].toInt() and 0xFF
            sb.append(String.format("%02X ", b))
            val c = (if (b in 32..126) b.toChar() else '.')
            ascii.append(c)
        }
        sb.append(' ').append(ascii)
        return sb.toString()
    }
}
