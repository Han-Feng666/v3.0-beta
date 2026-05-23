# 本应用支持的规则格式

本文档根据当前 `RuleRepository` 的解析逻辑自动整理，说明本应用当前可以稳定导入和执行的规则类型。

## 设计原则

- 优先支持 DNS 级可安全落地的域名规则
- 优先保证联网稳定，不把复杂网页规则误降级成整域名封禁
- 对不适合当前本地 VPN DNS 架构的规则，自动跳过并保留为暂不支持样本

## 当前支持的规则

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

- `third-party`
- `domain`
- `app`
- `denyallow`
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
- `process-name`
- `process-path`
- `package-name`
- `user-agent`
- `dst-port`
- `src-port`
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
