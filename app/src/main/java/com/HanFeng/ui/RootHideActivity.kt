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
        findViewById<Button>(R.id.btnUnhide).setOnClickListener { unhideAll() }
        findViewById<Button>(R.id.btnWatcherLog).setOnClickListener { showWatcherLog() }
        findViewById<Button>(R.id.btnPresetAll).setOnClickListener { selectAllThirdParty() }
        findViewById<Button>(R.id.btnPresetClear).setOnClickListener { clearScope() }
        switchProp.setOnCheckedChangeListener { _, checked ->
            handlePropToggle(checked)
        }
        switchAutoWatcher.setOnCheckedChangeListener { _, checked ->
            handleAutoWatcherToggle(checked)
        }

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

    private fun selectAllThirdParty() {
        Thread {
            val scopeFragmentView = scopeFragment
            val pm = packageManager
            @Suppress("DEPRECATION")
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DISABLED_COMPONENTS
            val allThirdParty = pm.getInstalledApplications(flags)
                .asSequence()
                .filter { it.packageName != packageName }
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName }
                .toSet()
            RootHideRepository.replaceScopePackages(this, allThirdParty)
            runOnUiThread {
                Toast.makeText(this, "已全选 ${allThirdParty.size} 个第三方应用", Toast.LENGTH_LONG).show()
                scopeFragmentView.reloadFromOutside()
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

    private fun unhideAll() {
        tvResult.text = "正在解除所有隐藏..."
        Thread {
            val mgr = RootHideManager()
            val ok = mgr.unhideAll()
            PropDisguiseManager.restore()
            RootHideAppWatcher.stop()
            runOnUiThread {
                tvResult.text = if (ok) "已解除所有隐藏（DenyList + 进程 + 系统路径 + Prop 伪装）" else "部分解除失败，请查看日志"
                Toast.makeText(this, if (ok) "已全部解除" else "部分失败", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }.start()
    }

    private fun showWatcherLog() {
        Thread {
            val status = RootHideAppWatcher.status()
            val log = RootHideAppWatcher.dumpLog(100)
            runOnUiThread {
                val sb = StringBuilder()
                sb.append("=== Watcher 状态 ===\n")
                sb.append("运行中: ${if (status.running) "是 (PID=${status.pid})" else "否"}\n")
                sb.append("作用域: ${status.scopePackages.size} 个\n")
                sb.append("已处理 PID: ${status.handledPids}\n\n")
                sb.append("=== 最近日志 ===\n")
                sb.append(log)
                tvResult.text = sb.toString()
            }
        }.start()
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

            // 应用完成后自动启用 watcher（如果开关已打开）
            if (RootHideRepository.isAutoWatcherEnabled(this)) {
                RootHideAppWatcher.refreshScope(this, scopePackages)
            } else if (scopePackages.isNotEmpty()) {
                // 开关未打开但 scope 非空，自动打开并启动
                RootHideRepository.setAutoWatcherEnabled(this, true)
                RootHideAppWatcher.start(this, scopePackages)
                runOnUiThread {
                    switchAutoWatcher.isChecked = true
                    Toast.makeText(this, "已自动开启后台监听", Toast.LENGTH_SHORT).show()
                }
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
