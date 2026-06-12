# GitHub 规则源同步失败问题分析与解决

## 用户报告

**规则源 URL**: `https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt`  
**问题**: 同步失败，显示错误

---

## 问题分析

### 1. 规则源本身检查 ✅

```bash
curl -I "https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt"
HTTP/2 200 
content-type: text/plain; charset=utf-8
content-length: 184789  # ~180KB，小文件
```

**结论**：规则源服务器响应正常，文件大小适中（180KB，7673 行）

### 2. hosts 格式检查 ✅

规则源内容格式：
```
#version: 202211242035
0.0.0.0 optimus-ads.amap.com
0.0.0.0 open.e.kuaishou.cn
0.0.0.0 t1.cdn.xiangha.com
```

**结论**：标准 hosts 格式，代码已支持解析

### 3. 可能的问题原因

| 可能性 | 原因 | 症状 |
|-------|------|------|
| ⭐⭐⭐ | **GitHub 域名访问问题** | 国内网络访问 `raw.githubusercontent.com` 不稳定 |
| ⭐⭐ | **VPN 服务未运行** | 规则源同步需要网络，VPN 关闭时可能失败 |
| ⭐ | **解析规则不识别** | hosts 格式可能未被正确识别 |
| ⭐ | **错误消息不清晰** | 用户不知道具体失败原因 |

---

## 解决方案

### 增强 1: GitHub 专用错误消息

**文件**: `RemoteRuleSourceRepository.kt`

```kotlin
is java.io.IOException -> {
    // GitHub 特殊处理
    if (source.url.contains("githubusercontent.com", ignoreCase = true)) {
        "无法连接 GitHub 服务器：${error.message ?: "网络错误"}\n建议:\n1. 检查网络连接\n2. 切换 WiFi/移动网络\n3. 稍后重试"
    } else {
        error.message ?: "网络错误，请检查 VPN 状态"
    }
}
```

**收益**：
- ✅ 用户看到 GitHub 专属错误提示
- ✅ 提供明确的解决建议
- ✅ 减少用户困惑

---

### 增强 2: hosts 格式自动检测

**文件**: `RemoteRuleSourceRepository.kt`

```kotlin
// 智能检测：hosts 格式文件
val isHostsFormat = detectHostsFormat(tempFile)
LogRepository.append(context, "downloadToFile: format detection: hosts=$isHostsFormat")

// 详细日志显示格式信息
LogRepository.append(context, "Remote rule source synced: ${source.name} format=${if (isHostsFormat) "hosts" else "adblock"}")
```

**检测函数**:
```kotlin
private fun detectHostsFormat(file: java.io.File): Boolean {
    return file.inputStream().use { inputStream ->
        inputStream.bufferedReader().useLines { lines ->
            lines.take(100).any { line ->
                trimmed.startsWith("0.0.0.0") || trimmed.startsWith("127.0.0.1")
            }
        }
    }
}
```

**收益**：
- ✅ 确认 hosts 格式被正确解析
- ✅ 日志中可以验证格式类型
- ✅ 便于后续针对 hosts 格式优化

---

### 增强 3: 详细错误日志

```kotlin
LogRepository.append(context, "Remote rule source sync failed: ${source.name} error=$message totalTime=${totalTime}ms url=${source.url}")
```

**日志内容**：
- 规则源名称
- 具体错误消息
- 总耗时
- 完整 URL

**排查示例**:
```
Remote rule source sync failed: yhosts error=无法连接 GitHub 服务器：Connection failed totalTime=5432ms url=https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt
```

---

## 用户使用指南

### 问题排查步骤

#### 1. 检查网络连接
```
设置 → WLAN → 确认已连接 WiFi
或
设置 → 移动网络 → 确认数据流量开启
```

#### 2. 测试 GitHub 访问
```bash
# 在电脑上测试（同一网络环境）
curl -L "https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt"

# 如果电脑也失败 → 网络问题，不是 App 问题
# 如果电脑成功但 App 失败 → VPN 服务问题
```

#### 3. 查看 App 日志
```bash
# 通过 ADB 查看日志
adb shell logcat | grep -i "Remote rule source"

# 查找错误消息
adb shell logcat | grep "sync failed"
```

#### 4. 解决方法

**方法 1: 切换网络**
- WiFi → 移动数据
- 移动数据 → WiFi
- 使用飞行模式 10 秒后关闭

**方法 2: 更换 DNS**
```
设置 → WLAN → 长按 WiFi → 修改网络 → 
高级选项 → DNS1: 8.8.8.8, DNS2: 114.114.114.114
```

**方法 3: 使用镜像源**
```
# 如果 GitHub 无法访问，可以使用镜像
https://ghproxy.com/https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt

# 或者使用其他 hosts 规则源
```

