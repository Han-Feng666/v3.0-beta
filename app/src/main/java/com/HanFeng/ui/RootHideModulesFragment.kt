package com.HanFeng.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.HanFeng.R
import com.HanFeng.adblocker.shizuku.RootHideRepository
import com.HanFeng.adblocker.shizuku.SuSession

class RootHideModulesFragment : Fragment() {

    private val moduleItems = mutableListOf<HideSectionItem>()
    private val appItems = mutableListOf<HideSectionItem>()
    private var descTextRef: TextView? = null
    private var modulesContainer: LinearLayout? = null
    private var appsContainer: LinearLayout? = null
    private var modulesContent: LinearLayout? = null
    private var appsContent: LinearLayout? = null
    private var modulesHeader: TextView? = null
    private var appsHeader: TextView? = null
    private var modulesExpanded = true
    private var appsExpanded = false

    data class HideSectionItem(
        val key: String,
        val label: String,
        val detail: String,
        var selected: Boolean = false
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scrollView = ScrollView(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }
        scrollView.addView(root)

        val descText = TextView(requireContext()).apply {
            text = "勾选后，这些内容将在作用域内不可见。\n检测中..."
            textSize = 12f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setPadding(0, 0, 0, 12.dp)
        }
        descTextRef = descText
        root.addView(descText)

        val mSection = buildCollapsibleSection("模块", "检测中...") { modulesExpanded = it }
        modulesHeader = mSection.first
        modulesContent = mSection.second
        modulesContainer = mSection.third
        root.addView(modulesContainer)

        val aSection = buildCollapsibleSection("APP", "检测中...") { appsExpanded = it }
        appsHeader = aSection.first
        appsContent = aSection.second
        appsContainer = aSection.third
        root.addView(appsContainer)

        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12.dp, 0, 0)
        }
        btnRow.addView(Button(requireContext()).apply {
            text = "全选"
            textSize = 13f
            setOnClickListener { toggleAll(true) }
        })
        btnRow.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp, 1)
        })
        btnRow.addView(Button(requireContext()).apply {
            text = "取消全选"
            textSize = 13f
            setOnClickListener { toggleAll(false) }
        })
        btnRow.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp, 1)
        })
        btnRow.addView(Button(requireContext()).apply {
            text = "重新检测"
            textSize = 13f
            setOnClickListener { reloadDetection() }
        })
        root.addView(btnRow)

        loadHideItems()
        return scrollView
    }

    private fun buildCollapsibleSection(title: String, subtitle: String, onToggle: (Boolean) -> Unit): Triple<TextView, LinearLayout, LinearLayout> {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp
            }
        }

        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val headerText = TextView(requireContext()).apply {
            text = "$title ($subtitle)"
            textSize = 14f
            setTextColor(resources.getColor(R.color.hf_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val arrow = TextView(requireContext()).apply {
            text = "▼"
            textSize = 12f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
        }
        headerRow.addView(headerText)
        headerRow.addView(arrow)
        headerRow.setOnClickListener {
            val expanded = arrow.text == "▼"
            if (expanded) arrow.text = "▲" else arrow.text = "▼"
            onToggle(!expanded)
            updateSectionVisibility()
        }
        container.addView(headerRow)

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8.dp, 0, 0)
        }
        container.addView(content)

        return Triple(headerText, content, container)
    }

    private fun updateSectionVisibility() {
        modulesContent?.visibility = if (modulesExpanded) View.VISIBLE else View.GONE
        appsContent?.visibility = if (appsExpanded) View.VISIBLE else View.GONE
    }

    private fun loadHideItems() {
        Thread {
            SuSession.getInstance().waitForSession()
            if (!SuSessionProvider.checkRoot()) {
                activity?.runOnUiThread {
                    descTextRef?.text = "未获取 Root 权限，请返回重新打开此页面"
                }
                return@Thread
            }
            val (modules, apps) = detectItems()

            activity?.runOnUiThread {
                moduleItems.clear()
                moduleItems.addAll(modules)
                val selectedModuleKeys = RootHideRepository.getHiddenModuleKeys(requireContext())
                moduleItems.forEach { it.selected = it.key in selectedModuleKeys }
                modulesHeader?.text = "模块 (${moduleItems.size})"
                renderSectionItems(modulesContent, moduleItems)

                appItems.clear()
                appItems.addAll(apps)
                appItems.forEach { it.selected = it.key in selectedModuleKeys }
                appsHeader?.text = "APP (${appItems.size})"
                renderSectionItems(appsContent, appItems)

                val total = moduleItems.size + appItems.size
                val summary = if (total == 0) "未检测到模块或应用（设备可能非 Magisk/KernelSU/APatch）" else "已检测 模块 ${moduleItems.size} 项 + APP ${appItems.size} 项"
                descTextRef?.text = "勾选后，这些内容将在作用域内不可见。$summary。"
            }
        }.start()
    }

    private fun detectItems(): Pair<List<HideSectionItem>, List<HideSectionItem>> {
        val modules = mutableListOf<HideSectionItem>()
        val apps = mutableListOf<HideSectionItem>()

        val pathCheckScript = "for p in /system/bin/su /data/adb/magisk /data/adb/magisk.db /data/adb/ksu /data/adb/ap /data/adb/lspd /data/adb/lsp /data/adb/zygisk /dev/zygisk /debug_ramdisk /sbin/.magisk /system/etc/init/magisk /cache/.disable_magisk; do test -e \"\$p\" && echo \"\$p\"; done; for D in /data/adb/modules/*/module.prop; do test -f \"\$D\" && head -1 \"\$D\" 2>/dev/null; done"
        val raw = runRootCheck(pathCheckScript)
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isBlank()) continue
            if (t.startsWith("/") || t.startsWith("[")) {
                val entry = PATH_TO_DESC.firstOrNull { it.path == t }
                if (entry != null) {
                    modules.add(HideSectionItem(entry.key, "[路径] ${entry.label}", entry.path))
                }
            } else if (t.startsWith("name=")) {
                val name = t.substringAfter("name=").trim()
                if (name.isNotBlank() && name.length < 64) {
                    modules.add(HideSectionItem("mod_${name.hashCode().toUInt()}", "[模块] $name", "/data/adb/modules/"))
                }
            }
        }

        val knownCheckerApps = listOf(
            "com.chunqiunativecheck" to "春川 Native Check",
            "io.github.vvb2060.mahoshojo" to "Momo Root检测",
            "com.camel.corp.universalcopy" to "Ruru Root检测",
            "com.drnoob.datamonitor" to "Native Test",
            "icu.nullptr.nativetest" to "Native Root Detector",
            "com.scottyab.rootbeer.sample" to "RootBeer Sample"
        )
        for ((pkg, label) in knownCheckerApps) {
            val installed = runRootCheck("pm list packages '$pkg' 2>/dev/null | grep -q '$pkg' && echo YES || echo NO")
            if (installed == "YES") apps.add(HideSectionItem("app_$pkg", "[检测App] $label", pkg))
        }

        val thirdParty = runRootCheck("pm list packages -3 2>/dev/null | sed 's/^package://' | head -200")
        if (thirdParty.isNotBlank()) {
            val seen = apps.mapTo(mutableSetOf()) { it.key.substringAfter("app_") }
            for (pkg in thirdParty.trim().lines().map { it.trim() }.filter { it.isNotBlank() && it !in seen }) {
                if (pkg.length < 128) {
                    apps.add(HideSectionItem("app_$pkg", "$pkg", pkg))
                }
            }
        }

        return modules to apps
    }

    private data class PathDesc(val key: String, val label: String, val path: String)

    private val PATH_TO_DESC = listOf(
        PathDesc("su_binary", "su 二进制", "/system/bin/su"),
        PathDesc("magisk_dir", "Magisk 目录", "/data/adb/magisk"),
        PathDesc("magisk_db", "Magisk DB", "/data/adb/magisk.db"),
        PathDesc("kernelsu_dir", "KernelSU 目录", "/data/adb/ksu"),
        PathDesc("apatch_dir", "APatch 目录", "/data/adb/ap"),
        PathDesc("lspd_dir", "LSPosed 守护", "/data/adb/lspd"),
        PathDesc("lsp_dir", "LSPosed", "/data/adb/lsp"),
        PathDesc("zygisk_dir", "Zygisk", "/data/adb/zygisk"),
        PathDesc("dev_zygisk", "Zygisk 节点", "/dev/zygisk"),
        PathDesc("debug_ramdisk", "调试 ramdisk", "/debug_ramdisk"),
        PathDesc("sbin_magisk", "Magisk sbin", "/sbin/.magisk"),
        PathDesc("etc_magisk", "Magisk init", "/system/etc/init/magisk"),
        PathDesc("disable_magisk", "Magisk 禁用", "/cache/.disable_magisk")
    )

    private fun renderSectionItems(container: LinearLayout?, items: List<HideSectionItem>) {
        container ?: return
        container.removeAllViews()
        for (item in items) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4.dp, 0, 4.dp)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(requireContext()).apply {
                isChecked = item.selected
                setOnCheckedChangeListener { _, checked ->
                    item.selected = checked
                    persistSelection()
                }
            }
            row.addView(cb)
            val labelLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8.dp, 0, 0, 0)
            }
            labelLayout.addView(TextView(requireContext()).apply {
                text = item.label
                textSize = 13f
                setTextColor(resources.getColor(R.color.hf_text_primary, null))
            })
            labelLayout.addView(TextView(requireContext()).apply {
                text = item.detail
                textSize = 10f
                setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            })
            row.addView(labelLayout)
            container.addView(row)
        }
    }

    private fun runRootCheck(cmd: String): String {
        return SuSessionProvider.execute(cmd, 8)
    }

    private fun reloadDetection() {
        descTextRef?.text = "检测中..."
        moduleItems.clear()
        appItems.clear()
        modulesContent?.removeAllViews()
        appsContent?.removeAllViews()
        modulesHeader?.text = "模块 (检测中...)"
        appsHeader?.text = "APP (检测中...)"
        loadHideItems()
    }

    private fun toggleAll(select: Boolean) {
        moduleItems.forEach { it.selected = select }
        appItems.forEach { it.selected = select }
        persistSelection()
        renderSectionItems(modulesContent, moduleItems)
        renderSectionItems(appsContent, appItems)
    }

    fun persistSelection() {
        val keys = (moduleItems + appItems).filter { it.selected }.map { it.key }.toSet()
        RootHideRepository.setHiddenModuleKeys(requireContext(), keys)
    }

    fun getSelectedModules(): List<HideSectionItem> = moduleItems.filter { it.selected }
    fun getSelectedApps(): List<HideSectionItem> = appItems.filter { it.selected }

    override fun onDestroyView() {
        descTextRef = null
        modulesContainer = null
        appsContainer = null
        modulesContent = null
        appsContent = null
        modulesHeader = null
        appsHeader = null
        super.onDestroyView()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

object SuSessionProvider {
    private val session get() = com.HanFeng.adblocker.shizuku.SuSession.getInstance()

    fun checkRoot(): Boolean {
        if (session.isSessionOpen()) return true
        return session.open(30)
    }

    fun execute(cmd: String, timeoutSeconds: Long): String {
        if (!session.isSessionOpen()) session.open(timeoutSeconds)
        val result = session.execute(cmd, timeoutSeconds)
        return result.output.trim()
    }
}
