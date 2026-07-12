package com.HanFeng.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.HanFeng.R
import com.HanFeng.model.InstalledApp
import com.HanFeng.adblocker.shizuku.RootHideRepository

class RootHideScopeFragment : Fragment() {

    private var allApps: List<InstalledApp> = emptyList()
    private var loadThread: Thread? = null
    private var loadVersion = 0
    private val handler = Handler(Looper.getMainLooper())
    private var searchInput: EditText? = null
    private var countText: TextView? = null
    private var appsContainer: LinearLayout? = null
    private var currentKeyword: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scrollView = ScrollView(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }
        scrollView.addView(root)

        root.addView(TextView(requireContext()).apply {
            text = "选择作用域 (对哪些应用隐藏)"
            textSize = 14f
            setTextColor(resources.getColor(R.color.hf_text_primary, null))
            setPadding(0, 0, 0, 8.dp)
        })

        searchInput = EditText(requireContext()).apply {
            hint = "搜索应用名或包名"
            textSize = 14f
            setTextColor(resources.getColor(R.color.hf_text_primary, null))
            setHintTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setSingleLine(true)
        }
        root.addView(searchInput!!)

        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
        }
        btnRow.addView(Button(requireContext()).apply {
            text = "全选可见"
            textSize = 13f
            setOnClickListener { toggleScope(true) }
        })
        btnRow.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp, 1)
        })
        btnRow.addView(Button(requireContext()).apply {
            text = "反选可见"
            textSize = 13f
            setOnClickListener { toggleScope(false, invert = true) }
        })
        root.addView(btnRow)

        val ct = TextView(requireContext()).apply {
            text = "已选: 0 个应用"
            textSize = 12f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setPadding(0, 4.dp, 0, 4.dp)
        }
        this.countText = ct
        root.addView(ct)

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        this.appsContainer = container
        root.addView(container)

        searchInput!!.doAfterTextChanged {
            currentKeyword = it?.toString().orEmpty()
            renderList()
        }

        loadApps()
        return scrollView
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            loadApps()
        }
    }

    private fun loadApps() {
        loadVersion++
        val requestVersion = loadVersion
        loadThread?.interrupt()
        val ctx = context ?: return
        val pm = ctx.packageManager
        val selfPkg = ctx.packageName
        loadThread = Thread {
            try {
                com.HanFeng.adblocker.shizuku.SuSession.getInstance().waitForSession(30)
                if (!SuSessionProvider.checkRoot()) {
                    handler.post {
                        if (requestVersion != loadVersion || isRemoving || isDetached) return@post
                        countText?.text = "未获取 Root 权限，请返回重新打开此页面"
                    }
                    return@Thread
                }
                val scopePackages = RootHideRepository.getScopePackages(ctx)
                @Suppress("DEPRECATION")
                val pkgFlags = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS

                val thirdPartyRaw = SuSessionProvider.execute("pm list packages -3 2>/dev/null | sed 's/^package://'", 15)

                val processed = mutableListOf<InstalledApp>()
                if (thirdPartyRaw.isNotBlank()) {
                    for (pkg in thirdPartyRaw.trim().lines().map { it.trim() }.filter { it.isNotBlank() && it != selfPkg }.distinct()) {
                        if (pkg.length >= 128) continue
                        val appInfo = runCatching { pm.getApplicationInfo(pkg, pkgFlags) }.getOrNull() ?: continue
                        val dispLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                        val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                        processed.add(
                            InstalledApp(
                                label = dispLabel,
                                packageName = pkg,
                                icon = icon,
                                whitelisted = false,
                                coexistSelected = false,
                                coexistRecommended = false,
                                rootHideSelected = pkg in scopePackages
                            )
                        )
                    }
                }
                if (processed.isEmpty()) {
                    val allRaw = SuSessionProvider.execute("pm list packages 2>/dev/null | sed 's/^package://'", 15)
                    for (pkg in allRaw.trim().lines().map { it.trim() }.filter { it.isNotBlank() && it != selfPkg }.distinct()) {
                        if (pkg.length >= 128) continue
                        val appInfo = runCatching { pm.getApplicationInfo(pkg, pkgFlags) }.getOrNull() ?: continue
                        val dispLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                        val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                        processed.add(
                            InstalledApp(
                                label = dispLabel,
                                packageName = pkg,
                                icon = icon,
                                whitelisted = false,
                                coexistSelected = false,
                                coexistRecommended = false,
                                rootHideSelected = pkg in scopePackages
                            )
                        )
                    }
                }
                val apps = processed.sortedBy { it.label.lowercase() }
                if (requestVersion != loadVersion) return@Thread
                handler.post {
                    if (requestVersion != loadVersion || isRemoving || isDetached) return@post
                    allApps = apps
                    renderList()
                    updateCount()
                    if (apps.isEmpty()) {
                        countText?.text = "未发现可勾选的应用。请确认已授予 Root 权限，或设备无第三方 App。"
                    }
                }
            } catch (e: Exception) {
                if (requestVersion != loadVersion) return@Thread
                handler.post {
                    if (requestVersion != loadVersion || isRemoving || isDetached) return@post
                    Toast.makeText(ctx, "加载应用列表失败: ${e.message}", Toast.LENGTH_LONG).show()
                    countText?.text = "加载失败: ${e.message}"
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun filteredApps(): List<InstalledApp> {
        val normalized = currentKeyword.trim().lowercase()
        return if (normalized.isBlank()) allApps
        else allApps.filter { it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized) }
    }

    private fun renderList() {
        val container = appsContainer ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val visible = filteredApps()
        for (item in visible) {
            val binding = com.HanFeng.databinding.ItemAppBinding.inflate(inflater, container, false)
            binding.loadingIndicator.visibility = View.GONE
            binding.appIcon.setImageDrawable(item.icon)
            binding.appName.text = item.label
            binding.packageName.text = item.packageName
            binding.whitelistBox.setOnCheckedChangeListener(null)
            binding.whitelistBox.isChecked = item.rootHideSelected
            binding.whitelistBox.setOnCheckedChangeListener { _, checked ->
                RootHideRepository.toggleScope(requireContext(), item.packageName, checked)
                updateAppState(item.packageName, checked)
            }
            container.addView(binding.root)
        }
        if (visible.isEmpty() && allApps.isNotEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "搜索无结果"
                setPadding(0, 12.dp, 0, 12.dp)
                setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            }
            container.addView(tv)
        }
    }

    private fun updateAppState(packageName: String, checked: Boolean) {
        allApps = allApps.map { if (it.packageName != packageName) it else it.copy(rootHideSelected = checked) }
        updateCount()
    }

    private fun toggleScope(selectAll: Boolean, invert: Boolean = false) {
        val visible = filteredApps()
        if (visible.isEmpty()) return
        val updated = mutableSetOf<String>()
        visible.forEach { app ->
            val target = if (invert) !app.rootHideSelected else selectAll
            if (target) updated += app.packageName
        }
        val base = RootHideRepository.getScopePackages(requireContext()).toMutableSet()
        visible.forEach { base.remove(it.packageName) }
        base += updated
        RootHideRepository.replaceScopePackages(requireContext(), base)

        val visibleNames = visible.mapTo(hashSetOf()) { it.packageName }
        allApps = allApps.map {
            if (it.packageName !in visibleNames) it
            else it.copy(rootHideSelected = it.packageName in updated)
        }
        renderList()
        updateCount()
        Toast.makeText(requireContext(), if (invert) "已反选" else "已全选", Toast.LENGTH_SHORT).show()
    }

    fun getSelectedScopePackages(): Set<String> {
        return allApps.filter { it.rootHideSelected }.map { it.packageName }.toSet()
    }

    fun reloadFromOutside() {
        if (context == null) return
        loadApps()
    }

    private fun updateCount() {
        val count = allApps.count { it.rootHideSelected }
        countText?.text = "已选: $count 个应用"
    }

    override fun onDestroyView() {
        loadThread?.interrupt()
        searchInput = null
        countText = null
        appsContainer = null
        super.onDestroyView()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
