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
        binding.btnReward.setOnClickListener {
            launchActivitySafely(
                RewardActivity.createIntent(this),
                failureMessage = "打开赞赏页失败"
            )
        }
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
            "反馈群：573309536\n" +
            "一、首次使用完整教程\n" +
            "1. 打开应用后先停留在首页，确认状态卡可正常显示。\n" +
            "2. 应用会弹窗建议关闭电池优化和允许自启动，请点击允许。\n" +
            "3. 点击 \"开启拦截\"，系统会弹出 VPN 授权，点击允许。若已被其他 VPN 占用，首页会显示 VPN 共存中，可进入加速器共存配置。\n" +
            "4. Android 13 及以上建议允许通知权限，方便前台服务稳定显示。\n" +
            "5. 若黑白名单、加速器共存或应用识别结果不完整，请到系统设置里手动允许应用列表相关权限。\n" +
            "6. 第一次建议先只用 DNS 拦截确认联网正常，再按需开启 MITM 模式。\n" +
            "7. 如果你需要更强的系统推广治理和连接归属增强，再去设置里开启 Shizuku 增强。\n" +
            "8. 需要 MITM 证书时，首页开启 MITM 开关后会自动导出证书文件。\n" +
            "9. 如果你同时使用加速器、VPN 或代理，先到设置页 \"加速器共存设置\" 把本体和目标应用一起勾选。详见第七节加速器共存教程。\n" +
            "10. 追求更省电时，优先只开 DNS 拦截；MITM 和 Shizuku 按需开启。\n\n" +
            "二、各厂商设备专项设置\n" +
            "1. 小米/Redmi（MIUI/HyperOS）：系统设置 → 应用设置 → 应用管理 → 寒枫 → 自启动（允许）+ 省电策略（无限制）+ 锁屏显示（允许）。\n" +
            "2. 华为/Honor（EMUI/HarmonyOS）：手机管家 → 应用启动管理 → 寒枫 → 手动管理（全部开启）。\n" +
            "3. OPPO/一加/realme（ColorOS）：设置 → 应用 → 应用管理 → 寒枫 → 耗电管理（允许后台运行）+ 自启动（允许）。\n" +
            "4. vivo/iQOO（FuntouchOS/OriginOS）：设置 → 应用与权限 → 权限管理 → 自启动 → 寒枫（允许）；i管家 → 省电管理 → 后台高耗电 → 寒枫（允许）。\n" +
            "5. 三星（OneUI）：设置 → 应用程序 → 寒枫 → 电池 → 不受限制；智能管理器 → 内存 → 已排除的应用 → 添加寒枫。\n" +
            "6. 魅族（Flyme）：手机管家 → 权限管理 → 后台管理 → 寒枫 → 允许后台运行。\n" +
            "7. 原生 Android/Pixel：设置 → 应用 → 寒枫 → 电池 → 不受限制。\n" +
            "8. 以上操作可确保广告拦截在灭屏后不会被系统强制中断。\n\n" +
            "三、首页所有功能说明\n" +
            "1. 开启拦截 / 停止拦截：启动或停止寒枫本地 VPN 拦截内核。点击后按钮文本会按当前运行状态实时刷新。\n" +
            "2. MITM 模式开关：开启后会导出证书文件，并对命中的 HTTP/HTTPS/HTTP2 流量进行增强过滤。证书未安装时仍会保留 DNS/IP 层拦截。\n" +
            "3. 使用说明：打开当前帮助文档。\n" +
            "4. 黑白名单：管理完全放行应用。加入白名单的应用会绕过寒枫拦截链。\n" +
            "5. 加速器共存：管理 VPN、代理、加速器和需要跟随它们走同一链路的目标应用。勾选的加速器/VPN 本体绕过寒枫 VPN，目标 App 可由寒枫承接后转发到本地代理。入口在设置页的 \"加速器共存设置\"。详见第七节。\n" +
            "6. 设置：进入 Shizuku、隐藏后台、共存设置和加群入口等设置项。\n" +
            "7. 状态卡当前会显示：工作状态、拦截模式、证书状态、Shizuku 状态。\n" +
            "8. Shizuku 状态会区分：未启用、未安装、未启动、已连接、服务连接中等状态。\n" +
            "9. 首页状态会在短时间内缓存 Shizuku 检查结果，减少重复探测带来的耗电和卡顿。\n\n" +
            "四、MITM 模式详细教程\n" +
            "1. 开启方式：首页打开 MITM 模式开关。\n" +
            "2. 开启后应用会把证书导出到 Download/HanFeng/HanFeng.crt，设置页也可以手动重新导出。\n" +
            "3. 打开系统设置，搜索 \"安装证书\"、\"CA 证书\"、\"从存储设备安装证书\" 或类似入口。\n" +
            "4. 进入 Download/HanFeng/，选择 HanFeng.crt 完成安装。\n" +
            "5. 回到首页，确认证书状态更新为已安装。\n" +
            "6. MITM 模式适合处理 DNS 规则没法直接拦住的 HTTPS 广告、广告 JSON、广告跳转、广告路径、竞价请求和部分同域广告。\n" +
            "7. 正常覆盖安装或应用商店更新通常无需重新安装证书；卸载重装、清空数据或证书重新生成后需要安装新证书。\n" +
            "8. 遇到证书锁定 App 时，HTTPS 内容层可能无法解密，寒枫会继续使用 DNS/IP、QUIC 回退和 Shizuku 能力降级处理。\n" +
            "9. 若开启后出现登录失败、验证码异常、页面空白，先关闭 MITM 模式测试，再决定是否加入白名单。\n\n" +
            "五、Shizuku 增强详细教程\n" +
            "1. 先安装并启动 Shizuku。Android 11 及以上通常可通过无线调试启动，Root 设备也可直接启动。\n" +
            "2. 进入寒枫设置页，打开 \"使用 Shizuku 增强\"。\n" +
            "3. 返回首页或点击相关入口申请授权。授权成功后，寒枫会自动预热连接归属服务和广告治理服务。\n" +
            "4. Shizuku 目前用于多类增强：连接归属增强、系统推广治理、应用联网限制、后台限制和 Hosts 域名同步。\n" +
            "5. 当前版本已经统一了首页、设置页、治理页的 Shizuku 预热和状态判断逻辑。\n" +
            "6. 当首页显示兼容模式时，说明 Binder 已连通，UserService 可直接参与增强。\n" +
            "7. 当首页显示服务连接中时，通常等待几秒会自动恢复；VPN 运行中也会按冷却周期自动重试连接归属服务。\n" +
            "8. 设置页的系统推广治理会优先展示已安装项，并区分系统推广、负一屏推荐、浏览器推荐、主题壁纸等分类。\n" +
            "9. 单项治理当前支持：智能治理、关闭推送广告、恢复推送广告、冻结、解冻、暂停、恢复暂停、组件治理和恢复最近治理。\n" +
            "10. 批量治理当前支持：智能治理已安装推广项、批量冻结、批量解冻、批量暂停、批量恢复暂停。\n" +
            "11. 组件治理现在使用独立页面展示，默认只显示疑似推广组件，并按用途、风险、状态和建议逐条列出；需要更强处理时可切换到全部 Activity。\n" +
            "12. Hosts 编辑器可保存域名列表并通过 Shizuku 尝试同步到系统 hosts；失败时以服务反馈为准，支持清除已同步内容。\n" +
            "13. 对已纳入系统推广治理目录的系统推荐 App，寒枫会更积极地把它们识别为广告型上下文，并减少无意义的未知样本重复采集。\n\n" +
            "六、系统推广治理功能效果与教程\n" +
            "1. 进入方式：首页或设置页开启 Shizuku 增强后，打开系统推广治理或推广治理范围页面。\n" +
            "2. 搜索 App：可按应用名、包名、分类、来源和识别标签搜索，点击 App 后会弹出治理方式。\n" +
            "3. 智能治理：优先关闭推送广告；需要更强处理时再尝试冻结，冻结失败时回退为暂停。适合不想逐项选择的新用户。\n" +
            "4. 关闭推送广告：限制通知和后台推送广告能力，应用图标保留，对正常启动影响较小，建议优先尝试。\n" +
            "5. 恢复推送广告：撤销关闭推送广告的处理，适合误伤通知或需要恢复提醒时使用。\n" +
            "6. 冻结：底层使用系统禁用应用能力，效果接近冰箱冻结。桌面图标会消失，后台、通知和大部分 Activity 会停止。适合确认无用的预装推广、广告壳或垃圾 App。\n" +
            "7. 解冻：重新启用被冻结的 App，桌面图标通常会恢复；若列表里找不到，可使用最近治理记录恢复。\n" +
            "8. 暂停：挂起 App，图标通常仍保留，但 App 可能无法正常运行或启动。适合临时限制推广行为。\n" +
            "9. 恢复暂停：解除暂停状态，让 App 恢复运行。\n" +
            "10. 组件治理：只处理疑似广告组件，例如启动广告 Activity、推送 Receiver、广告 Service、推荐页 Activity。图标通常保留，误伤范围小于整包冻结。\n" +
            "11. 组件治理教程：打开组件治理页面后，先看推荐组件列表；每项会显示用途、风险、状态、完整组件名和建议。勾选后可点冻结选中或解冻选中。\n" +
            "12. 全部 Activity：会展示该 App 的全部 Activity，数量可能很多。冻结全部或冻结主入口后，效果接近冰箱类冻结，桌面图标可能消失，应用页面通常无法打开。\n" +
            "13. 搜索组件：可按组件名、类型、用途、风险和建议搜索，适合在组件数量较多时定位启动页、推荐页、推送和广告组件。\n" +
            "14. 手动输入：当自动列表没有识别到目标组件，或你从日志/第三方工具拿到完整组件名时使用，格式类似 包名/.组件类名。\n" +
            "15. 恢复最近治理：恢复最近一次治理前记录的应用启用状态、暂停状态、推送广告状态和组件状态。适合冻结后图标消失、误冻结组件或误关通知时使用。\n" +
            "16. 批量治理：适合一次处理当前筛选列表。首次使用建议先单个 App 验证效果，再批量处理同类推广项。\n" +
            "17. 风险建议：普通用户优先顺序为关闭推送广告、组件治理、暂停、冻结；系统核心应用和常用主业务 App 处理前先看风险提示。\n\n" +
            "七、加速器共存与本地代理共存教程\n" +
            "1. 使用场景：你在用 VPN、代理或游戏加速器（如 Clash、V2Ray、UU 加速器等），同时又要让寒枫拦截广告，就需要让加速器或 VPN 和寒枫同时运行。加速器共存功能正是为此设计。\n" +
            "2. 入口：设置页点击 \"加速器共存设置\"。\n" +
            "3. 第一步——勾选加速器或 VPN 本体：列表会自动把常见加速器、VPN、代理应用置顶（带标记）。勾选本体的意思，是让这个应用不走寒枫 VPN 隧道，直接用它自己的出口联网。\n" +
            "4. 第二步——勾选需要跟着加速器走的目标应用：例如正在加速的游戏、需要走代理的 App。勾选后，这些应用的 TCP 流量会由寒枫承接，再转发到你配置的本地代理端口，最终走加速器链路。与只加白名单相比，这样做还能保留 DNS 拦截和 MITM 拦截能力。\n" +
            "5. 第三步——打开页面底部 \"配置信息\"：填写本地代理的地址和端口。\n" +
            "   (1) 地址：通常填 127.0.0.1。\n" +
            "   (2) 端口：填你代理软件在本地开放的端口。常见参考：Clash 7890、V2RayNG 10808、Surfboard 6152、sing-box 2080、Shadowrocket 1080。UU 类纯加速器不需要填端口，只勾选本体即可。\n" +
            "   (3) 代理应用包名：可填可不填。留空时，寒枫会试图按已选加速器自动识别。\n" +
            "   (4) 保存后会立即生效，若寒枫 VPN 正在运行会自动 reload。\n" +
            "6. 协议说明：当前本地代理共存优先处理 TCP；优先 SOCKS5 握手，失败自动回退到 HTTP CONNECT。部分代理软件只开放 HTTP 端口，寒枫会自动降级，无需手动指定协议。\n" +
            "7. UDP 和 QUIC：目标 App 的 QUIC (UDP 443) 会被寒枫推动回退到 TCP，再进入本地代理链路，这样打游戏时不会因为 QUIC 被拦而卡住，同时依然能拦截 QUIC 通道的广告请求。\n" +
            "8. 共存诊断：在 \"配置信息\" 页底部会显示实时预览，包括代理本体排除状态、目标应用数量、共存列表总数。\n" +
            "9. 仅勾选本体（不填端口）：寒枫会让这些应用绕过 VPN，但仍承担广告拦截。这适合不会开放本地端口的加速器（如 UU、BiU）和不需要复杂代理链路的场景。\n" +
            "10. 完全放行（加入白名单）：如果你不想让寒枫对某个目标 App 做任何处理，可在黑白名单里直接加入白名单。代理稳定性最高，但广告拦截能力会下降。\n" +
            "11. 与首页 MITM 的关系：加速器共存不会关闭 MITM。本地代理配置保存生效后，MITM 检测、响应解密、广告注入仍继续工作，本地代理只负责把网络包转发出去。\n" +
            "12. 常见组合推荐：\n" +
                "  (a) Clash/V2Ray + 广告拦截：勾选 Clash 本体 + 目标应用 + 配置信息填 127.0.0.1:7890，TCP 流量经寒枫过滤后转发到 Clash，Clash 再走节点。\n" +
                "  (b) UU 加速器 + 广告拦截：勾选 UU 本体和游戏本体，不填端口。UU 和游戏直接走自己的隧道，绕过寒枫 VPN；其他 App 继续被拦截。\n" +
                "  (c) Shadowrocket + 广告拦截：勾选 Shadowrocket + 目标应用 + 配置信息填 127.0.0.1:1080，SOCKS5 转发。\n" +
            "13. 排错：参考下方常见问题的加速器或代理异常条目。\n\n" +
            "八、规则页所有功能说明\n" +
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
            "11. 拦截与放行：查看最近哪些域名被拦截、哪些域名被放行，并显示命中应用和时间。\n" +
            "12. 导出规则到文件：将本地规则库导出为 txt，Android 10 及以上会写入系统下载目录，Android 9 及以下会按权限写入下载目录。\n\n" +
            "九、可疑域名页面说明\n" +
            "1. 可疑域名页面用于展示运行时自动采集到的广告样本。\n" +
            "2. 当前样本评分会综合这些信号：DNS、别名链、TLS SNI、HTTP body、HTTP/2 body、HTTP redirect、路径命中、硬编码 IP、应用上下文、厂商上下文、置信加权。\n" +
            "3. 你可以按搜索、仅看未添加、仅看已添加、小说专项筛选、批量选择、推荐添加、一键加入规则库等方式处理它们。\n" +
            "4. 添加后会即时触发规则重载，VPN 正在运行时会尽量热生效。\n" +
            "5. 列表里会展示最近应用、厂商、评分、小说专项次数和线索摘要，方便后续补规则。\n\n" +
            "十、当前支持拦截的广告样式\n" +
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
            "12. 硬编码 IP / 直连广告：命中广告 IP 缓存后会参与拦截和可疑样本记录。\n" +
            "13. 共用主域名广告：开启 MITM 后会优先按路径、请求头、响应体和广告字段精确识别。\n" +
            "14. 系统推广和负一屏推荐：可结合 Shizuku 做包级治理。\n\n" +
            "十一、当前支持的规则样式（24+ 种格式）\n" +
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
            "\n十二、部分支持的规则（需 MITM 增强）\n" +
            "1. redirect 类：redirect=resource / redirect=noop\n" +
            "2. removeparam 类：removeparam=tracking_id / removeparam=utm_source\n" +
            "3. header 修改：header=Set-Cookie:xxx / header_remove=User-Agent\n" +
            "4. replace 类：replace=pattern/replacement/\n" +
            "5. urlblock: urlblock=pattern\n" +
            "\n十三、会跳过的规则类型\n" +
            "1. 远程脚本依赖型：需要加载外部脚本的规则\n" +
            "2. 完整 JS/DOM 运行时依赖：需要浏览器页面生命周期或执行外部脚本的重写链\n" +
            "3. 高级代理逻辑：DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD / IP-CIDR / PROCESS-NAME 会尽量落地；GEOSITE 广告类别会降级为内置广告种子域名；GEOIP / IP-ASN 需要本地数据库支撑，当前安全跳过\n" +
            "4. 无法安全降级的复杂组合规则\n" +
            "\n十四、规则导入说明\n" +
            "1. 缩进型 `payload:` / `rules:` 多行块可自动展开导入\n" +
            "2. 支持 `rule:` / `value:` 包裹的单行规则\n" +
            "3. 支持 `- ` / `* ` 开头的 YAML 列表项\n" +
            "4. 自动识别并跳过注释（# / ! 开头）\n" +
            "5. 支持行内注释（#xxx / !xxx / ;xxx 后内容会被忽略）\n" +
            "6. 大文件导入会自动去重和优化\n" +
            "7. 本地文件导入和远程规则源同步不再设置固定大小硬限制，实际可处理规模取决于设备内存、存储和规则格式复杂度\n" +
            "8. 导入本地文件时会显示读取、分析、导入和刷新进度，完成后显示本次导入条数与当前可拦截规则总数\n" +
            "9. 同步多个远程规则源时会显示当前第几个规则源、规则源名称和导入状态，完成后显示成功数量和导入条数\n\n" +
            "十五、推荐使用方案\n" +
            "1. 追求稳定省电：只开 DNS 拦截。\n" +
            "2. 追求更强广告拦截：DNS + MITM（HTTPS 解密），并定期处理可疑域名推荐。\n" +
            "3. 有系统推广和负一屏问题：DNS + Shizuku（包级治理）。\n" +
            "4. 想兼顾小说 App、开屏广告、广告 JSON、系统推广：DNS + MITM + Shizuku。\n" +
            "5. 同时要用加速器 / VPN / 代理：开启拦截 + 配置加速器共存（勾选本体 + 目标 App + 填本地代理端口）。ROOT 设备还可再加 Root 隐藏，让加速器和寒枫同时不被检测。\n" +
            "6. 想完整隐藏 Root：进入 Root 隐藏页面，一键预设作用域 + 打开 Prop 伪装 + 打开启动监听 + 应用隐藏，一次配置后续自动生效。\n\n" +
            "十六、适配并支持更多类型的规则\n" +
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
            "十七、拦截与放行说明\n" +
            "1. 该页面用于回看最近的放行和拦截结果。\n" +
            "2. 左侧会显示红色的拦截或绿色的放行。\n" +
            "3. 右侧上方显示域名，下方显示应用名和时间。\n" +
            "4. 长按域名可直接复制，方便加规则或排查误杀。\n" +
            "5. 这个页面适合配合 MITM、Shizuku、可疑域名页一起定位漏拦和误拦。常见原因包括 matched-rule、smart-score-suspicious、tracked-ad-target、general-ad-traffic 和 novel-force-quic-block。\n\n" +
            "十八、统计页说明\n" +
            "1. 统计页展示今日拦截、累计拦截、DNS 总拦截、请求总数、响应总数和累计节省流量。\n" +
            "2. 排行榜区域会展示厂商拦截排行、厂商请求排行、厂商响应排行、应用拦截排行、应用请求排行、应用响应排行。点击查看完整榜单可进入详情页。\n\n" +
            "十九、隐身模式（反追踪）使用说明\n" +
            "1. 入口：进入设置页面，打开「隐身模式（反追踪）」开关。\n" +
            "2. 隐身模式依赖 MITM（HTTPS 解密），请先确保 MITM 模式已开启且证书已安装。未开启 MITM 时隐身模式不生效。\n" +
            "3. 开启后会显示三个子开关，可独立控制每项反追踪策略：\n" +
            "  (1) 剥离 URL 追踪参数：移除请求 URL 中的广告和分析追踪参数（utmsource、fbclid、gclid、msclkid 等 60+ 种）。\n" +
            "  (2) 隐藏 Referer：截断 Referer 仅保留域名部分（如 https://example.com），隐藏你从哪个具体页面来访。\n" +
            "  (3) 移除浏览器指纹头：净化 HTTP 请求中的浏览器指纹标识头（sec-ch-ua-*、x-client-data、x-requested-with 等 40+ 种），减少跨站追踪和用户画像。\n" +
            "4. 隐身模式与广告拦截互不干扰：追踪参数、Referer、指纹头的移除在广告规则匹配之后、请求转发之前完成，不影响广告拦截效果。\n" +
            "5. 如果开启后部分网站功能异常（如跳转回源、第三方登录失效），可以关闭对应子开关排查，或参考常见问题中的排障步骤。\n\n" +
            "二十、Root 隐藏功能说明（需 Root 设备）\n" +
            "1. 入口：设置页进入 Root 隐藏。\n" +
            "2. Root 隐藏用于让银行、支付、游戏等检测 Root 的 App 看不到 Root 痕迹，同时让寒枫自己的 Root 加速能力继续可用。\n" +
            "3. 状态面板会实时显示：Root 方案（Magisk/KernelSU/APatch/None）、Zygisk 状态、Zygisk Next 模块状态、DenyList 可用性、系统挂载模式（EROFS 只读 / 可挂载）、隐藏路径数、已隐藏 DenyList 文件数和进程数。\n" +
            "4. Prop 伪装开关：开启后会通过 resetprop 修改 19 项关键系统属性：ro.debuggable=0、ro.secure=1、ro.build.type=user、ro.build.tags=release-keys、ro.boot.verifiedbootstate=green、ro.boot.flash.locked=1、ro.boot.unlocked=0、ro.boot.veritymode=enforcing、ro.boot.vbmeta.device_state=locked、ro.bootloader/baseband 伪装、init.svc.magisk_pfs 清空、ro.magisk.version 清空等。关掉后自动还原原值。\n" +
            "5. 启动监听开关：开启后会启动后台守护脚本，每秒扫描 /proc 下的进程命令行，作用域内 App 一启动就立即执行 mount bind 隐藏所有 Root 路径，无需手动按按钮。应用隐藏完成后会自动打开此开关。\n" +
            "6. 作用域管理：默认隐藏所有第三方应用。可手动在「作用域」tab 中取消勾选不需要隐藏的特殊 App，也可点「全选所有应用」一键把全部第三方 App 加入作用域，点「清空作用域」撤销全部勾选。\n" +
            "7. 应用隐藏按钮：一键执行 Zygisk 启用 → DenyList/白名单 → KSU/LSPosed → root mount bind 多层组合。建议在预设作用域后点一次应用隐藏，会自动启动后台监听守候后续 App 启动。\n" +
            "8. 解除隐藏按钮：一键撤销所有隐藏操作（DenyList 全部移除 + 进程 mount 全部解绑 + 系统路径 umount + Prop 伪装还原 + 后台监听停止），用于彻底恢复隐藏前状态。\n" +
            "9. 监听日志按钮：查看 RootHide watcher 后台脚本的运行状态和最近 100 行日志（包名、PID、处理时间戳），方便排查 watcher 是否在活动、命中了哪些 App。\n" +
            "10. Zygisk Next 已内置 Shamiko 同等能力，无需额外安装 Shamiko 模块。\n" +
            "11. EROFS 系统分区无法直接修改文件，寒枫会自动改用进程级 mount bind 和 DenyList 隐藏处理；只做只读检测不真的 remount 系统分区。\n" +
            "12. KernelSU/APatch 设备有自带 Zygisk 实现，无需 Magisk 也可使用 Root 隐藏。\n" +
            "13. 隐藏记录在 reboot 后会自动恢复：DenyList 是 Magisk/KSU 持久化的，Prop 伪装重启后失效（需重新打开开关，会自动检测并重新应用），Watcher 脚本重启手机后需重新打开启动监听。\n\n" +
            "二十一、常见问题与排障教程\n" +
            "1. 某个 App 联网异常：先把它加入白名单测试。\n" +
            "2. 加速器或代理异常：先把本体和目标 App 一起加入加速器共存；本地代理类（Clash/V2Ray）还需在配置信息里填写 127.0.0.1 和端口。\n" +
            "3. 加速器共存已配置但目标 App 无法联网：检查代理软件是否已开启、端口是否与配置信息一致、代理软件是否允许局域网/本地连接。若代理软件有 \"允许局域网连接\" 开关请打开。\n" +
            "4. 加速器共存已配置但流量没走代理链路：确认目标 App 已在加速器共存列表中勾选，且代理本体也已在列表中（否则会被寒枫 VPN 抢占出口）。\n" +
            "5. 登录、验证码、支付相关异常：先关闭 MITM 测试。\n" +
            "6. 小说 App 仍有漏网广告：重开应用重新触发广告位，因为部分广告会在 QUIC 被阻断后回退到 TCP 再进入 MITM。\n" +
            "7. 旧广告一直还在：清理目标 App 缓存后重试。\n" +
            "8. 首页显示恢复中：通常是自动恢复流程，等待 2 到 3 秒。\n" +
            "9. 经常被系统中断：到系统设置里关闭电池优化、允许后台运行、锁定前台通知。详见上方各厂商设备专项设置。\n" +
            "10. Shizuku 已授权但首页显示服务连接中：重新进入设置页或首页等待预热，必要时回到 Shizuku 重新启动服务。\n" +
            "11. 本地代理共存显示未连通：检查代理是否启动、本地端口是否正确、代理本体是否已加入共存、防火墙是否拦截了 127.0.0.1 本地回环端口。\n" +
            "12. 目标 App 能联网但链路不对：看日志里是否出现 Connected local proxy bridge、protocol=socks5、protocol=http_connect。若只看到 protocol=socks5/fail 又回退到 http_connect，说明 SOCKS5 端口填错或代理不支持 SOCKS5，请改填 HTTP 端口。\n" +
            "13. 加速器共存后加速器本体本身连不上：确认加速器本体在加速器共存列表里被勾选。勾选的加速器/VPN 会绕过寒枫 VPN，直接使用自己的隧道；没勾选则会被寒枫 VPN 覆盖，导致双重 VPN 冲突。\n" +
            "14. 若恢复后仍无网络，先停止拦截再重新开启。\n" +
            "15. 导入超大规则文件时长时间停留在进度页：等待当前阶段完成，若设备内存不足导致系统回收，建议拆分规则文件后重新导入。\n" +
            "16. 远程规则源同步失败：检查网络、规则源地址和 GitHub 访问情况，再进入规则源管理页单独同步失败源。\n" +
            "17. 冻结后桌面图标消失：这是系统禁用应用的正常效果，到推广治理里点解冻或恢复最近治理即可找回。\n" +
            "18. 组件治理列表没有想处理的组件：使用高级手动输入，输入完整组件名后选择冻结组件或解冻组件。\n" +
            "19. Root 隐藏后银行/支付仍检测到 Root：先确认 DenyList 可用（内核版本太老的 Magisk 可能不支持 DenyList），然后点应用隐藏按钮查看输出日志确认每个 App 是否成功加入 DenyList，再确认 Prop 伪装开关是否打开（状态面板会显示 Prop 是否完整伪装）；再确认已打开启动监听；最后强制停止目标银行/支付 App 进程让其重新加载系统属性。\n" +
            "20. Root 隐藏 watcher 显示未运行：重启手机后 watcher 脚本不会自动恢复；进入 Root 隐藏页面重新打开\"启动监听\"开关即可。如已开启但某任务被杀（如系统清理加速），同样需手动重新打开监听。\n" +
            "21. Root 隐藏解除后发现仍有部分 App 受影响：可能是 DenyList 移除命令执行被 Magisk/KSU 拒绝；进入监听日志查看是否有遗留 PID 仍在 mount bind；强制停止目标 App 后下一轮 watcher 扫描会自动覆盖；若仍卡死可重启手机后再点解除隐藏。\n" +
            "22. Prop 伪装提示未找到 resetprop：需确认你的 Magisk 20+ 自带 resetprop 命令。KernelSU 请升级到最新版自带 resetprop 或 kproprop，APatch 请安装对应 resetprop 补丁。\n" +
            "23. 首页状态卡显示 Shizuku 完全断开：可能因后台被系统杀进程，重启 Shizuku 服务并重新申请权限即可恢复。\n" +
            "24. 如有 BUG 或建议，可通过设置页加群入口、日志导出或现有沟通渠道继续反馈。\n\n" +
            "二十二、应用冻结管理功能说明\n" +
            "1. 入口：设置页进入应用冻结。应用冻结是一个独立的冻结管理器，类似冰箱 App，提供完整的全量应用列表和冻结/解冻切换能力。\n" +
            "2. 前置条件：需要 Shizuku 增强已开启并授权。Shizuku 服务连接失败时无法冻结，请按首页或推广治理的 Shizuku 检查流程排查。\n" +
            "3. 列表展示：会加载设备上全部已安装应用，按关键系统 > 已冻结 > 名称排序显示，每个条目展示图标、应用名、包名和状态徽章（已冻结 / 已暂停 / 系统应用 / 关键）。\n" +
            "4. 全部应用和已冻结两个标签页：全部应用展示设备所有应用；已冻结只展示当前被冻结的应用，方便快速定位和批量解冻。\n" +
            "5. 搜索框：按应用名或包名实时过滤（支持大小写不敏感），输入框防抖 180ms 后生效。\n" +
            "6. 单项操作：每个条目右侧都有「冻结」或「解冻」按钮。冻结后桌面图标会消失，应用无法运行；解冻后恢复。\n" +
            "7. 冻结当前列表：批量冻结当前展示的所有未冻结非关键应用。已冻结或关键系统应用会自动跳过。适合一次性清理某个分类或筛选结果中的应用。\n" +
            "8. 解冻全部：一键解冻所有已冻结应用。此操作会弹确认弹窗，确认后才执行。\n" +
            "9. 关键系统应用保护：android、SystemUI、Phone、设置、启动器、输入法等关键包名被内置保护列表硬屏蔽，按钮显示\"保护\"且不可点击，批量操作也会自动跳过。\n" +
            "10. freeze 和 suspend 区别：应用冻结只使用 disable（pm disable）正式冻结，效果和推广治理中的\"冻结\"一致；暂停（suspend）相关能力保留在推广治理内，本模块不启用 suspend 降级逻辑。\n" +
            "11. 与推广治理的关系：推广治理聚焦系统推广项和广告 App 的智能治理，按预设目录发现并提示风险。应用冻结是通用冻结管理器，主要满足 \"把它当冰箱用\" 的需求，列表覆盖所有应用，不限于推广类。\n\n" +
            "二十三、腾讯游戏防设备标记功能说明（需 Root）\n" +
            "1. 入口：设置页进入腾讯游戏防设备标记。该功能依赖 Root（Magisk、KernelSU 或 APatch），无 Root 自动提示不可用。\n" +
            "2. 实现原理：在 Root shell 启动一个 nohup 守护脚本，每 2 秒检测 target.txt 中所有腾讯游戏包名是否在运行（pidof），游戏运行时把 /mnt/vendor/persist/data 权限改为 000 阻止写入设备标记；游戏从任务栏划掉后台后自动清理 ano_tmp、cache、code_cache、mrpcs* 文件并还原 700 权限。\n" +
            "3. 启动监听开关：打开后会写 watcher.sh 到 /data/adb/GameAntiMark/ 并 nohup 启动，PID 保存到 watcher.pid。开关状态会写入 SharedPreferences，但守护脚本本身是 Root 进程，不依赖寒枫 App 存活——即使寒枫被杀也继续运行。\n" +
            "4. 状态面板字段：监听守护状态（运行中/未运行加 PID）、Root 方案与版本、SoC 是否 SM8850、游戏包名数量、当前运行中游戏数、已清理次数、/mnt/vendor/persist/data 权限、boot_completed、最近一次清理时间戳。\n" +
            "5. 游戏包名列表按钮：可视化编辑 target.txt 对应的包名集合。内置 50 个腾讯游戏包名（PUBG、和平精英、王者荣耀、CF、火影、QQ飞车等），支持新增、删除、恢复默认、清空四种操作。包名格式校验：必须以字母开头的标准 Java 包名（点分隔、字母数字下划线）。\n" +
            "6. 包名列表改动会自动重启 watcher（如果当前在运行），使新配置立即生效。\n" +
            "7. 随机修改 AndroidID/SSAID 按钮：等价于原模块 action.sh 的音量+ 流程：随机生成新的 android_id 写入 settings secure，对每个用户（0、10、11、901、999）的每个游戏包名生成新的 16 位 hex SSAID，并 sed 替换 settings_ssaid.xml / fallback / bptmp 三份文件。完成后必须手动重启手机，否则系统不重新加载 SSAID。\n" +
            "8. 骁龙 8 Elite 5（SM8850）特判：进入页面时自动检测 SoC，若匹配则自动设置 SM8850 标志位，并跳过 chmod 000 / chmod 700 步骤，仅执行文件清理，防止 TEE 损坏。\n" +
            "9. 查看守护日志按钮：tail 200 行 watcher.log，包含 watcher started、permission set to 000、permission restored、CLEANED 等事件，便于排查守护是否真的在工作。\n" +
            "10. 手动恢复权限按钮：把 /mnt/vendor/persist/data 权限立即设为 700。如果守护未正确还原权限（比如寒枫被杀导致无后续 sleep），可手动触发恢复。\n" +
            "11. 真死指纹条件：只有当列表中所有游戏都还运行在后台（pidof 返回非空）就关机刷机清数据。正常使用场景下：必须划掉后台退出游戏，下一轮扫描检测到没有 pidof 时会自动清理并还原权限，不会触发真死。\n" +
            "12. 重启不会真死：守护脚本启动时会先显式 chmod 700 还原权限，重启后系统其它服务可正常写入 /mnt/vendor/persist/data。\n" +
            "13. 与 Root 隐藏的关系：两者都在 /data/adb/ 下创建工作目录、都用 SuSession 执行 root 命令、都用 nohup sh 持久运行。但 Root 隐藏是 zygisk mount bind 隐藏路径 + prop 伪装，防的是 Root 检测；游戏防设备标记是 chmod + 文件清理，防的是设备识别追踪，互不冲突可同时启用。"

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
