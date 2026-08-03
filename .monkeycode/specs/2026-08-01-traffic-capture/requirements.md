# Requirements Document

## Introduction

为 HanFeng 广告拦截 App 增设第四个主界面"抓包"Tab, 提供对齐 HTTPCanary 的完整抓包能力: 实时查看、TLS 信息查看、修改请求/响应、重放、筛选搜索、抓包模板、多语言命令导出、HAR 导出、悬浮窗实时列表。功能依托现有 VPN + HTTPS MITM 管线(`AdBlockVpnService` + `HttpMitmFilter` + `TlsMitmSessionManager` + `TlsMitmContextFactory` + `CertificateAuthorityManager`), 与广告拦截共用同一套本地 CA 与已安装的根证书, 开启抓包时强制激活 HTTPS MITM 路径。抓包对象可按 App 选择或全量; 开启时由前台 Service 通过 WindowManager 渲染可拖动的实时列表悬浮窗。抓包记录常驻内存环形缓冲, 关闭即清空。

"修改请求/响应"通过在 `HttpMitmFilter` 入口处设置断点实现: 用户在抓包 Tab 进入断点期间, `HttpMitmFilter` 暂停把该请求/响应转给上游/下游, 用异步 `Channel` 等待用户在详情页应用编辑; 用户放行后 `HttpMitmFilter` 用草稿替换原字节并继续转发。重放则在 VPN 内部以独立客户端 socket 直发目标, 不影响设备其它流量。

## Glossary

- **抓包模式 (Capture Mode)**: App 内置的全设备/选定 App 的 HTTP/HTTPS 流量观察开关,激活后流量被旁路进抓包缓冲。
- **抓包 Tab**: MainActivity ViewPager 中的第 4 个页面(现有 3 个: 规则、主页、统计)。
- **抓包悬浮窗 (Capture Floating Window)**: 抓包启用时由前台 Service 通过 WindowManager 渲染的可拖动悬浮窗,实时滚动显示最近若干条请求。
- **HTTPS MITM**: 项目现有的 HTTPS 中间人拦截路径(`HttpsMitmController` + `TlsMitmContextFactory` + `CertificateAuthorityManager`)。
- **本地 CA**: 项目 `CertificateAuthorityManager` 自签生成的根证书,所有 HTTPS 解码共用。
- **环形缓冲 (Ring Buffer)**: 固定容量的 FIFO 缓冲,溢出丢弃最旧条目。
- **HAR (HTTP Archive)**: HAR 1.2 规范的 JSON 格式抓包导出文件。
- **cURL**: 单条请求导出为 curl 命令文本。
- **抓包条目 (Capture Entry)**: 一条请求-响应配对的聚合记录,含 method/host/path/status/timing/headers/body 摘要。

## Requirements

### Requirement 1: 抓包 Tab 入口

**User Story:** AS 用户, 我想在 App 主界面有一个专门的抓包入口, 以便随时启用/查看抓包与结果

#### Acceptance Criteria

1. THE `MainPagerAdapter` SHALL 提供 4 个 Tab, 顺序为: 规则、主页、统计、抓包
2. WHEN 用户切到抓包 Tab, the `MainActivity` SHALL 展示 `CaptureFragment`, 该 Fragment 包含启用开关、抓包对象范围选择、抓包列表、导出按钮
3. WHILE 抓包未启用, the `CaptureFragment` SHALL 显示空态说明 + 启用按钮, 不展示历史记录(历史记录不持久化)
4. WHEN 抓包从启用切到停用, the `CaptureFragment` SHALL 清空环形缓冲, 不保留任何条目在内存外

### Requirement 2: 抓包对象范围

**User Story:** AS 调试者, 我想选择只抓某几个 App 或全量抓, 以便在不同调试场景下控制噪音

#### Acceptance Criteria