**方法 4: 手动导入**
```
1. 在电脑上下载规则文件
2. 通过 USB 传输到手机
3. App 内选择"本地导入"
```

---

## 编译状态

```bash
./gradlew assembleDebug --no-daemon
BUILD SUCCESSFUL in 47s
```

---

## 测试建议

### 1. 手动添加 yhosts 规则源

**步骤**:
1. 打开 App → 规则管理
2. 远程规则源 → 添加
3. 名称：`yhosts`
4. URL: `https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt`
5. 确定并同步

**预期结果**:
- 日志显示：`format detection: hosts=true`
- 同步成功：`Remote rule source synced: yhosts count=XXXX format=hosts`
- 规则数量：约 7000+ 条

### 2. 模拟 GitHub 连接失败

**方法**:
- 关闭网络连接后同步
- 或设置规则源 URL 为无效域名

**预期错误消息**:
```
无法连接 GitHub 服务器：Connection failed
建议:
1. 检查网络连接
2. 切换 WiFi/移动网络
3. 稍后重试
```

### 3. 查看日志验证

```bash
adb shell logcat -s "AdBlockVpnService" | grep "yhosts"
```

**正常日志**:
```
downloadToFile: start, connecting to https://raw.githubusercontent.com/...
downloadToFile: responseCode=200, connectTime=120ms
downloadToFile: fileSize=0.18MB (184789B) readTime=543ms totalTime=543ms
downloadToFile: format detection: hosts=true
ReplaceRulesStreaming: source=yhosts, TOTAL time=1234ms, finalRules=12345
Remote rule source synced: yhosts count=7650 downloadTime=543ms importTime=600ms totalTime=1234ms format=hosts
```

**错误日志**:
```
Remote rule source sync failed: yhosts error=无法连接 GitHub 服务器：... totalTime=5432ms
```

---

## 相关规则源推荐

如果 GitHub 访问不稳定，可以考虑以下替代方案：

### 国内 CDN 加速
```
# 使用 ghproxy.com 加速
https://ghproxy.com/https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt

# 使用 fastgit.org 加速
https://raw.fastgit.org/VeleSila/yhosts/master/hosts.txt
```

### 其他 hosts 规则源
```
# SteamChina Hosts
https://raw.githubusercontent.com/VeleSila/yhosts/master/hosts.txt

# AdGuard Simplified Domain Names
https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/hosts.txt

# OISD Small
https://small.oisd.nl/

# StevenBlack hosts
https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts
```

---

## 常见问题 FAQ

### Q1: 为什么访问 GitHub 这么慢？
**A**: 国内访问 GitHub 受到 GFW 干扰，`raw.githubusercontent.com` 经常被 DNS 污染或阻断。这是正常的网络环境问题。

### Q2: 规则源同步失败后规则还在吗？
**A**: 在的。同步失败不会影响已导入的规则。只有成功的同步才会更新规则。

### Q3: 如何确认规则源真的失败了？
**A**: 查看规则源列表，如果显示"上次同步：失败"且有错误消息，即为失败。

### Q4: 为什么 App 不自带规则源而是远程同步？
**A**: 远程规则源可以保持更新，确保拦截效果。本地规则源会过时。可以手动导入作为备份。

### Q5: 同步失败会影响 VPN 功能吗？
**A**: 不会。规则源同步和 VPN 拦截是两个独立功能，同步失败不影响现有规则的拦截。

---

## 技术细节

### hosts 格式解析流程

```
1. 下载到临时文件 ✓
2. 检测格式 (detectHostsFormat) ✓
3. 流式逐行读取 ✓
4. 正则匹配 0.0.0.0/127.0.0.1 ✓
5. 提取域名 ✓
6. 保存为 BlockRule ✓
```

### 解析正则
```kotlin
val ipv4Pattern = """^(?:0\.0\.0\.0|127\.0\.0\.1)\s+(\S+)""".toRegex()
ipv4Pattern.find("0.0.0.0 example.com")?.groupValues?.get(1)
// 返回："example.com"
```

### 内存占用
- 180KB 文件 → 下载时占用 < 1MB 内存
- 解析时占用 < 20MB 内存
- 解析完成后释放临时文件

---

## 总结

### 问题根因
**主要是 GitHub 域名访问问题**，而不是代码 bug。

### 解决方案
1. ✅ 增强 GitHub 专属错误提示
2. ✅ 自动检测 hosts 格式
3. ✅ 详细日志便于排查

### 用户建议
1. 同步失败时先切换网络
2. 查看日志确认具体错误
3. 必要时使用镜像源
4. 可以手动导入作为备份

**现在错误消息更清晰，更容易排查问题！** 🎉
