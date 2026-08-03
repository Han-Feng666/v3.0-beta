package com.HanFeng.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> RulesFragment()
            1 -> HomeFragment()
            2 -> StatsFragment()
            // 第 4 个主 Tab: 抓包模块(design 现有组件改动 MainPagerAdapter 表; requirements R1.1)
            else -> com.HanFeng.ui.capture.CaptureFragment()
        }
    }
}
