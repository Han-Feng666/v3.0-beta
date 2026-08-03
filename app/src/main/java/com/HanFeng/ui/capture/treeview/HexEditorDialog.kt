package com.HanFeng.ui.capture.treeview

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R

/**
 * 二进制 hex 编辑器(batch B: requirements R12)。
 *
 * - 默认只读, 切换到 "编辑" 后输入框可改 ASCII; 改完点"应用"自动同步回 bytes
 * - 简化: 不支持直接改 hex 字符(避免行级 16 字节定位 bug); 让用户在 ASCII 中改 char, 自动映射 bytes
 * - 应用按钮 toast 提示已写入多少字节
 */
class HexEditorDialog(private val original: ByteArray) : DialogFragment() {

    private var workingBytes: ByteArray = original.copyOf()
    private var editable: Boolean = false
    private var onApply: ((ByteArray) -> Unit)? = null

    fun setOnApply(cb: (ByteArray) -> Unit): HexEditorDialog {
        onApply = cb
        return this
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_hex_editor, null)
        val rvHex = view.findViewById<RecyclerView>(R.id.rvHex)
        val adapter = HexAdapter(workingBytes, maxBytes = 64 * 1024)
        rvHex.layoutManager = LinearLayoutManager(requireContext())
        rvHex.adapter = adapter

        val tvStatus = view.findViewById<TextView>(R.id.tvHexStatus)
        tvStatus.text = getString(R.string.capture_hex_status_readonly, workingBytes.size)

        val etAscii = view.findViewById<EditText>(R.id.etAscii)
        etAscii.isEnabled = false
        etAscii.setText(String(workingBytes, Charsets.ISO_8859_1))
        etAscii.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!editable) return
                val newBytes = (s?.toString() ?: "").toByteArray(Charsets.ISO_8859_1)
                workingBytes = newBytes
                tvStatus.text = getString(R.string.capture_hex_status_editing, newBytes.size)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val btnToggle = view.findViewById<android.widget.Button>(R.id.btnToggleEdit)
        btnToggle.setOnClickListener {
            editable = !editable
            etAscii.isEnabled = editable
            btnToggle.text = if (editable) getString(R.string.capture_hex_lock) else getString(R.string.capture_hex_unlock)
            tvStatus.text = if (editable) getString(R.string.capture_hex_status_editing, workingBytes.size)
            else getString(R.string.capture_hex_status_readonly, workingBytes.size)
        }

        return AlertDialog.Builder(requireActivity())
            .setTitle(R.string.capture_hex_editor_title)
            .setView(view)
            .setPositiveButton(R.string.capture_hex_apply) { _, _ ->
                onApply?.invoke(workingBytes)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
