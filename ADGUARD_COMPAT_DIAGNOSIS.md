# AdGuard 规则拦截失败诊断报告

## 问题现象
同样的规则源，AdGuard 可以正常拦截广告，但寒枫拦截失败。

## 根本原因分析

### 架构差异

**AdGuard 的工作方式**：
- AdGuard 是流量代理架构，所有 HTTP/HTTPS 请求都经过它的代理引擎
- 即使 DNS 不拦截，当浏览器/App 成功解析 DNS 并发出 HTTP 请求时，AdGuard 仍可以在 HTTP/2 或 HTTPS 层拦截并阻止内容加载

**寒枫的工作方式**：
- 寒枫是 DNS+MITM 混合架构：
  1. DNS 层：拦截域名解析请求，返回 0.0.0.0 或 sinkhole 地址（**第一道防线**）
  2. MITM 层：开启 HTTPS 解密后，分析 HTTP/2 请求路径和响应体（**第二道防线**）

### 可能的失败点

#### 1. 远程规则没有被导入（最可能）

检查步骤：
1. 打开寒枫 App 设置页 → 规则管理
2. 查看远程规则源的"最后同步时间"和"已导入条数"
3. 如果显示"未导入规则"或条数为 0，说明规则下载/解析失败

解决方案：
- 手动点击"同步全部规则源"
- 如果同步失败，查看"应用日志"寻找错误原因（可能是网络问题或 DNS 配置问题）
- 切换到移动数据/WiFi 重试

#### 2. 白名单过度保护

寒枫保护大量业务域名确保 App 不断网：
- 视频 CDN（`douyinvod.com`、`video.qq.com`、`iqiyi.com`等）
- 社交核心（`servicewechat.com`、`alipay.com`）
- 支付金融（`webank.com`、`tenpay.com`）
- 游戏服务（`dnf.qq.com`、`game.163.com`）

问题：如果广告域名与这些业务域名共用根域名，可能被白名单穿透。

检查步骤：
1. 打开寒枫 App → 规则管理 → 可疑域名
2. 查看被识别但未拦截的域名是否命中白名单

解决方案：
- 如果发现大量广告域名被白名单保护，可以在设置中关闭"保守保护模式"（如果存在）
- 或手动将特定域名添加到黑名单

#### 3. MITM 未启用或配置不当

如果远程规则导入成功且 DNS 拦截正常，但 App 内广告仍显示，可能是因为：
- **广告在 HTTPS 响应体内被动态注入**
- **HTTP/2 流量不走 DNS 拦截**
- **响应体广告识别不够积极**

解决方案：
1. 确保已启用"HTTPS 解密（MITM）模式"
2. 安装寒枫的 CA 证书（如果系统提示）
3. 在"应用级规则"中，为特定 App（如番茄小说、七猫小说）启用强拦模式

#### 4. 规则解析不兼容

寒枫支持 AdGuard 风格的 DNS 规则，但不支持所有修饰符：
- **支持**：`domain=`, `denyallow=`, `path=`, `app=`, `dns-type=`, `dns-exclude-type=`, `|`路径修饰符, `#$#` CSS 隐藏
- **降级支持**：`##` CSS 隐藏（仅 MITM）、`$$` 隐藏（仅 MITM）
- **不支持**：`$redirect=`、`$script`、`$stylesheet`、`$frame`（这些需要浏览器语义）

检查步骤：
1. 打开寒枫 App → 规则管理
2. 查看远程规则源的规则列表（如果支持查看）
3. 如果大部分规则是 `##`、`$$`、`$redirect=` 修饰符，这些在 DNS 层不生效

解决方案：
- 使用更纯粹的 DNS 规则源，如：
  - `AdGuard Simplified Domain Names`（纯域名列表）
  - `StevenBlack hosts`（hosts 格式）
  - `OISD`（保守 DNS 规则集）
- 或添加多个规则源覆盖不同类型的规则

## 推荐的修复步骤

### 第一步：检查规则导入状态
1. 打开寒枫 App → 设置 → 规则管理
2. 查看远程规则源的"最后同步时间"是否是最近的
3. 查看"已导入条数"是否 > 0
4. 如果导入失败，先解决同步问题

### 第二步：启用 MITM 模式
1. 打开寒枫 App → 设置 → HTTPS 解密
2. 开启 HTTPS 解密（MITM 模式）
3. 按提示安装 CA 证书
4. 重启拦截服务

### 第三步：检查应用级规则
1. 打开寒枫 App → 设置 → 应用规则管理（如果存在）
2. 为广告重灾区 App（番茄小说、七猫小说、抖音等）启用"小说强拦模式"或等效增强
3. 或直接在主规则列表中添加该 App 的包名限定规则

### 第四步：补充规则源
1. 打开寒枫 App → 设置 → 规则源管理
2. 添加以下额外规则源（如果支持）：
   - `https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt`
   - `https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts`
   - `https://raw.githubusercontent.com/oisd/oisd/main/oisd_big.txt`
3. 点击"同步全部规则源"

### 第五步：查看诊断日志
1. 打开寒枫 App → 设置 → 应用日志
2. 查看是否有类似以下的日志：
   - `Blocked DNS query domain=xxx.xxx.com app=com.xxx`（DNS 层拦截）
   - `Blocked HTTPS connection domain=xxx.xxx.com app=com.xxx`（MITM 层拦截）
3. 如果只有`Passed` 而没有`Blocked`，说明规则匹配失败

## 快速验证

测试以下域名是否被拦截：

```bash
# 使用 ping 测试 DNS 拦截（应该解析到 0.0.0.0 或失败）
ping ad.qq.com
ping gdt.qq.com
ping adpartner.qq.com

# 查看寒枫 App 的拦截日志
# 应该看到类似 "Blocked DNS query domain=gdt.qq.com app=com.xxx" 的记录
```

如果上述 `ping` 命令能够正常解析出 IP 地址（如 `183.3.226.35`），说明 DNS 拦截失败。

## 联系支持

如果按上述步骤操作后仍无法拦截，请提供：
1. 寒枫版本号
2. 已添加的远程规则源列表
3. 规则源的同步日志（复制设置页 → 应用日志内容）
4. 具体哪个 App 的哪个广告拦截失败（如"番茄小说第 X 章末尾的横幅广告"）

这将帮助我们定位具体是哪个环节的问题。