1. WHEN 用户启用抓包, the `CaptureFragment` SHALL 默认进入"按 App 抓包"模式, 并弹出让用户从已安装第三方 App 列表中勾选目标
2. WHILE 处于"按 App 抓包"模式, the `CaptureController` SHALL 仅将所选 App 的 HTTP/HTTPS 流量写入环形缓冲, 其它 App 流量按现有规则照常处理
3. WHEN 用户在抓包设置中切换到"全量抓包"模式, the `CaptureController` SHALL 将所有 App 的 HTTP/HTTPS 流量都写入环形缓冲
4. WHILE 处于全量抓包模式, the `CaptureController` SHALL 维持现有广告拦截决策(拦截/放行)独立运行, 不因抓包改变拦截结果

### Requirement 3: HTTPS 解码与证书共用

**User Story:** AS 用户, 我希望抓包与广告拦截共用同一根证书, 以便只安装一次证书就能同时驱动两个功能

#### Acceptance Criteria

1. WHEN 用户启用抓包, the `CaptureController` SHALL 强制激活 HTTPS MITM 路径, 复用 `TlsMitmContextFactory` + `CertificateAuthorityManager` 现有 CA
2. WHEN 抓包启用但本地 CA 尚未生成或证书未安装, the `CaptureFragment` SHALL 显示与现有 HTTPS MITM 相同的引导文案(生成 CA / 安装证书)
3. WHILE 抓包启用, the `CaptureController` SHALL 对所有抓包目标(无论是否原本要拦截)都走 HTTPS MITM 解码, 不丢弃明文
4. WHEN 抓包停用, the `CaptureController` SHALL 不主动关闭 HTTPS MITM 全局开关, 保持广告拦截 MITM 状态不变

### Requirement 4: 抓包条目采集

**User Story:** AS 调试者, 我想在每条 HTTP/HTTPS 流量经过 VPN 时被采样成结构化记录, 以便后续查看与导出

#### Acceptance Criteria

1. WHILE 抓包启用且流量匹配抓包对象, the `CaptureController` SHALL 在 `HttpMitmFilter` 解码请求/响应后, 以事务 ID 为 key 聚合为一组 CaptureEntry 字段: method/host/path/scheme/httpVersion/requestHeaders/responseStatus/responseHeaders/timingMs/appName/bodyPreview
2. WHEN 一条流量的响应未到达, the `CaptureController` SHALL 先写入只有请求字段的条目, 响应到达后补全状态码与响应头
3. WHEN 环形缓冲已满且有新条目到达, the `CaptureController` SHALL 丢弃最旧条目, 保留容量上限默认 500 条(可在设置中调整 100/500/1000)
4. IF 单条 body 大小超过 64KB, the `CaptureController` SHALL 仅保留前 64KB 作为预览, 同时在条目上标记 `bodyTruncated=true`

### Requirement 5: 实时列表悬浮窗

**User Story:** AS 用户, 我想在开启抓包时屏幕上有个悬浮窗实时看到每条请求, 以便像 HTTPCanary 一样不用回到 App 也能观察

#### Acceptance Criteria

1. WHEN 抓包启用, the `CaptureFloatingService` SHALL 经由 `WindowManager` 在屏幕上层创建一个半透明可拖动悬浮窗, 初始位置在屏幕顶部偏下
2. WHILE 抓包持续, the `CaptureFloatingService` SHALL 在悬浮窗内以列表形式实时滚动展示最近 5 条 CaptureEntry(每行: method(缩写) + host + path 摘要 + status), 新条目从顶部进入
3. WHEN 用户点击悬浮窗任意行, the `CaptureFloatingService` SHALL 跳转到 `MainActivity` 并定位到抓包 Tab 选中对应条目
4. WHEN 用户长按悬浮窗, the `CaptureFloatingService` SHALL 弹出关闭按钮以停止抓包并移除悬浮窗
5. IF 系统未授予悬浮窗权限, the `CaptureController` SHALL 跳过悬浮窗渲染, 仅在 App 内抓包 Tab 提供实时列表

### Requirement 6: 抓包列表与详情

**User Story:** AS 调试者, 我想在抓包 Tab 查看完整列表, 并能进入单条详情查看 headers/body, 以便排查接口问题

#### Acceptance Criteria

