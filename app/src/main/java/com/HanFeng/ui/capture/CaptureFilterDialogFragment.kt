package com.HanFeng.ui.capture

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.HanFeng.R
import com.HanFeng.capture.CaptureFilter
import com.HanFeng.capture.CaptureFilterParser

/**
 * 抓包列表筛选对话框(design 现有组件改动表;requirements R1.2)。
 *
 * 输入字段:
 * - host: 任意子串
 * - methods: GET / POST / PUT / DELETE / PATCH / HEAD / OPTIONS 多选 (chip)
 * - statusRanges: "200,3xx,4xx,404..410" 等
 *
 * 关闭后由 [Listener] 回填完整 [CaptureFilter] 给 [CaptureFragment]。
 */
class CaptureFilterDialogFragment(
    private val current: CaptureFilter,
    private val onApply: (CaptureFilter) -> Unit
) : DialogFragment() {

    interface Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        val hostEdit = EditText(ctx).apply {
            hint = getString(R.string.capture_filter_host_hint)
            setText(current.hostContains.orEmpty())
        }
        container.addView(hostEdit)

        // method 多选 chips
        val methodsAvailable = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
        val methodChips = LinkedHashMap<String, com.google.android.material.chip.Chip>()
        val methodGroup = com.google.android.material.chip.ChipGroup(ctx).apply {
            isSingleSelection = false
        }
        methodsAvailable.forEach { m ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = m
                isCheckable = true
                isChecked = current.methods.contains(m)
            }
            methodChips[m] = chip
            methodGroup.addView(chip)
        }
        container.addView(methodGroup)

        val statusEdit = EditText(ctx).apply {
            hint = getString(R.string.capture_filter_status_hint)
            setText(formatBackStatusRange(current.statusRanges))
        }
        container.addView(statusEdit)

        // 批次 C3: 关键字输入框
        val keywordEdit = EditText(ctx).apply {
            hint = getString(R.string.capture_filter_keyword_hint)
            setText(current.keyword.orEmpty())
        }
        container.addView(keywordEdit)

        // 批次 C3: sessionId 输入框
        val sessionEdit = EditText(ctx).apply {
            hint = getString(R.string.capture_filter_session_hint)
            setText(current.sessionId.orEmpty())
        }
        container.addView(sessionEdit)

        // 批次 C3: 仅看被拦截条目复选框
        val interceptedCheck = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.capture_filter_intercepted_only)
            isChecked = current.interceptedOnly
        }
        container.addView(interceptedCheck)

        // 批次 D: durationMs 区间(如 50..2000)
        val durationEdit = EditText(ctx).apply {
            hint = getString(R.string.capture_filter_duration_hint)
            setText(formatBackDurationRange(current.durationMsRange))
        }
        container.addView(durationEdit)

        // 批次 D: appName 精确
        val appNameEdit = EditText(ctx).apply {
            hint = getString(R.string.capture_filter_appname_hint)
            setText(current.appName.orEmpty())
        }
        container.addView(appNameEdit)

        return AlertDialog.Builder(ctx)
            .setTitle(R.string.capture_filter)
            .setView(container)
            .setPositiveButton(R.string.capture_filter_apply) { _, _ ->
                val selectedMethods = methodChips.filterValues { it.isChecked }.keys
                val ranges = CaptureFilterParser.parseStatusRanges(statusEdit.text.toString())
                onApply(
                    CaptureFilter(
                        hostContains = hostEdit.text.toString().trim().ifBlank { null },
                        methods = selectedMethods.toSet(),
                        statusRanges = ranges,
                        keyword = keywordEdit.text.toString().trim().ifBlank { null },
                        sessionId = sessionEdit.text.toString().trim().ifBlank { null },
                        interceptedOnly = interceptedCheck.isChecked,
                        durationMsRange = parseDurationRange(durationEdit.text.toString().trim()),
                        appName = appNameEdit.text.toString().trim().ifBlank { null }
                    )
                )
            }
            .setNegativeButton(R.string.capture_filter_reset) { _, _ -> onApply(CaptureFilter()) }
            .create()
    }

    /** 解析 "50..2000" / "100" / "" 。 */
    private fun parseDurationRange(input: String): LongRange? {
        if (input.isBlank()) return null
        return try {
            val parts = input.split("..", "-", ":")
            val first = parts.firstOrNull()?.trim()?.toLongOrNull() ?: return null
            val last = parts.elementAtOrNull(1)?.trim()?.toLongOrNull() ?: first
            LongRange(minOf(first, last), maxOf(first, last))
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatBackDurationRange(range: LongRange?): String {
        if (range == null) return ""
        return if (range.first == range.last) range.first.toString()
        else "${range.first}..${range.last}"
    }

    /** 仅用于编辑框回填:把状态码段反向为紧凑表达式。 */
    private fun formatBackStatusRange(ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return ""
        return ranges.joinToString(",") { r ->
            val s = r.first
            val e = r.last
            when {
                e - s == 99 && s % 100 == 0 -> "${s / 100}xx"
                s == e -> s.toString()
                else -> "$s..$e"
            }
        }
    }
}
