package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.HanFeng.databinding.ItemGuideSectionBinding
import com.HanFeng.databinding.ActivityGuideBinding

class GuideActivity : BaseActivity() {
    private lateinit var binding: ActivityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val initialTopPadding = binding.guideRoot.paddingTop
        val initialBottomPadding = binding.guideRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.guideRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top + initialTopPadding, view.paddingRight, systemBars.bottom + initialBottomPadding)
            insets
        }
        val title = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { "使用说明" } ?: "使用说明"
        val content = intent.getStringExtra(EXTRA_CONTENT)?.ifBlank { DEFAULT_GUIDE_CONTENT } ?: DEFAULT_GUIDE_CONTENT
        binding.titleText.text = title
        if (title == "使用说明") {
            showCollapsibleGuideSections(content)
        } else {
            binding.sectionsContainer.visibility = View.GONE
            binding.contentText.visibility = View.VISIBLE
            binding.contentText.text = content
        }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun showCollapsibleGuideSections(content: String) {
        val parsed = parseGuideContent(content)
        binding.contentText.text = parsed.headerTitle
        binding.contentText.visibility = View.VISIBLE
        if (parsed.headerSubtitle.isNotBlank()) {
            binding.contentSubText.visibility = View.VISIBLE
            binding.contentSubText.text = parsed.headerSubtitle
        } else {
            binding.contentSubText.visibility = View.GONE
            binding.contentSubText.text = ""
        }
        val sections = parsed.sections
        if (sections.isEmpty()) {
            binding.contentSubText.visibility = View.GONE
            binding.sectionsContainer.visibility = View.GONE
            binding.contentText.visibility = View.VISIBLE
            binding.contentText.text = content
            return
        }
        binding.sectionsContainer.visibility = View.VISIBLE
        binding.sectionsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        sections.forEach { section ->
            val itemBinding = ItemGuideSectionBinding.inflate(inflater, binding.sectionsContainer, false)
            itemBinding.sectionTitle.text = section.title
            itemBinding.sectionContent.text = section.content
            itemBinding.sectionContent.visibility = View.GONE
            itemBinding.sectionIndicator.text = "展开"
            itemBinding.headerRow.setOnClickListener {
                val expanded = itemBinding.sectionContent.visibility == View.VISIBLE
                itemBinding.sectionContent.visibility = if (expanded) View.GONE else View.VISIBLE
                itemBinding.sectionIndicator.text = if (expanded) "展开" else "收起"
            }
            binding.sectionsContainer.addView(itemBinding.root)
        }
    }

    private fun parseGuideContent(content: String): GuideContent {
        val normalized = content.replace("\r\n", "\n")
        val lines = normalized.lines()
        val sections = mutableListOf<GuideSection>()
        val headerLines = mutableListOf<String>()
        var currentTitle: String? = null
        val currentBody = StringBuilder()

        fun flush() {
            val title = currentTitle ?: return
            sections += GuideSection(title = title, content = currentBody.toString().trim())
            currentTitle = null
            currentBody.clear()
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (sectionTitleRegex.matches(line.trim())) {
                flush()
                currentTitle = line.trim()
            } else if (currentTitle != null) {
                if (currentBody.isNotEmpty()) currentBody.append('\n')
                currentBody.append(line)
            } else if (line.isNotBlank()) {
                headerLines += line.trim()
            }
        }
        flush()
        val filteredSections = sections.filter { it.title.isNotBlank() && it.content.isNotBlank() }
        val headerTitle = headerLines.getOrNull(0).orEmpty()
        val headerSubtitle = headerLines.drop(1).joinToString("\n")
        return GuideContent(
            headerTitle = headerTitle,
            headerSubtitle = headerSubtitle,
            sections = filteredSections
        )
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_CONTENT = "extra_content"
        private val sectionTitleRegex = Regex("^[一二三四五六七八九十]+、.+")
        private val DEFAULT_GUIDE_CONTENT =
            "APP作者：寒枫，酷安ID：寒枫颜值担当\n" +
            "本说明覆盖首页、规则页、统计页、MITM、Shizuku、可疑域名、加速器共存、规则导入与排障流程。\n" +
            "建议先完整看一遍首次使用、MITM 教程、规则支持范围和常见问题，再按自己的机型和需求选择功能组合。\n\n" +
            "一、首次使用完整教程\n" +
            "1. 打开应用后先停留在首页，确认状态卡可正常显示。\n" +
            "2. 点击 \"开启拦截\"，系统会弹出 VPN 授权，点击允许。\n" +
            "3. Android 13 及以上建议允许通知权限，方便前台服务稳定显示。\n" +
            "4. 若黑白名单、加速器共存或应用识别结果不完整，请到系统设置里手动允许应用列表相关权限。\n" +
            "5. 第一次建议先只用 DNS 拦截确认联网正常，再按需开启 MITM 模式。\n" +
            "6. 如果你需要更强的系统推广治理和连接归属增强，再去设置里开启 Shizuku 增强。\n\n" +
            "二、首页所有功能说明\n" +
            "1. 开启拦截 / 停止拦截：启动或停止寒枫本地 VPN 拦截内核。点击后按钮文本会按当前运行状态实时刷新。\n" +
            "2. MITM 模式开关：开启后会导出证书文件，并对命中的 HTTP/HTTPS/HTTP2 流量进行增强过滤。\n" +
            "3. 使用说明：打开当前帮助文档。\n" +
            "4. 黑白名单：管理完全放行应用。加入白名单的应用会绕过寒枫拦截链。\n" +
            "5. 加速器共存：管理 VPN、代理、加速器和需要跟随它们走同一链路的目标应用。\n" +
            "6. 设置：进入 Shizuku、隐藏后台等设置项。\n" +
            "7. 状态卡当前会显示：工作状态、拦截模式、证书状态、Shizuku 状态。\n" +
            "8. Shizuku 状态会区分：未启用、未安装、未启动、未授权、已连接、服务连接中。\n\n" +
            "三、MITM 模式详细教程\n" +
            "1. 开启方式：首页打开 MITM 模式开关。\n" +
            "2. 开启后应用会把证书导出到 Download/HanFeng/HanFeng.crt。\n" +
            "3. 打开系统设置，搜索 \"安装证书\"、\"CA 证书\"、\"从存储设备安装证书\" 或类似入口。\n" +
            "4. 进入 Download/HanFeng/，选择 HanFeng.crt 完成安装。\n" +
            "5. 回到首页，确认证书状态更新为已安装。\n" +
            "6. MITM 模式适合处理 DNS 规则没法直接拦住的 HTTPS 广告、广告 JSON、广告跳转、广告路径和部分同域广告。\n" +
            "7. 若开启后出现登录失败、验证码异常、页面空白，先关闭 MITM 模式测试，再决定是否加入白名单。\n\n" +
            "四、Shizuku 增强详细教程\n" +
            "1. 先安装并启动 Shizuku。Android 11 及以上通常可通过无线调试启动，Root 设备也可直接启动。\n" +
            "2. 进入寒枫设置页，打开 \"使用 Shizuku 增强\"。\n" +
            "3. 返回首页或点击相关入口申请授权。授权成功后，寒枫会自动预热连接归属服务和广告治理服务。\n" +
            "4. Shizuku 目前用于两类增强：\n" +
            "   - 连接归属增强：辅助识别流量属于哪个 App，提高应用级拦截准确率。\n" +
            "   - 系统推广治理：对系统推广包执行停用、恢复、暂停、恢复暂停。\n" +
            "5. 设置页的系统推广治理会优先展示已安装项，并区分系统推广、负一屏推荐、浏览器推荐、主题壁纸等分类。\n" +
            "6. 单项治理当前支持：智能治理、停用、恢复、暂停、恢复暂停。智能治理会优先停用，失败后自动回退为暂停。\n" +
            "7. 批量治理当前支持：智能治理已安装推广项、批量停用、批量恢复、批量暂停、批量恢复暂停。\n" +
            "8. 对已纳入系统推广治理目录的系统推荐 App，寒枫会更积极地把它们识别为广告型上下文，并减少无意义的未知样本重复采集。\n\n" +
            "五、加速器共存与本地代理共存教程\n" +
            "1. 如果你在用 VPN、代理、加速器，先到首页点击 \"加速器共存\"。\n" +
            "2. 把 VPN / 加速器本体勾选进去。\n" +
            "3. 如果目标 App 需要跟着该加速器走同一链路，也把目标 App 一起勾选。\n" +
            "4. 若你的代理软件提供本地端口，可在共存页开启本地代理共存，填写 127.0.0.1 和端口。\n" +
            "5. 当前本地代理共存优先处理 TCP，并支持优先 SOCKS5、失败回退 HTTP CONNECT。\n" +
            "6. 对目标 App 的 QUIC/UDP 443，寒枫会尽量推动回退到 TCP，再进入本地代理链。\n" +
            "7. 加入共存后的应用会脱离寒枫的 DNS / MITM 拦截链，所以广告拦截能力会下降，但 VPN/代理稳定性会更高。\n\n" +
            "六、规则页所有功能说明\n" +
            "1. 添加规则：把输入框中的文本解析后导入规则库。\n" +
            "2. 粘贴：把剪贴板内容追加到输入框。\n" +
            "3. 清空：清空输入框。\n" +
            "4. 导入规则：通过文件选择器导入本地规则文件。\n" +
            "5. 疑似广告域名：查看运行中自动发现的可疑广告域名样本。\n" +
            "6. 筛选非广告：辅助清理明显误加或低价值规则。\n" +
            "7. 搜索框：按域名、厂商、路径关键词、正则文本等过滤。\n" +
            "8. 规则项支持单删、批量删除、重新分类。\n" +
            "9. 删除后如果 VPN 正在运行，规则会即时 reload 生效。\n\n" +
            "七、可疑域名页面说明\n" +
            "1. 可疑域名页面用于展示运行时自动采集到的广告样本。\n" +
            "2. 当前样本评分会综合这些信号：DNS、别名链、TLS SNI、HTTP body、HTTP redirect、路径命中、应用上下文、厂商上下文、置信加权。\n" +
            "3. 你可以按搜索、筛选、批量选择、推荐添加、一键加入规则库等方式处理它们。\n" +
            "4. 添加后会即时触发规则重载。\n" +
            "5. 日志导出里也会带出可疑域名多信号 CSV 报表，方便后续补规则。\n\n" +
            "八、当前支持拦截的广告样式\n" +
            "1. DNS 域名广告：已知广告域名、广告 SDK 域名、联盟广告域名、公共加密 DNS 反绕过目标。\n" +
            "2. Hosts 型广告：通过 hosts、dnsmasq、SmartDNS、OpenWrt 这类域名规则可直接落地的广告目标。\n" +
            "3. HTTPS 隐藏广告：开启 MITM 后可处理命中的 HTTPS 广告请求与广告响应。\n" +
            "4. HTTP/2 广告：支持多路复用响应头、响应体和广告字段识别。\n" +
            "5. HTTP/3 / QUIC 广告：支持广告目标阻断、必要时推动回退到 TCP。\n" +
            "6. 开屏广告：如 splash、startup、open_screen、launch 类广告。\n" +
            "7. 信息流广告：如 feed、stream、timeline、recommend_card、insert_ad 类广告。\n" +
            "8. 评论区 / 回复区插入广告：comment_banner、reply_banner、floor_promote、comment_insert_ad 等。\n" +
            "9. 小说福利与激励广告：chapter_unlock、watch_ad_unlock、task_center、coin_reward、benefit_center 等。\n" +
            "10. 底部悬浮 Banner、暂停页 Banner、播放器广告、部分激励视频和广告 JSON。\n" +
            "11. 广告跳转与落地域名：现在会单独采集 HTTP redirect 目标并参与评分。\n" +
            "12. 系统推广和负一屏推荐：可结合 Shizuku 做包级治理。\n\n" +
            "九、当前支持的规则样式\n" +
            "1. 纯域名规则：一行一个域名、子域名、广告域名列表。\n" +
            "2. Hosts 规则：如 0.0.0.0 domain.com、127.0.0.1 domain.com。\n" +
            "3. dnsmasq / SmartDNS / OpenWrt 域名规则：如 address=/domain.com/0.0.0.0、server=/domain.com/、ipset=/domain.com/...、nftset=/domain.com/...。\n" +
            "4. AdGuard / ABP 域名型规则：如 ||domain.com^、@@||domain.com^。\n" +
            "5. 一部分 AdGuard 修饰符：如 dnstype=、important、match-case、badfilter、app=、domain=、denyallow=、path=、removeparam=、csp=、urlblock、first-party / 1p、third-party / 3p。\n" +
            "6. 端口规则：dst-port=、src-port=。\n" +
            "7. 应用规则：package-name、process-name，以及 app= 修饰符。\n" +
            "8. 星号应用 / 端口规则：如 package-name,com.example.app、dst-port,443、* 配合端口或应用范围的规则。\n" +
            "9. 星号端口规则：如 *:41826\$network。\n" +
            "10. IP 网络段规则：IP-CIDR、IP-CIDR6。\n" +
            "11. 域名加端口规则：如 ||domain.com^\$dst-port=443。\n" +
            "12. Clash / Surge / Loon / Shadowrocket 常见规则：DOMAIN、DOMAIN-SUFFIX、DOMAIN-KEYWORD、DOMAIN-FULL、HOST、HOST-SUFFIX、HOSTNAME、HOSTNAME-KEYWORD、HOST-WILDCARD、DOMAIN-REGEX、PACKAGE-NAME、PROCESS-NAME、DEST-PORT、SRC-PORT。\n" +
            "13. 一部分 URL / path / keyword / regex 规则：如 URL-KEYWORD、URL-REGEX、path=、部分请求路径匹配。\n" +
            "14. 一部分 cosmetic 规则和 cosmetic exception 规则：如 ##、#@#。\n" +
            "15. 一部分应用上下文和请求上下文规则。\n" +
            "16. 注释、空行、行尾注释、混合规则文件、部分厂商标记注释。\n\n" +
            "十、规则支持边界说明\n" +
            "1. 生效最稳定的规则类型：纯域名、Hosts、dnsmasq 域名规则、AdGuard / ABP 域名型规则、IP-CIDR、带端口限制的 IP 规则、域名加端口规则、应用包名规则。\n" +
            "2. 依赖增强过滤的规则类型：URL-KEYWORD、URL-REGEX、path=、first-party / third-party、部分 regex、部分请求上下文规则、部分应用上下文规则、部分 cosmetic 规则。\n" +
            "3. 当前会跳过或部分跳过的类型：远程脚本、完整浏览器语义脚本、复杂逻辑组合、无法安全降级的高级代理规则、完整重定向脚本链。\n\n" +
            "十一、统计页说明\n" +
            "1. 统计页展示今日拦截、累计拦截、DNS 总拦截、MITM 总拦截、请求总数和节省流量。MITM 总拦截只统计证书解密后真实命中的 HTTP/HTTPS/HTTP2 深度拦截。\n" +
            "2. 排行榜区域会展示主要命中来源，方便判断哪些厂商、规则或广告方向最活跃。\n\n" +
            "十二、推荐使用方案\n" +
            "1. 追求稳定省电：只开 DNS 拦截。\n" +
            "2. 追求更强广告拦截：DNS + MITM。\n" +
            "3. 有系统推广和负一屏问题：DNS + Shizuku。\n" +
            "4. 想兼顾小说 App、开屏广告、广告 JSON、系统推广：DNS + MITM + Shizuku。\n\n" +
            "十三、常见问题与排障教程\n" +
            "1. 某个 App 联网异常：先把它加入白名单测试。\n" +
            "2. 加速器或代理异常：先把本体和目标 App 一起加入加速器共存。\n" +
            "3. 登录、验证码、支付相关异常：先关闭 MITM 测试。\n" +
            "4. 小说 App 仍有漏网广告：重开应用重新触发广告位，因为部分广告会在 QUIC 被阻断后回退到 TCP 再进入 MITM。\n" +
            "5. 旧广告一直还在：清理目标 App 缓存后重试。\n" +
            "6. 首页显示恢复中：通常是自动恢复流程，等待 2 到 3 秒。\n" +
            "7. 经常被系统中断：到系统设置里关闭电池优化、允许后台运行、锁定前台通知。\n" +
            "8. Shizuku 已授权但首页显示服务连接中：重新进入设置页或首页等待预热，必要时回到 Shizuku 重新启动服务。\n" +
            "9. 本地代理共存显示未连通：检查代理是否启动、本地端口是否正确、代理本体是否已加入共存。\n" +
            "10. 目标 App 能联网但链路不对：看日志里是否出现 Connected local proxy bridge、protocol=socks5、protocol=http_connect。\n" +
            "11. 若恢复后仍无网络，先停止拦截再重新开启。\n" +
            "12. 如有 BUG 或建议，可通过页面中的反馈入口提交。"

        fun createIntent(context: Context, title: String, content: String): Intent {
            return Intent(context, GuideActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CONTENT, content)
        }
    }

    private data class GuideSection(
        val title: String,
        val content: String
    )

    private data class GuideContent(
        val headerTitle: String,
        val headerSubtitle: String,
        val sections: List<GuideSection>
    )
}