1. WHILE 抓包启用, the `CaptureFragment` SHALL 以 RecyclerView 列表展示环形缓冲内全部条目, 每行: method 图标色 + host + path + status + timingMs + appName
2. WHEN 用户点击列表某行, the `CaptureFragment` SHALL 打开 `CaptureDetailActivity` 展示完整请求/响应 headers 与 body(bytes 形式 + 文本预览/二进制 hex 视图自动切换)
3. WHILE 详情页展示 body, the `CaptureDetailActivity` SHALL 对文本类 body 提供美化的 JSON/HTML/XML 格式化选项, 对二进制提供 hex dump
4. WHEN 抓包被停用, the `CaptureFragment` SHALL 保留当前列表直到用户离开 Tab, 不主动清屏

### Requirement 7: HAR 与多语言命令导出

**User Story:** AS 调试者, 我想把抓包结果导出为 HAR 或单条 curl/Postman/Java/Python, 以便在外部工具复现或分享

#### Acceptance Criteria

1. WHEN 用户在抓包列表顶部点"导出 HAR", the `CaptureController` SHALL 将环形缓冲内全部条目序列化为 HAR 1.2 兼容 JSON, 经由 `Intent.ACTION_CREATE_DOCUMENT` 让用户选择保存位置
2. WHEN 用户在单条详情点"导出", the `CaptureDetailActivity` SHALL 提供 cURL(bash)/Postman Collection JSON/Python(requests)/Java(OkHttp)/JavaScript(fetch) 五种格式选项, 写入剪贴板或文件
3. IF 某条 entry 缺少响应字段, the `CaptureController` SHALL 在 HAR 中标记 `response.status=0` 与 `response.headers={}`, 不导出 body
4. WHEN HAR 或命令导出包含敏感头部(Authorization/Cookie/Set-Cookie/X-Token 等), the `CaptureController` SHALL 默认以 `***` 脱敏, 并在文件头注释中说明, 同时在设置中提供"原样导出"开关
5. WHEN 用户点"导出 Postman", the `CaptureDetailActivity` SHALL 生成可直接 Import 到 Postman 的 Collection v2.1 schema 单条目 JSON

### Requirement 8: 修改请求/响应

**User Story:** AS 调试者, 我想能在抓包流转过程中修改请求(发往服务器前)和响应(发回 App 前), 以便测试接口容错或修正接口返回

#### Acceptance Criteria

1. WHEN 用户在抓包列表点某行进入详情, the `CaptureDetailActivity` SHALL 提供独立"改请求"与"改响应"两个按钮, 进入编辑模式
2. WHILE 处于改请求编辑模式, the `CaptureDetailActivity` SHALL 让用户修改 method/path/host/请求 headers/请求 body, 退出编辑时该变更仅作为本地草稿, 不立即生效
3. WHEN 用户在改请求草稿基础上点"开启断点", the `CaptureController` SHALL 让该 host+method 的后续请求在 `HttpMitmFilter` 解码后**暂停转发**, 等待用户在抓包 Tab 手动确认"放行/修改后再放行/直接丢弃"三种动作
4. WHILE 处于改响应编辑模式, the `CaptureDetailActivity` SHALL 让用户修改 statusLine/响应 headers/响应 body, 草稿可"本地预览"或"应用到下一条匹配响应"
5. WHEN 用户在改响应草稿基础上点"应用到下一条", the `CaptureController` SHALL 对下一条同 host+method+path 匹配的响应, 用草稿内容替换原响应返回给 App, 替换仅一次性生效, 不留存
6. WHEN 用户关闭抓包 Tab 或停用抓包, the `CaptureController` SHALL 清空所有改请求/改响应断点与草稿, 不持久化
7. IF 改响应后新 body 与原 Content-Length 不一致, the `CaptureController` SHALL 自动重新计算 Content-Length, 同时移除原 Transfer-Encoding: chunked 以避免客户端误解析

### Requirement 9: 请求重放

**User Story:** AS 调试者, 我想在不依赖 App 触发的场景下重发已抓到的请求, 以便多次复现同一调用

