package com.HanFeng.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.HanFeng.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.HanFeng.adblocker.shizuku.PropDisguiseManager
import com.HanFeng.adblocker.shizuku.RootHideAppWatcher
import com.HanFeng.adblocker.shizuku.RootHideManager
import com.HanFeng.adblocker.shizuku.RootHideRepository
import com.HanFeng.adblocker.shizuku.SuSession

class RootHideActivity : BaseActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var tvResult: TextView
    private lateinit var tvRootSolution: TextView
    private lateinit var tvHideStatus: TextView
    private lateinit var btnApply: Button
    private lateinit var switchProp: Switch
    private lateinit var switchAutoWatcher: Switch
    private lateinit var modulesFragment: RootHideModulesFragment
    private lateinit var scopeFragment: RootHideScopeFragment

    private var cachedPreCheck: RootHideManager.PreCheckResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_root_hide)

        val rootView = findViewById<android.view.View>(R.id.rootHideRoot)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16.dp, systemBars.top + 8.dp, 16.dp, systemBars.bottom + 8.dp)
            insets
        }

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        tvResult = findViewById(R.id.tvHideResult)
        tvRootSolution = findViewById(R.id.tvRootSolution)
        tvHideStatus = findViewById(R.id.tvHideStatus)
        btnApply = findViewById(R.id.btnApplyHide)
        switchProp = findViewById(R.id.switchPropDisguise)
        switchAutoWatcher = findViewById(R.id.switchAutoWatcher)

        modulesFragment = RootHideModulesFragment()
        scopeFragment = RootHideScopeFragment()

        viewPager.adapter = HidePagerAdapter(this, modulesFragment, scopeFragment)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "隐藏内容" else "作用域"
        }.attach()

        btnApply.setOnClickListener { applyHiding() }
        switchProp.setOnCheckedChangeListener { _, checked ->
            handlePropToggle(checked)
        }
        switchAutoWatcher.setOnCheckedChangeListener { _, checked ->
            handleAutoWatcherToggle(checked)
        }
        findViewById<Button>(R.id.btnPresetBank).setOnClickListener { applyPreset("Bank/Pay") }
        findViewById<Button>(R.id.btnPresetGame).setOnClickListener { applyPreset("Game") }
        findViewById<Button>(R.id.btnPresetSocial).setOnClickListener { applyPreset("Social") }
        findViewById<Button>(R.id.btnPresetVideo).setOnClickListener { applyPreset("ShortVideo") }
        findViewById<Button>(R.id.btnPresetAll).setOnClickListener { applyPreset("All") }
        findViewById<Button>(R.id.btnPresetClear).setOnClickListener { clearScope() }

        checkRootStatus()
    }

    private fun checkRootStatus() {
        Thread {
            val hasRoot = SuSession.getInstance().open(30)
            runOnUiThread {
                if (!hasRoot) {
                    tvResult.text = "Root 权限未授予"
                    btnApply.isEnabled = false
                    switchProp.isEnabled = false
                    switchAutoWatcher.isEnabled = false
                    Toast.makeText(this, "请在弹窗中授予 Root 权限", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // 跑 preCheck 同时刷新 UI
                refreshStatus()
                // 还原开关状态
                switchProp.isChecked = RootHideRepository.isPropDisguiseEnabled(this)
                switchAutoWatcher.isChecked = RootHideRepository.isAutoWatcherEnabled(this)
            }
        }.start()
    }

    private fun refreshStatus() {
        Thread {
            val mgr = RootHideManager()
            val pc = mgr.preCheck()
            cachedPreCheck = pc
            val sb = StringBuilder()
            sb.append("Root 方案: ${pc.rootSolution}\n")
            sb.append("Zygisk: ${if (pc.zygiskEnabled) "已启用" else "未启用"}\n")
            sb.append("Zygisk Next 模块: ${if (pc.zygiskNextDetected) "已安装" else "未安装"}\n")
            sb.append("DenyList 可用: ${if (pc.magiskDenyListAvailable) "是" else "否"}\n")
            sb.append("系统挂载: ${if (pc.systemMountable) "可" else "不可（EROFS / 只读）"}\n")
            sb.append("路径数: ${pc.mountablePaths.size + pc.readonlyPaths.size}\n")
            if (pc.recommendations.isNotEmpty()) {
                sb.append("\n建议:\n")
                for (r in pc.recommendations.take(4)) {
                    sb.append("· $r\n")
                }
            }
            // 当前隐藏状态
            val status = mgr.getHiddenStatus()
            sb.append("\n已隐藏: DenyList=${status.magiskDenyListCount}  进程=${status.processHiddenCount}  路径=${status.hiddenPathCount}")
            runOnUiThread {
                tvRootSolution.text = pc.rootSolution
                tvHideStatus.text = sb.toString()
            }
            // prop 伪装状态
            val propStatus = PropDisguiseManager.status()
            val propOn = RootHideRepository.isPropDisguiseEnabled(this)
            if (propOn && propStatus.state == PropDisguiseManager.DisguiseState.NOT_APPLIED) {
                // 用户之前启用了但 prop 没生效（重启失效），自动重新应用
                PropDisguiseManager.apply()
            }
        }.start()
    }

    private fun handlePropToggle(checked: Boolean) {
        RootHideRepository.setPropDisguiseEnabled(this, checked)
        Thread {
            if (checked) {
                val r = PropDisguiseManager.apply()
                runOnUiThread {
                    Toast.makeText(this, if (r.success) r.detail else "Prop 伪装失败", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            } else {
                val ok = PropDisguiseManager.restore()
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Prop 已还原" else "Prop 还原失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleAutoWatcherToggle(checked: Boolean) {
        RootHideRepository.setAutoWatcherEnabled(this, checked)
        Thread {
            if (checked) {
                val scope = RootHideRepository.getScopePackages(this)
                if (scope.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "请先在「作用域」中勾选要隐藏的应用", Toast.LENGTH_LONG).show()
                        switchAutoWatcher.isChecked = false
                        RootHideRepository.setAutoWatcherEnabled(this, false)
                    }
                    return@Thread
                }
                val ok = RootHideAppWatcher.start(this, scope)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "已启动后台监听，目标 App 启动后自动隐藏" else "启动失败", Toast.LENGTH_LONG).show()
                }
            } else {
                RootHideAppWatcher.stop()
                runOnUiThread {
                    Toast.makeText(this, "已停止后台监听", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun applyPreset(category: String) {
        Thread {
            val pm = packageManager
            @Suppress("DEPRECATION")
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DISABLED_COMPONENTS
            val allInstalled = pm.getInstalledApplications(flags)
                .asSequence()
                .filter { it.packageName != packageName }
                .map { it.packageName }
                .toSet()

            val matched = when (category) {
                "Bank/Pay" -> allInstalled.filterTo(mutableSetOf()) { pkg ->
                    RootHideRepository.Presets.BANK_PAY_PREFIXES.any { pkg == it || pkg.startsWith(it) }
                }
                "Game" -> allInstalled.filterTo(mutableSetOf()) { pkg ->
                    RootHideRepository.Presets.GAME_PREFIXES.any { pkg == it || pkg.startsWith(it) }
                }
                "Social" -> allInstalled.filterTo(mutableSetOf()) { pkg ->
                    RootHideRepository.Presets.SOCIAL_PREFIXES.any { pkg == it || pkg.startsWith(it) }
                }
                "ShortVideo" -> allInstalled.filterTo(mutableSetOf()) { pkg ->
                    RootHideRepository.Presets.SHORT_VIDEO_PREFIXES.any { pkg == it || pkg.startsWith(it) }
                }
                "All" -> RootHideRepository.Presets.filterInteresting(allInstalled)
                else -> emptySet()
            }
            // 与现有作用域合并
            val current = RootHideRepository.getScopePackages(this).toMutableSet()
            current += matched
            RootHideRepository.replaceScopePackages(this, current)
            runOnUiThread {
                Toast.makeText(this, "已加入作用域: ${matched.size} 个 ${labelForPreset(category)}", Toast.LENGTH_LONG).show()
                scopeFragment.reloadFromOutside()
            }
        }.start()
    }

    private fun clearScope() {
        Thread {
            RootHideRepository.replaceScopePackages(this, emptySet())
            runOnUiThread {
                Toast.makeText(this, "作用域已清空", Toast.LENGTH_SHORT).show()
                scopeFragment.reloadFromOutside()
            }
        }.start()
    }

    private fun labelForPreset(category: String): String = when (category) {
        "Bank/Pay" -> "银行/支付"
        "Game" -> "游戏"
        "Social" -> "社交"
        "ShortVideo" -> "短视频"
        "All" -> "智能作用域"
        else -> category
    }

    private fun applyHiding() {
        val scopePackages = scopeFragment.getSelectedScopePackages()

        if (scopePackages.isEmpty()) {
            Toast.makeText(this, "请先在「作用域」中勾选目标应用（或使用一键预设）", Toast.LENGTH_SHORT).show()
            viewPager.currentItem = 1
            return
        }

        tvResult.text = "正在应用隐藏... (共 ${scopePackages.size} 个应用)"
        btnApply.isEnabled = false

        Thread {
            val hideManager = RootHideManager()
            val results = hideManager.hideFromAllSelectedPackages(scopePackages)
            val successCount = results.count { it.success }

            val sb = StringBuilder()
            sb.appendLine("隐藏完成: $successCount/${results.size}")
            results.take(20).forEach { r ->
                sb.appendLine(if (r.success) "OK ${r.packageName}: ${r.detail}" else "FAIL ${r.packageName}: ${r.detail}")
            }
            if (results.size > 20) sb.appendLine("... 共 ${results.size} 项")

            // 应用完成后自动启用 watcher（如果开关打开）
            if (RootHideRepository.isAutoWatcherEnabled(this)) {
                RootHideAppWatcher.refreshScope(this, scopePackages)
            }

            runOnUiThread {
                tvResult.text = sb.toString()
                btnApply.isEnabled = true
                Toast.makeText(this, "完成: $successCount/${results.size}", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }.start()
    }

    private inner class HidePagerAdapter(
        activity: FragmentActivity,
        private val modulesFrag: RootHideModulesFragment,
        private val scopeFrag: RootHideScopeFragment
    ) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment {
            return if (position == 0) modulesFrag else scopeFrag
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
