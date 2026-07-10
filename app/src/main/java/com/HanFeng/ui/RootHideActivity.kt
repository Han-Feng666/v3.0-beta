package com.HanFeng.ui

import android.os.Bundle
import android.widget.Button
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
import com.HanFeng.adblocker.shizuku.RootHideManager
import com.HanFeng.adblocker.shizuku.SuSession

class RootHideActivity : BaseActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var tvResult: TextView
    private lateinit var btnApply: Button
    private lateinit var modulesFragment: RootHideModulesFragment
    private lateinit var scopeFragment: RootHideScopeFragment

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
        btnApply = findViewById(R.id.btnApplyHide)

        modulesFragment = RootHideModulesFragment()
        scopeFragment = RootHideScopeFragment()

        viewPager.adapter = HidePagerAdapter(this, modulesFragment, scopeFragment)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "隐藏内容" else "作用域"
        }.attach()

        btnApply.setOnClickListener {
            applyHiding()
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
                    Toast.makeText(this, "请在弹窗中授予 Root 权限", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun applyHiding() {
        val selectedModules = modulesFragment.getSelectedModules()
        val selectedApps = modulesFragment.getSelectedApps()
        val scopePackages = scopeFragment.getSelectedScopePackages()

        if (scopePackages.isEmpty()) {
            Toast.makeText(this, "请先在「作用域」中勾选目标应用", Toast.LENGTH_SHORT).show()
            viewPager.currentItem = 1
            return
        }

        tvResult.text = "正在应用隐藏..."
        btnApply.isEnabled = false

        Thread {
            val hideManager = RootHideManager()
            val results = hideManager.hideFromAllSelectedPackages(scopePackages)
            val successCount = results.count { it.success }

            val sb = StringBuilder()
            sb.appendLine("隐藏完成: $successCount/${results.size}")
            results.forEach { r ->
                sb.appendLine(if (r.success) "OK ${r.packageName}: ${r.detail}" else "FAIL ${r.packageName}: ${r.detail}")
            }

            runOnUiThread {
                tvResult.text = sb.toString()
                btnApply.isEnabled = true
                Toast.makeText(this, "完成: $successCount/${results.size}", Toast.LENGTH_SHORT).show()
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