#### Acceptance Criteria

1. WHEN 用户在抓包详情页点"重放", the `CaptureDetailActivity` SHALL 进入"重放编辑"模式, 预填原请求, 用户可改 method/path/headers/body 后点"发送"
2. WHEN 用户点"发送"触发重放, the `CaptureController` SHALL 在 VPN 内部以**独立的客户端 socket**直接发往目标 host(不绕回设备网络栈), 将响应作为一条新 `CaptureEntry` 记入缓冲, 标记 `replayed=true`
3. WHILE 重放进行中, the `CaptureDetailActivity` SHALL 显示 spinner, 失败时显示具体异常类与消息, 允许用户修改后重试
4. WHEN 用户长按列表中的某条 entry, the `CaptureFragment` SHALL 弹出菜单"加入模板", 模板存入 `CaptureTemplateRepository`(持久化), 在抓包 Tab 顶有"从模板重放"入口

### Requirement 10: 抓包筛选与搜索

**User Story:** AS 调试者, 我想在大量条目中按 host/method/status/关键字快速筛选与搜索, 以便定位关心的接口

#### Acceptance Criteria

1. WHILE 抓包启用并显示列表, the `CaptureFragment` SHALL 顶部提供筛选条: host 输入 + method 下拉(GET/POST/PUT/DELETE/ALL)+ status 范围(2xx/3xx/4xx/5xx/ALL)+ 关键字输入(对 path+headers+body 预览做 contains)
2. WHILE 筛选条件变化, the `CaptureFragment` SHALL 即时对环缓冲内全部条目过滤后展示, 不改变原缓冲顺序
3. WHEN 用户使用顶部搜索框输入关键字, the `CaptureFragment` SHALL 在筛选结果中进一步按关键字高亮匹配位置, 不区分大小写
4. WHEN 用户点筛选条上"仅看被拦截"开关, the `CaptureFragment` SHALL 仅显示 `intercepted=true` 的条目, 以便对比广告拦截与抓包观察结果

### Requirement 11: TLS / 连接信息查看

**User Story:** AS 调试者, 我想查看每条请求的 TLS 信息(SNI/密码套件/证书链/ALPN), 以便排查证书相关问题

#### Acceptance Criteria

1. WHEN 用户在抓包详情页切到"TLS"Tab, the `CaptureDetailActivity` SHALL 展示 SNI、协议版本(如 TLSv1.3)、选中密码套件、ALPN 协议(http/1.1 或 h2)、对方证书链(subject/issuer/notBefore/notAfter)
2. WHILE 抓包启用, the `CaptureController` SHALL 在 TLS 握手完成时把 `TlsMitmSessionManager` 现有 session 上的 TLS 元数据存到 `CaptureEntry.tlsMeta`, 不另起 grabber
3. IF 某条流量上游 TLS 握手失败, the `CaptureDetailActivity` SHALL 在 TLS Tab 中显示失败原因, 而非隐藏该 Tab

### Requirement 12: 性能与稳定性

**User Story:** AS 用户, 我希望开启抓包不会让手机卡顿或 VPN 中断, 以便长时间调试

#### Acceptance Criteria

1. WHILE 抓包启用, the `CaptureController` SHALL 在 `HttpMitmFilter` 的主线程回调中以非阻塞方式采样, body 复制与序列化放 IO 线程
2. WHILE 环形缓冲被读写, the `CaptureController` SHALL 使用无锁环形队列或 `Channel` 实现, 避免与 VPN 数据面竞争锁
3. IF 抓包启用导致内存峰值超过 80MB, the `CaptureController` SHALL 主动将环形缓冲容量减半, 并在抓包 Tab 顶部提示"已自动降配"
4. WHEN 抓包停用或 App 退出, the `CaptureController` SHALL 释放所有 body 字节数组, 不持有任何引用
5. WHILE 改请求/响应断点让 `HttpMitmFilter` 暂停转发, the `CaptureController` SHALL 设置硬超时 30 秒, 用户未操作则自动放行原请求, 避免 App 看似卡死
