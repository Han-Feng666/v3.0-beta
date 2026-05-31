# 本应用支持的规则格式

本文档根据当前 `RuleRepository` 的解析逻辑自动整理，说明本应用当前可以稳定导入和执行的规则类型。

## 设计原则

- 优先支持 DNS 级可安全落地的域名规则
- 优先保证联网稳定，不把复杂网页规则误降级成整域名封禁
- 对不适合当前本地 VPN DNS 架构的规则，自动跳过并保留为暂不支持样本

## 当前支持的规则

## MITM 与增强过滤说明

- 开启 MITM 并正确安装证书后，应用会对命中的 HTTP/HTTPS/HTTP2 流量做更深入的广告识别。
- 当前重点增强场景包括：开屏广告、信息流广告、评论区插入广告、回复楼层广告、推荐卡片广告、消息中心广告、小说激励解锁广告、部分同域广告 JSON。
- 统计页中的 `MITM 总拦截` 只统计证书解密后真实命中的增强过滤结果。
- 系统推广治理和一键治理已安装推广项依赖 Shizuku，用于处理系统推广包、负一屏推荐、浏览器推荐、主题壁纸推荐等系统级推广来源。

### 1. 纯域名

支持示例：

```text
ads.example.com
tracker.example.org
```

### 2. Hosts 格式

支持示例：

```text
0.0.0.0 ads.example.com
127.0.0.1 tracker.example.org
:: ads.example.com
::1 tracker.example.org
```

### 3. AdGuard / ABP 域名锚点规则

支持示例：

```text
||ads.example.com^
@@||cdn.example.com^
|https://ads.example.com|
|http://tracker.example.org|
```

说明：

- 仅支持可直接提取为域名的安全子集
- 如果规则包含路径、复杂通配或请求上下文依赖，则会跳过

### 4. AdGuard DNS 安全修饰符

支持示例：

```text
||ads.example.com^$dnstype=A
||ads.example.com^$dnstype=AAAA|HTTPS
||ads.example.com^$important
||ads.example.com^$match-case
||ads.example.com^$badfilter
@@||cdn.example.com^$dnstype=CNAME
```

说明：

- `dnstype=` 当前支持：`A` `NS` `CNAME` `SOA` `PTR` `MX` `TXT` `AAAA` `SRV` `NAPTR` `SVCB` `HTTPS` `CAA` `ANY`
- `ANY` 会被视为不限制记录类型
- `badfilter` 当前用于关闭已导入的同域名规则或对应记录类型规则
- 额外支持的高频修饰符：`app=` `src-port=` `dst-port=` `path=` `domain=` `denyallow=` `removeparam=` `csp=` `urlblock` `first-party` `1p` `third-party` `3p`
- `first-party` / `1p` 与 `third-party` / `3p` 当前主要在 URL/MITM 请求上下文匹配中生效

### 5. dnsmasq 风格域名规则

支持示例：

```text
address=/ads.example.com/0.0.0.0
server=/tracker.example.org/1.1.1.1
local=/example.com/
ipset=/ads.example.com/adblock
nftset=/tracker.example.org/adblock
```

说明：

- 当前只提取其中的域名部分
- 不直接执行 dnsmasq 的目标 IP、集合或路由语义

### 6. 结构化域名规则

支持示例：

```text
domain,ads.example.com
domain-suffix,example.com
host,ads.example.com
hostname-suffix,example.com
domain-wildcard,*.example.com
full,ads.example.com
domain:ads.example.com
domain-suffix=example.com
```

当前支持的结构化类型：

- `domain-suffix`
- `domain`
- `host-suffix`
- `host`
- `hostname-suffix`
- `suffix`
- `domain-wildcard`
- `host-wildcard`
- `hostname-wildcard`
- `full`
- `full-domain`
- `hostname`
- `host-full`
- `hostname-full`
- `domain-full`
- `domain-exact`
- `host-exact`

### 6.1 结构化应用与端口规则

支持示例：

```text
package-name,com.ss.android.article.news
process-name,com.dragon.read
dst-port,443
src-port,53
PACKAGE-NAME,com.qimao.reader
PROCESS-NAME,com.dragon.read
DEST-PORT,443
SRC-PORT,853
```

说明：

- `package-name` / `process-name` 会映射为应用维度规则
- `dst-port` / `src-port` 会映射为端口维度规则
- 这类规则只有在当前运行态能拿到应用名或端口时才会生效

### 7. YAML 列表前缀

支持示例：

```text
- ads.example.com
* tracker.example.org
```

## 当前不支持或仅保留样本的规则

以下规则会被跳过，避免误伤正常网络：

### 1. Cosmetic 规则

例如：

```text
example.com##.ad-banner
example.com#@#.ad-banner
```

### 2. 正则规则

例如：

```text
/^ad[0-9]+\.example\.com$/
```

### 3. 依赖请求上下文的高级修饰符

当前不支持的代表项：

- `from`
- `to`
- `redirect`

说明：

- 这些修饰符需要浏览器上下文、请求来源、重定向语义或更高层代理能力
- 当前本应用是本地 VPN DNS 拦截，不做不安全的强行降级处理

### 4. 非域名型结构化规则

当前不会导入的代表项：

- `keyword`
- `domain-keyword`
- `host-keyword`
- `domain-regex`
- `host-regex`
- `url-regex`
- `ip-cidr`
- `ip-cidr6`
- `src-ip-cidr`
- `geoip`
- `geosite`
- `rule-set`
- `process-path`
- `user-agent`
- `inbound`
- `network`
- `protocol`
- `and`
- `or`
- `not`

## 同步规则按钮的来源

应用内“同步规则”按钮当前会优先从以下仓库位置下载：

- 当前开发分支：`260425-feat-improve-adguard-blocking`
- 回退分支：`main`

同步文件：

- `app/src/main/res/raw/default_safe_ad_rules.txt`
- `domestic-safe-ad-sdk-rules.txt`

## 后续扩展方向

- 继续补充可安全落地的 AdGuard DNS 修饰符
- 增强规则优先级与冲突处理
- 增强规则来源、命中原因和导入分析展示
