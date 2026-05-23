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

class GuideActivity : AppCompatActivity() {
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
        private const val DEFAULT_GUIDE_CONTENT =
            "APP作者：寒枫\n" +
            "拦截规则来源：寒枫、大萌主、海哥、不是DD啊、下个ID见、那个谁520\n\n" +
            "一、首次使用\n" +
                "1. 点击\"开启拦截\"后，应用会建立本地 VPN，首次使用需授权系统 VPN 权限。\n" +
                "2. Android 13 及以上机型建议允许通知权限，方便稳定显示前台服务通知。\n" +
                "3. 部分手机会限制应用列表读取，黑白名单或应用识别不完整时，请到系统设置里手动允许相关权限。\n\n" +
                "二、拦截核心效果\n" +
                "现在的拦截效果分为三个层级，从基础的域名拦截到增强过滤，覆盖了绝大多数广告场景：\n\n" +
                "1. DNS 层拦截（基础防线）- 极速、低耗\n" +
                "- 效果：App 请求已知广告域名的瞬间即被拦截，资源消耗极低。\n" +
                "- 优势：速度快，不费电。\n" +
                "- 局限：对于使用 HTTPDNS 或硬编码 IP 的广告无效（这部分靠下一级处理）。\n\n" +
                "2. MITM 增强过滤（核心防线）- 穿透隐身广告\n" +
                "- 拦截目标：HTTPS 加密广告、HTTPDNS 劫持过来的广告、HTTP/2 隐藏广告。\n" +
                "- 工作原理：拦截器会在中间识别目标流量，检查请求路径（如 /ad/, /banner/）和响应中的广告特征。\n" +
                "- 精准清洗：发现广告特征后，会对命中的 HTTP/1.1、HTTP/2 流量做拦截或替换处理。\n" +
                "- QUIC/HTTP3 精细处理：结合 DNS 命中域名、白名单和广告规则处理 UDP/443，广告目标会被阻断，必要时推动回退到 TCP 后再进入增强过滤。\n\n" +
                "3. 小说 App 专项净化（难点攻克）\n" +
                "- 拦截目标：番茄、七猫等 App 的底部悬浮 Banner 和激励视频。\n" +
                "- 效果：即使广告域名被伪装在主业务域名下（“保护域名”），只要 URL 路径带 /reward/ 等特征也能拦截。看小说基本无干扰。\n\n" +
                "三、MITM 模式说明\n" +
                "1. 主界面的 MITM 模式开关属于增强拦截模式，适合 DNS 模式下广告仍然明显的场景。\n" +
                "2. MITM（中间人）拦截覆盖的协议：\n" +
                "   - HTTP/1.1：明文请求，直接过滤广告内容\n" +
                "   - HTTP/2：多路复用加密流量，解密后识别广告特征\n" +
                "   - HTTPS：通过证书增强识别命中流量，重点处理广告相关请求和响应\n" +
                "   - QUIC/HTTP3：按域名和规则精细处理，广告目标阻断并推动回退到 TCP\n" +
                "3. 当前 HTTP/3/QUIC 的实现重点是识别、阻断和回退，不是直接解析所有 HTTP/3 内容。\n" +
                "4. 关闭 MITM 模式的优点：速度更稳、兼容性更高、对大多数正常联网场景影响最小。\n" +
                "5. 开启 MITM 模式的优点：除了域名拦截，还会继续识别请求路径、查询参数、响应头和响应体，对开屏广告、激励视频、广告 JSON 处理更强。\n" +
                "6. 对登录、账号、认证一类敏感域名，当前会优先保持兼容，避免因增强过滤影响登录和正常联网。\n" +
                "7. 开启前请先安装证书。若开启后遇到异常，关闭开关并重试可恢复标准模式。\n\n" +
                "四、性能与发热优化\n" +
                "1. 普通流量优先直通，只对命中广告规则、命中小说强拦目标或需要增强过滤的流量做深度处理。\n" +
                "2. QUIC/HTTP3 目标采用缓存和低频清理策略，减少高频 UDP/443 流量带来的额外耗电。\n" +
                "3. 统计与界面刷新已做节流处理，降低长时间运行时的页面抖动、卡顿和发热。\n" +
                "4. 如需更低功耗，可优先使用纯 DNS 模式，在广告明显时再开启 MITM 模式。\n\n" +
                "五、证书安装与管理\n" +
                "1. 存放位置：证书自动导出至手机内部存储的 Download/HanFeng/ 目录下，文件名为 HanFeng.crt。\n" +
                "2. 安装方式：应用只会导出证书文件，不会自动跳转系统安装页面。\n" +
                "3. 手动安装：打开手机\"设置\"，搜索\"安装证书\"（或在\"安全/密码与隐私\"中找到\"从存储设备安装\"），选择\"CA 证书\"后，前往 Download/HanFeng/ 目录选择 HanFeng.crt 完成安装。\n\n" +
                "六、主界面按钮说明\n" +
                "1. \"开启拦截 / 停止拦截\"：启动或停止本地 VPN 拦截服务。\n" +
                "2. \"MITM 模式\"开关：开启后进入增强拦截模式，并导出证书文件供手动安装。\n" +
                "3. \"使用说明\"：打开当前这份帮助文档。\n" +
                "4. \"黑白名单\"：进入应用白名单页面，加入白名单的应用会完全放行。\n" +
                "5. 状态卡片：显示当前工作状态、拦截模式、证书状态和版本号。\n\n" +
                "七、规则页按钮说明\n" +
                "1. \"添加规则\"：把输入框中的域名或规则文本加入规则库。\n" +
                "2. \"粘贴\"：把剪贴板内容追加到规则输入框。\n" +
                "3. \"清空\"：清空当前输入框内容。\n" +
                "4. \"导入/同步规则\"：可从本地文件导入规则，或从 GitHub 同步规则。\n" +
                "5. \"办卡\"：打开预设外部办卡页面。\n" +
                "6. \"加群反馈\"：拉起 QQ 加群入口提交反馈。\n" +
                "7. \"疑似广告域名\"：查看运行过程中采集到的可疑域名样本。\n" +
                "8. \"筛选非广告\"：识别疑似无效或低价值规则，辅助清理规则库。\n" +
                "9. 规则列表中的厂商分组：点击可展开或折叠对应厂商规则。\n" +
                "10. 规则项操作：可对单条规则重新分类或删除。\n" +
                "11. 多选栏中的 \"全选\"、\"删除所选\"、\"取消\"：用于批量选择、删除和退出多选。\n\n" +
                "八、规则导入支持格式\n" +
                "1. 支持纯文本规则文件，常见如 txt、conf、list、hosts、yaml、yml。\n" +
                "2. 支持 Hosts 格式，例如 `0.0.0.0 example.com`。\n" +
                "3. 支持纯域名列表。\n" +
                "4. 支持 AdGuard / ABP 域名型规则，例如 `||example.com^`、`@@||example.com^`。\n" +
                "5. 支持 dnsmasq 类规则，例如 `address=/example.com/`、`ipset=/example.com/`。\n" +
                "6. 支持部分结构化域名规则，例如 `domain,example.com`、`domain-suffix,example.com`。\n" +
                "7. 支持正则规则和 Cosmetic 规则的导入与统计。\n" +
                "8. 对复杂 URL 规则、脚本注入、请求头改写等当前架构难以安全落地的规则，会自动跳过，并在导入分析页展示。\n\n" +
                "九、统计页按钮说明\n" +
                "1. 统计卡片：显示今日拦截、累计拦截、DNS 总拦截、MITM 总拦截、请求拦截和累计节省流量。\n" +
                "2. 排行榜卡片中的 \"查看完整榜单\"：进入完整排行页面查看更详细的数据。\n\n" +
                "十、规则兼容\n" +
                "1. 规则导入兼容常见 Hosts、AdGuard 域名型规则、正则规则、Cosmetic 规则和部分结构化域名规则。\n" +
                "2. 已支持部分请求改写修饰符，如 `removeparam`、`csp`，其余高风险修饰符按兼容性继续保守处理。\n\n" +
                "十一、排查建议\n" +
                "1. 某个应用联网异常时，优先把它加入白名单测试。\n" +
                "2. 若出现登录失败、验证码异常、账号页加载不完整，先关闭 MITM 模式后重试，再决定是否加入白名单。\n" +
                "3. 某些旧广告通常和本地缓存有关，建议清理对应应用缓存、重开应用后再测试。\n" +
                "4. 如有 BUG 或建议，可通过左界面的\"加群反馈\"入口提交。"

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
