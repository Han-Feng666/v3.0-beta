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
            "当前版本：1.6.0\n" +
            "本说明覆盖首页、规则页、拦截与放行、统计页、MITM、Shizuku、可疑域名、加速器共存、规则导入与排障流程。\n" +
            "建议先完整看一遍首次使用、MITM 教程、Shizuku 教程、规则支持范围和常见问题，再按自己的机型和需求选择功能组合。\n\n" +
            "一、首次使用完整教程\n" +
            "1. 打开应用后先停留在首页，确认状态卡可正常显示。\n" +
            "2. 点击 \"开启拦截\"，系统会弹出 VPN 授权，点击允许。若已被其他 VPN 占用，首页会显示 VPN 共存中。\n" +
            "3. Android 13 及以上建议允许通知权限，方便前台服务稳定显示。\n" +
            "4. 若黑白名单、加速器共存或应用识别结果不完整，请到系统设置里手动允许应用列表相关权限。\n" +
            "5. 第一次建议先只用 DNS 拦截确认联网正常，再按需开启 MITM 模式。\n" +
            "6. 如果你需要更强的系统推广治理和连接归属增强，再去设置里开启 Shizuku 增强。\n" +
            "7. 需要 MITM 证书时，首页开启 MITM 开关后会自动导出证书文件。\n" +
            "8. 追求更省电时，优先只开 DNS 拦截；MITM 和 Shizuku 按需开启。\n\n" +
            "二、首页所有功能说明\n" +
            "1. 开启拦截 / 停止拦截：启动或停止寒枫本地 VPN 拦截内核。点击后按钮文本会按当前运行状态实时刷新。\n" +
            "2. MITM 模式开关：开启后会导出证书文件，并对命中的 HTTP/HTTPS/HTTP2 流量进行增强过滤。\n" +
            "3. 使用说明：打开当前帮助文档。\n" +
            "4. 黑白名单：管理完全放行应用。加入白名单的应用会绕过寒枫拦截链。\n" +
            "5. 黑白名单页也提供加速器共存模式，可管理 VPN、代理、加速器和需要跟随它们走同一链路的目标应用。\n" +
            "6. 设置：进入 Shizuku、隐藏后台、共存设置和加群入口等设置项。\n" +
            "7. 状态卡当前会显示：工作状态、拦截模式、证书状态、Shizuku 状态。\n" +
            "8. Shizuku 状态会区分：未启用、未安装、未启动、已连接、服务连接中等状态。\n" +
            "9. 首页状态会在短时间内缓存 Shizuku 检查结果，减少重复探测带来的耗电和卡顿。\n\n" +
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
            "4. Shizuku 目前用于多类增强：连接归属增强、系统推广治理、应用联网限制、后台限制和 Hosts 域名同步。\n" +
            "5. 当前版本已经统一了首页、设置页、治理页的 Shizuku 预热和状态判断逻辑。\n" +
            "6. 当首页显示兼容模式时，说明 Binder 已连通，UserService 可直接参与增强。\n" +
            "7. 当首页显示服务连接中时，通常等待几秒会自动恢复；VPN 运行中也会按冷却周期自动重试连接归属服务。\n" +
            "8. 设置页的系统推广治理会优先展示已安装项，并区分系统推广、负一屏推荐、浏览器推荐、主题壁纸等分类。\n" +
            "9. 单项治理当前支持：智能治理、停用、恢复、暂停、恢复暂停和恢复最近治理。智能治理会优先停用，失败后自动回退为暂停。\n" +
            "10. 批量治理当前支持：智能治理已安装推广项、批量停用、批量恢复、批量暂停、批量恢复暂停。\n" +
            "11. 组件治理会按启动广告、推送服务、推荐组件等风险类型展示候选项，操作前会给出风险标签和治理建议。\n" +
            "12. Hosts 编辑器可保存域名列表并通过 Shizuku 尝试同步到系统 hosts；失败时以服务反馈为准，支持清除已同步内容。\n" +
            "13. 对已纳入系统推广治理目录的系统推荐 App，寒枫会更积极地把它们识别为广告型上下文，并减少无意义的未知样本重复采集。\n\n" +
            "五、加速器共存与本地代理共存教程\n" +
            "1. 如果你在用 VPN、代理、加速器，先到首页点击 \"加速器共存\"。\n" +
            "2. 把 VPN / 加速器本体勾选进去。\n" +
            "3. 如果目标 App 需要跟着该加速器走同一链路，也把目标 App 一起勾选。\n" +
            "4. 若你的代理软件提供本地端口，可在共存页开启本地代理共存，填写 127.0.0.1 和端口。\n" +
            "5. 当前本地代理共存优先处理 TCP，并支持优先 SOCKS5、失败回退 HTTP CONNECT。\n" +
            "6. 对部分目标 App 的 QUIC/UDP 443，寒枫会尽量推动回退到 TCP，再进入本地代理链。\n" +
            "7. 加入共存后的应用会脱离寒枫的 DNS / MITM 拦截链，所以广告拦截能力会下降，但 VPN/代理稳定性会更高。\n\n" +
            "六、规则页所有功能说明\n" +
            "1. 添加规则：把输入框中的文本解析后导入规则库。\n" +
            "2. 粘贴：把剪贴板内容追加到输入框。\n" +
            "3. 清空：清空输入框。\n" +
            "4. 导入规则：通过文件选择器导入本地规则文件。\n" +
            "5. 疑似广告域名：查看运行中自动发现的可疑广告域名样本。\n" +
            "6. 筛选非广告：执行规则去重与整理。\n" +
            "7. 搜索框：按域名、厂商、路径关键词、正则文本等过滤。\n" +
            "8. 规则项支持查看详情、手动分类、单删、批量删除。\n" +
            "9. 删除后如果 VPN 正在运行，规则会即时 reload 生效。\n" +
            "10. 规则源：管理远程规则源，支持单个同步和全部同步。同步时会显示当前规则源、进度和导入条数。\n" +
            "11. 拦截与放行：查看最近哪些域名被拦截、哪些域名被放行，并显示命中应用和时间。\n\n" +
            "七、可疑域名页面说明\n" +
            "1. 可疑域名页面用于展示运行时自动采集到的广告样本。\n" +
            "2. 当前样本评分会综合这些信号：DNS、别名链、TLS SNI、HTTP body、HTTP redirect、路径命中、应用上下文、厂商上下文、置信加权。\n" +
            "3. 你可以按搜索、仅看未添加、仅看已添加、小说专项筛选、批量选择、推荐添加、一键加入规则库等方式处理它们。\n" +
            "4. 添加后会即时触发规则重载。\n" +
            "5. 列表里会展示最近应用、厂商、评分、小说专项次数和线索摘要，方便后续补规则。\n\n" +
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
            "九、当前支持的规则样式（24+ 种格式）\n" +
            "1. 纯域名：一行一个域名，如 example.com / ads.domain.net\n" +
            "2. Hosts: 0.0.0.0 domain.com / 127.0.0.1 domain.com\n" +
            "3. dnsmasq: address=/domain.com/0.0.0.0 / address=/domain.com/127.0.0.1\n" +
            "4. SmartDNS: address /domain.com/0.0.0.0 / ipset=/domain.com/adblock / nameserver=/domain.com/8.8.8.8\n" +
            "5. OpenWrt: ipset=/domain.com/adblock / nftset=/domain.com/adblock\n" +
            "6. AdGuard / ABP: ||domain.com^ / ||domain.com^\$modifier / @@||domain.com^\n" +
            "7. ABP 修饰符（30+ 种）：third-party/3p, first-party/1p, domain=域名，path=/路径/, removeparam=参数，csp=策略，redirect=资源，denyallow=域名，cookie=Cookie, header=头名：值，removeheader=头名，replace=正则/替换，app=包名，dnstype=类型，urlblock, from, to, jsinject=脚本，network, blockipv6, blockipv4, dnsrewrite=IP, generichide, ctag=标签，client=客户端，mac=地址，asn=AS 号，important, match-case, badfilter, script, image, stylesheet, xmlhttprequest 等\n" +
            "8. IP-CIDR: ip-cidr,192.168.1.0/24 / ip-cidr,10.0.0.0/8\n" +
            "9. IP-CIDR6: ip6-cidr,::1/128 / ip6-cidr,fe80::/10\n" +
            "10. Clash: DOMAIN-SUFFIX,com / DOMAIN-KEYWORD,ad / DOMAIN,xxx / IP-CIDR,xxx / PROCESS-NAME,xxx\n" +
            "11. Surge: HOST-SUFFIX,com / HOST-KEYWORD,ad / HOST,xxx / IP-CIDR,xxx\n" +
            "12. Loon: DOMAIN-SUFFIX,com / DOMAIN-KEYWORD,ad / ip-cidr,xxx / get keyword\n" +
            "13. Quantumult X: host example.com / ip-cidr 192.168.1.0/24 / host-keyword ad / host-suffix com\n" +
            "14. Shadowrocket: host-suffix,com / host-keyword,ad / url-regexp pattern / ip-cidr,xxx\n" +
            "15. V2Ray/Xray: domain:xxx / domainSuffix:xxx / domainKeyword:xxx / ip:xxx / ipCIDR:xxx\n" +
            "16. 域名 + 端口：example.com:8080 / tracker.com:443\n" +
            "17. 端口通配符：*:443\$network / *:80\$network / *:8080\$network\n" +
            "18. 路径规则：domain.com/ads/* / api.com/v1/ad/*\n" +
            "19. 关键词规则：*ad* / *tracker* / *analytics*\n" +
            "20. 正则规则：/^https?:.*ad.*\\.example\\.com/ / /.*\\.(ads?|banner)\\..*/\n" +
            "21. CSP 规则：domain##^csp:script-src 'self' / domain##^csp:default-src 'none'\n" +
            "22. CSS 规则：domain##.ad-banner / domain###sidebar-ads / domain##[class*=\"ad-\"]\n" +
            "23. 复合规则：AND(domain.com, /ads/) / OR(ads1.com, ads2.com)\n" +
            "24. 例外规则：@@||domain^ / @@0.0.0.0 whitelist.com\n" +
            "25. 包名规则：package:com.example.app / ||ads.com^\$package=com.example.app\n" +
            "26. 点前缀域名：.example.com（匹配所有子域名）\n" +
            "27. IPv6 Hosts: 2001:db8::1 ads.example.com\n" +
            "\n十、部分支持的规则（需 MITM 增强）\n" +
            "1. redirect 类：redirect=resource / redirect=noop\n" +
            "2. removeparam 类：removeparam=tracking_id / removeparam=utm_source\n" +
            "3. header 修改：header=Set-Cookie:xxx / header_remove=User-Agent\n" +
            "4. replace 类：replace=pattern/replacement/\n" +
            "5. urlblock: urlblock=pattern\n" +
            "\n十一、会跳过的规则类型\n" +
            "1. 远程脚本依赖型：需要加载外部脚本的规则\n" +
            "2. 完整 JS/DOM 运行时依赖：需要浏览器页面生命周期或执行外部脚本的重写链\n" +
            "3. 高级代理逻辑：DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD / IP-CIDR / PROCESS-NAME 会尽量落地；GEOSITE 广告类别会降级为内置广告种子域名；GEOIP / IP-ASN 需要本地数据库支撑，当前安全跳过\n" +
            "4. 无法安全降级的复杂组合规则\n" +
            "\n十二、规则导入说明\n" +
            "1. 缩进型 `payload:` / `rules:` 多行块可自动展开导入\n" +
            "2. 支持 `rule:` / `value:` 包裹的单行规则\n" +
            "3. 支持 `- ` / `* ` 开头的 YAML 列表项\n" +
            "4. 自动识别并跳过注释（# / ! 开头）\n" +
            "5. 支持行内注释（#xxx / !xxx / ;xxx 后内容会被忽略）\n" +
            "6. 大文件导入会自动去重和优化\n" +
            "7. 本地文件导入和远程规则源同步不再设置固定大小硬限制，实际可处理规模取决于设备内存、存储和规则格式复杂度\n" +
            "8. 导入本地文件时会显示读取、分析、导入和刷新进度，完成后显示本次导入条数与当前可拦截规则总数\n" +
            "9. 同步多个远程规则源时会显示当前第几个规则源、规则源名称和导入状态，完成后显示成功数量和导入条数\n\n" +
            "十三、推荐使用方案\n" +
            "1. 追求稳定省电：只开 DNS 拦截。\n" +
            "2. 追求更强广告拦截：DNS + MITM（HTTPS 解密）。\n" +
            "3. 有系统推广和负一屏问题：DNS + Shizuku（包级治理）。\n" +
            "4. 想兼顾小说 App、开屏广告、广告 JSON、系统推广：DNS + MITM + Shizuku。\n\n" +
            "十四、适配并支持更多类型的规则\n" +
            "1. 已支持 24+ 种主流规则格式，可直接导入各类规则列表。\n" +
            "2. 自动识别规则类型，无需手动选择格式。\n" +
            "3. 混合规则文件可自动解析，支持注释和空行。\n" +
            "4. 缩进型 YAML 块（payload:/rules:）可自动展开导入。\n" +
            "5. 规则解析性能优化，正则表达式自动缓存减少重复编译。\n" +
            "6. 大文件导入会自动去重和优化，避免冗余规则。\n" +
            "7. 兼容 AdGuard、Clash、Surge、Loon、QX、Shadowrocket、V2Ray 等工具规则。\n" +
            "8. 部分支持的规则（redirect/removeparam/header 修改）需 MITM 增强。\n" +
            "9. 高级规则会按能力分层处理：浏览器语义规则会尽量降级为 MITM 可处理的参数、头部、重写、CSP 和 CSS 隐藏；高级代理规则会优先支持域名、IP-CIDR、应用级匹配和 GEOSITE 广告类别；远程脚本、GEOIP、IP-ASN 会安全跳过。\n" +
            "10. 详细规则语法可直接参考本说明的规则样式章节和导入提示。\n\n" +
            "十五、拦截与放行说明\n" +
            "1. 该页面用于回看最近的放行和拦截结果。\n" +
            "2. 左侧会显示红色的拦截或绿色的放行。\n" +
            "3. 右侧上方显示域名，下方显示应用名和时间。\n" +
            "4. 长按域名可直接复制，方便加规则或排查误杀。\n" +
            "5. 这个页面适合配合 MITM、Shizuku、可疑域名页一起定位漏拦和误拦。\n\n" +
            "十六、统计页说明\n" +
            "1. 统计页展示今日拦截、累计拦截、DNS 总拦截、请求总数、响应总数和累计节省流量。\n" +
            "2. 排行榜区域会展示厂商拦截排行、厂商请求排行、厂商响应排行、应用拦截排行、应用请求排行、应用响应排行。点击查看完整榜单可进入详情页。\n\n" +
            "十七、常见问题与排障教程\n" +
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
            "12. 导入超大规则文件时长时间停留在进度页：等待当前阶段完成，若设备内存不足导致系统回收，建议拆分规则文件后重新导入。\n" +
            "13. 远程规则源同步失败：检查网络、规则源地址和 GitHub 访问情况，再进入规则源管理页单独同步失败源。\n" +
            "14. 如有 BUG 或建议，可通过设置页加群入口、日志导出或现有沟通渠道继续反馈。"

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
