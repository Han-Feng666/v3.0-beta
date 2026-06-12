# 寒枫规则导出功能使用指南

## 功能概述

寒枫 App 现支持将本地规则库导出为 txt 格式文件，可在 AdGuard、AdGuard Home、Pi-hole 等工具中使用。

## 导出格式

### 1. AdGuard 风格（推荐）

**特点**：
- 完整的 AdGuard 格式规则
- 包含所有修饰符（$third-party、$app、$path 等）
- 按供应商分组
- 带元数据头和说明

**使用场景**：
- AdGuard / AdGuard Home
- Pi-hole（需要转换为 dnsmasq 格式）
- 其他支持 AdGuard 格式的工具

**示例文件头**：
```
! Title: 寒枫广告 blocking 规则导出
! Description: 从寒枫 App 导出的自定义广告拦截规则
! Homepage: https://github.com/Han-Feng666/v3.0-beta
! License: MIT
! Version: 2026-06-12 15:30:45
!
! 导出时间：2026-06-12 15:30:45
! 规则总数：1234
! 来源：寒枫 App 本地规则库
!
! 使用说明：
! 1. 可在 AdGuard、AdGuard Home、Pi-hole 等工具中使用
! 2. 部分寒枫特有功能（如 app= 限定）可能不被其他工具支持
! 3. 如导致 App 功能异常，请将相关域名加入白名单
!
```

### 2. 纯域名列表

**特点**：
- 每行一个域名
- 无修饰符
- 格式简洁

**使用场景**：
- hosts 文件
- 简单的 DNS 黑名单
- 需要快速导入的场景

**示例**：
```
# 寒枫广告 blocking 规则 - 纯域名列表
# 导出时间：2026-06-12 15:30:45
# 域名总数：1234

ad.example.com
tracking.example.com
banner.example.org
```

## API 使用

### Kotlin 代码示例

```kotlin
import com.HanFeng.data.RuleRepositoryExport
import java.io.File

// 导出 AdGuard 风格规则
val outputFile = File("/sdcard/Download/hanfeng-rules.txt")
val count = RuleRepositoryExport.exportRulesToTxt(
    context = this,
    outputFile = outputFile,
    includeWhitelist = false,      // 不包含@@白名单规则
    includeSmartScored = false     // 不包含智能评分规则
)
println("导出成功：$count 条规则")

// 导出纯域名列表
val domainFile = File("/sdcard/Download/hanfeng-domains.txt")
val domainCount = RuleRepositoryExport.exportRulesAsDomainList(
    context = this,
    outputFile = domainFile
)
println("导出成功：$domainCount 个域名")
```

### 参数说明

| 参数名称 | 类型 | 默认值 | 说明 |
|---------|------|--------|------|
| context | Context | - | Android 上下文 |
| outputFile | File | - | 输出文件路径 |
| includeWhitelist | Boolean | false | 是否包含@@白名单规则 |
| includeSmartScored | Boolean | false | 是否包含智能评分规则 |

## 导出规则示例

### 完整规则（AdGuard 风格）

```
! ===========================================================================
! 通用广告/追踪 (Generic Ad/Tracking)
! ===========================================================================

||ad.example.com$third-party
||tracking.example.com$app=com.xxx|com.yyy
||banner.example.org$path=/ad/
||stats.example.com$dns-type=A|AAAA

! ===========================================================================
! 穿山甲/字节系
! ===========================================================================

||pangolin-sdk-toutiao.com
||pangle.com$subdomain
||gromore.cn

! ===========================================================================
! 导出完成
! 总计：1234 条规则
! ===========================================================================
```

### 纯域名列表

```
ad.example.com
banner.example.org
gromore.cn
pangle.com
pangolin-sdk-toutiao.com
stats.example.com
tracking.example.com
```

## 在 AdGuard 中使用

### AdGuard Desktop/Mobile

1. 打开 AdGuard 设置
2. 进入「用户规则」或「自定义规则」
3. 点击「导入」
4. 选择导出的 txt 文件
5. 确认导入

### AdGuard Home

1. 打开 AdGuard Home 管理界面
2. 进入「过滤器」→「DNS 屏蔽列表」
3. 点击「添加屏蔽列表」
4. 选择「从文件上传」或使用 URL
5. 如果导出文件在本地，可以先上传到 Web 服务器，然后使用 URL 导入

## 在 Pi-hole 中使用

### 方法 1：转换为 dnsmasq 格式

Pi-hole 使用 dnsmasq 格式（`address=/domain/0.0.0.0`），需要转换：

```bash
# 假设 hanfeng-domains.txt 是纯域名列表
while read domain; do
    echo "address=/$domain/0.0.0.0"
done < hanfeng-domains.txt > hanfeng-pihole.conf

# 复制到 Pi-hole 的 custom.list
sudo cp hanfeng-pihole.conf /etc/dnsmasq.d/03-custom.list
sudo systemctl restart pihole-FTL
```

### 方法 2：使用 AdGuard 格式（Pi-hole v6+）

Pi-hole v6 开始支持 AdGuard 格式，可以直接导入 txt 文件。

## 在 hosts 文件中使用

### Windows

1. 导出的纯域名列表保存为 `hosts_hanfeng.txt`
2. 在每行前添加 `0.0.0.0`（可以用文本编辑器批量替换）
3. 复制内容到 `C:\Windows\System32\drivers\etc\hosts`

### Linux/macOS

```bash
# 将域名列表转换为 hosts 格式
sed 's/^/0.0.0.0 /' hanfeng-domains.txt >> /etc/hosts
```

## 注意事项

1. **规则兼容性**：
   - `app=` 限定符只有寒枫等少数工具支持
   - `path=`、`keyword=` 是寒枫特有功能
   - 标准 DNS 拦截（`||domain.com`）在所有工具中都支持

2. **更新频率**：
   - 建议每周导出一次
   - 规则变化频繁时可以每天导出
   - 导出后记得在其他工具中刷新规则

3. **性能影响**：
   - 规则过多可能影响 DNS 解析速度
   - AdGuard Home 建议不超过 10 万条规则
   - Pi-hole 建议不超过 5 万条规则

4. **误杀处理**：
   - 如导致某些 App 功能异常，请将相关域名加入白名单
   - 可使用 `@@domain.com` 格式豁免特定域名
   - 导出时设置 `includeWhitelist=true` 可包含已有白名单

## 文件大小估算

| 规则数量 | AdGuard 风格 | 纯域名列表 |
|---------|------------|-----------|
| 1,000   | ~100 KB    | ~20 KB    |
| 10,000  | ~1 MB      | ~200 KB   |
| 100,000 | ~10 MB     | ~2 MB     |

## 常见问题

### Q: 导出的规则在其他工具中不工作？
A: 确保目标工具支持 AdGuard 格式。如不支持，请使用纯域名列表格式。

### Q: 如何自动定期导出？
A: 可以编写定时任务调用导出 API，或在 App 中增加自动导出功能。

### Q: 导出的规则太多，如何精简？
A: 可以：
- 只导出特定供应商的规则（修改代码过滤）
- 使用纯域名列表格式（体积更小）
- 定期清理过期规则

### Q: 如何分享导出的规则？
A: 可以：
- 上传到 GitHub Gist
- 分享到 Pastebin
- 搭建自己的规则服务器
- 在论坛/社群分享

## 开发者

如需在代码中使用导出功能，请参考：
- `RuleRepositoryExport.kt` - 导出功能实现
- `BlockRule.kt` - 规则数据模型

## 许可证

MIT License

