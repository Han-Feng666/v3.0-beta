package com.HanFeng.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.model.InstalledApp
import com.HanFeng.adblocker.shizuku.RootHideRepository
import com.HanFeng.adblocker.shizuku.SuSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RootHideScopeFragment : Fragment() {

    private val adapter = AppListAdapter(
        checkedSelector = { it.rootHideSelected }
    ) { app, checked ->
        RootHideRepository.toggleScope(requireContext(), app.packageName, checked)
        updateAppState(app.packageName, checked)
    }

    private var allApps: List<InstalledApp> = emptyList()
    private var loadJob: Job? = null
    private var loadVersion = 0
    private var searchInput: EditText? = null
    private var countText: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }

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

        val countText = TextView(requireContext()).apply {
            text = "已选: 0 个应用"
            textSize = 12f
            setTextColor(resources.getColor(R.color.hf_text_secondary, null))
            setPadding(0, 4.dp, 0, 4.dp)
        }
        this.countText = countText
        root.addView(countText)

        val appList = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RootHideScopeFragment.adapter
            setBackgroundResource(R.drawable.bg_panel)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(appList, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f })

        searchInput!!.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        loadApps()
        return root
    }

    private fun loadApps() {
        loadVersion++
        val requestVersion = loadVersion
        loadJob?.cancel()
        val ctx = context ?: return
        val pm = ctx.packageManager
        val selfPkg = ctx.packageName
        loadJob = lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.Default) {
                    val scopePackages = RootHideRepository.getScopePackages(ctx)
                    @Suppress("DEPRECATION")
                    val flags = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS or
                        android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                    val visiblePackages = pm.getInstalledPackages(flags)
                        .asSequence()
                        .filter { it.packageName != selfPkg && it.applicationInfo != null }
                        .map { info ->
                            InstalledApp(
                                label = runCatching { info.applicationInfo!!.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                                packageName = info.packageName,
                                icon = runCatching { info.applicationInfo!!.loadIcon(pm) }.getOrNull(),
                                whitelisted = false,
                                coexistSelected = false,
                                coexistRecommended = false,
                                rootHideSelected = info.packageName in scopePackages
                            )
                        }
                        .toList()

                    val thirdPartyVisible = visiblePackages.count {
                        (it.rootHideSelected || true) && runCatching {
                            val ai = pm.getApplicationInfo(it.packageName, 0)
                            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                        }.getOrDefault(false)
                    }
                    val rootPackages = if (thirdPartyVisible <= 1) loadThirdPartyPackagesViaRoot() else emptyList()
                    val visibleNames = visiblePackages.mapTo(hashSetOf()) { it.packageName }
                    val rootOnlyApps = rootPackages
                        .asSequence()
                        .filter { it != selfPkg && it !in visibleNames }
                        .map { pkg ->
                            val appInfo = runCatching { pm.getApplicationInfo(pkg, flags) }.getOrNull()
                            InstalledApp(
                                label = runCatching { appInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: pkg,
                                packageName = pkg,
                                icon = runCatching { appInfo?.loadIcon(pm) }.getOrNull(),
                                whitelisted = false,
                                coexistSelected = false,
                                coexistRecommended = false,
                                rootHideSelected = pkg in scopePackages
                            )
                        }
                        .toList()

                    (visiblePackages + rootOnlyApps).distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
                }
                if (requestVersion != loadVersion) return@launch
                allApps = apps
                applyFilter(searchInput?.text?.toString().orEmpty())
                updateCount()
                if (apps.isEmpty()) {
                    countText?.text = "未发现可勾选的应用。请确认已授予应用列表权限（已声明 QUERY_ALL_PACKAGES），或设备无第三方 App。"
                }
            } catch (e: Exception) {
                if (requestVersion != loadVersion) return@launch
                activity?.runOnUiThread {
                    Toast.makeText(ctx, "加载应用列表失败: ${e.message}", Toast.LENGTH_LONG).show()
                    countText?.text = "加载失败: ${e.message}"
                }
            }
        }
    }

    private fun applyFilter(keyword: String) {
        val normalized = keyword.trim().lowercase()
        val filtered = if (normalized.isBlank()) allApps
        else allApps.filter { it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized) }
        adapter.submit(filtered)
    }

    private fun loadThirdPartyPackagesViaRoot(): List<String> {
        return runCatching {
            val session = SuSession.getInstance()
            if (!session.isSessionOpen()) session.open(10)
            val result = session.execute("pm list packages -3 2>/dev/null | sed 's/^package://'", 10)
            result.output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains('.') }
                .distinct()
                .take(500)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun updateAppState(packageName: String, checked: Boolean) {
        allApps = allApps.map { if (it.packageName != packageName) it else it.copy(rootHideSelected = checked) }
        applyFilter(searchInput?.text?.toString().orEmpty())
        updateCount()
    }

    private fun toggleScope(selectAll: Boolean, invert: Boolean = false) {
        val visible = adapter.currentList
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
        applyFilter(searchInput?.text?.toString().orEmpty())
        updateCount()
        Toast.makeText(requireContext(), if (invert) "已反选" else "已全选", Toast.LENGTH_SHORT).show()
    }

    fun getSelectedScopePackages(): Set<String> {
        return allApps.filter { it.rootHideSelected }.map { it.packageName }.toSet()
    }

    /** 允许外部（RootHideActivity）触发作用域重新加载。 */
    fun reloadFromOutside() {
        if (context == null) return
        loadApps()
    }

    private fun updateCount() {
        val count = allApps.count { it.rootHideSelected }
        countText?.text = "已选: $count 个应用"
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        searchInput = null
        countText = null
        super.onDestroyView()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
