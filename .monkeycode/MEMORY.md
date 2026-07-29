# 用户指令记忆

本文件记录了用户的指令、偏好和教导，用于在未来的交互中提供参考。

## 格式

### 用户指令条目
用户指令条目应遵循以下格式：

[用户指令摘要]
- Date: [YYYY-MM-DD]
- Context: [提及的场景或时间]
- Instructions:
  - [用户教导或指示的内容，逐行描述]

### 项目知识条目
Agent 在任务执行过程中发现的条目应遵循以下格式：

[项目知识摘要]
- Date: [YYYY-MM-DD]
- Context: Agent 在执行 [具体任务描述] 时发现
- Category: [代码结构|代码模式|代码生成|构建方法|测试方法|依赖关系|环境配置]
- Instructions:
  - [具体的知识点，逐行描述]

## 去重策略
- 添加新条目前，检查是否存在相似或相同的指令
- 若发现重复，跳过新条目或与已有条目合并
- 合并时，更新上下文或日期信息
- 这有助于避免冗余条目，保持记忆文件整洁

## 条目

[规则导入稳定性优先]
- Date: 2026-06-14
- Context: 用户明确规则文件和规则源导入的唯一优先级
- Instructions:
  - 无论规则文件和规则源多大，导入流程都优先保证快速、不卡死、不崩溃、不闪退。
  - 大规则导入应先把可直接拦截的域名规则稳定入库，再在后台继续分批分析复杂语义；任何一批失败都应保留已经完成的导入结果。
  - 导入实现应优先采用流式读取和流式写入；导入阶段不做全量去重，重复规则清理由现有清理功能或后台低优先级任务处理。

[当前阶段不做无障碍跳过]
- Date: 2026-04-24
- Context: 用户要求先把广告通过现有拦截能力尽量拦下来，暂不增加类似李跳跳的功能
- Instructions:
  - 当前阶段不新增类似李跳跳的无障碍自动跳过功能。
  - 优先继续增强现有 DNS/VPN 域名拦截能力，把小说类 App 广告先尽量拦截下来。

[优先兼容手机稳定性]
- Date: 2026-04-24
- Context: 用户要求继续增强广告拦截，同时尽量兼容手机、少出 bug
- Instructions:
  - 继续增强广告拦截时优先选择保守、安全、兼容性更高的实现。
  - 避免为了提高拦截强度引入容易导致手机兼容性问题或误杀主业务流量的改动。

[首次权限申请边界]
- Date: 2026-04-24
- Context: 用户要求首次启动主动申请必要权限，并在失败时提示手动允许
- Instructions:
  - 首次启动时只主动申请应用真正需要的标准运行时权限，不要弹出无关权限请求。
  - `POST_NOTIFICATIONS` 可以主动申请；`VPN` 权限在用户开启拦截时走系统授权链路；应用列表读取若被手机系统额外限制，只能弹窗引导用户去系统设置手动允许。

[小说 App 强拦方向]
- Date: 2026-04-24
- Context: 用户要求按建议继续增强拦截能力
- Instructions:
  - 在当前 DNS/VPN 架构内优先增强“小说 App 强拦模式”，但只对已识别小说应用生效。
  - 强拦策略应只针对明确广告厂商和明显广告特征域名，并排除小说主业务域，避免误伤正常阅读和接口请求。

[增强目标与规则页易用性]
- Date: 2026-04-24
- Context: 用户要求继续做 DoT/DoH、CNAME、IPv4/IPv6、IP 过滤等增强，同时保证网络稳定、性能流畅，并让规则页更人性化
- Instructions:
  - 拦截增强优先选择不会明显影响网络与性能的实现，例如负缓存、CNAME 溯源命中、应用分级和保守 IP 过滤。
  - 规则页面的控件优先优化高频操作体验，如粘贴添加、清空输入、明确输入提示，避免大改带来新 bug。

[版本与兼容性要求]
- Date: 2026-06-13
- Context: 用户要求根据修改次数更新版本号，并检查兼容性与流畅度
- Instructions:
  - 版本号按修改次数递进，例如 `1.6.1` 到 `1.6.2`；补丁位每十次进阶，逢10进1：`2.6.9` → `2.6.10` → `2.7.0`。
  - 更新版本号时同步提升 `versionCode`，便于后续安装覆盖。
  - 兼容性增强以静态保守实现为主，避免直接改成全流量代理或引入会影响不同机型联网稳定性的高风险策略。

[当前版本展示要求]
- Date: 2026-05-22
- Context: 用户要求继续完善 HTTP/3 并把当前版本更新为 3.0-beta
- Instructions:
  - 当前版本号使用 `3.0-beta`，同时同步提升 `versionCode`，保证安装覆盖和界面版本展示一致。

[当前版本展示要求 5.5.6-beta]
- Date: 2026-05-31
- Context: 用户要求把当前版本改成 5.5.6-beta
- Instructions:
  - 当前版本号使用 `5.5.6-beta`，同时同步提升 `versionCode`，保证安装覆盖和界面版本展示一致。
  - 涉及版本展示或对外标识的位置，如 `build.gradle.kts`、首页版本文案、规则源请求 `User-Agent`，需要保持一致。

[当前版本展示要求 5.8.4-beta]
- Date: 2026-06-01
- Context: 用户要求把当前版本改成 5.8.4-beta
- Instructions:
  - 当前版本号使用 `5.8.4-beta`，同时同步提升 `versionCode`，保证安装覆盖和界面版本展示一致。
  - 涉及版本展示或对外标识的位置，如 `build.gradle.kts`、首页版本文案、规则源请求 `User-Agent`，需要保持一致。

[当前版本展示要求 5.9.7]
- Date: 2026-06-01
- Context: 用户要求把当前版本改成 5.9.7
- Instructions:
  - 当前版本号使用 `5.9.7`，同时同步提升 `versionCode`，保证安装覆盖和界面版本展示一致。
  - 涉及版本展示或对外标识的位置，如 `build.gradle.kts`、首页版本文案、规则源请求 `User-Agent`，需要保持一致。

[当前版本展示要求 5.9.7-beta]
- Date: 2026-06-01
- Context: 用户要求把当前版本改成 5.9.7-beta，并同步当前代码到仓库
- Instructions:
  - 当前版本号使用 `5.9.7-beta`，同时同步提升 `versionCode`，保证安装覆盖和界面版本展示一致。
  - 涉及版本展示或对外标识的位置，如 `build.gradle.kts`、首页版本文案、规则源请求 `User-Agent`，需要保持一致。

[当前版本展示要求 1.0]
- Date: 2026-06-06
- Context: 用户要求把当前版本改成 1.0
- Instructions:
  - 当前版本号使用 `1.0`，同时同步提升 `versionCode`，保证安装覆盖和界面版本展示一致。
  - 涉及版本展示或对外标识的位置，如 `build.gradle.kts`、首页版本文案、规则源请求 `User-Agent`，需要保持一致。

[本地构建依赖 Android SDK]
- Date: 2026-06-01
- Context: Agent 在执行“检查并修复优化 app 里的所有功能”时发现
- Category: 环境配置
- Instructions:
  - 本项目执行 `./gradlew :app:assembleDebug` 依赖本机 Android SDK，可通过 `ANDROID_HOME` 或项目根目录 `local.properties` 中的 `sdk.dir` 指定。
  - 当前工作区若缺少 Android SDK 路径，Gradle 会直接在依赖解析前失败，后续代码修复需要先补齐 SDK 环境再做完整编译验证。

[小说专项观测链路]
- Date: 2026-04-24
- Context: Agent 在执行“继续补小说 App 域名库并做专项观测”时发现
- Category: 代码模式
- Instructions:
  - 可疑域名样本除了域名和次数，还应记录最近命中的应用名、最近分类厂商以及小说 App 命中次数，优先把小说类样本排在前面。
  - `SuspiciousDomainsActivity` 适合提供“只看小说 App 专项样本”筛选，便于根据番茄、七猫、起点、书旗、掌阅等真实日志继续补规则。
  - 可疑样本写入需要做短时间节流，避免同一域名在同一应用里高频命中时持续刷写本地存储，影响手机兼容性和流畅度。

[公开广告规则源接入边界]
- Date: 2026-04-24
- Context: Agent 在继续把公开开源广告规则源合并进默认规则时发现
- Category: 代码模式
- Instructions:
  - 公开规则源优先参考 `AdGuardSDNSFilter`、`EasyList China`、`StevenBlack/hosts`，但只保守提取 DNS 级可直接落地的明确域名。
  - 对 `EasyList/EasyPrivacy` 一类包含 URL、path、cosmetic、regex 的规则源，不能把复杂规则误降级成整域名拦截。

[AdGuard DNS 规则导入兼容补充]
- Date: 2026-04-25
- Context: Agent 在执行“继续优化广告拦截并兼容 AdGuard 广告 SDK 域名规则”时发现
- Category: 代码模式
- Instructions:
  - `RuleRepository` 现在适合继续兼容 AdGuard DNS 域名规则中的 `dnstype=`、`important`、`match-case`、`badfilter` 等可安全落地的修饰符。
  - 对 `domain`、`app`、`denyallow`、`third-party`、`redirect` 等依赖请求上下文或浏览器语义的修饰符，仍应保持不支持，避免误降级成整域名拦截。
  - VPN 查询命中规则时应优先按真实 DNS `qType` 匹配，避免把 `AAAA` 或 `HTTPS` 等受限规则误扩展成所有记录类型都拦截。

[界面功能保持原样]
- Date: 2026-04-25
- Context: 用户要求继续增强广告拦截效果，但确认界面和功能与原来保持一致
- Instructions:
  - 后续增强优先放在拦截逻辑、规则库和 DNS 处理链路上，不主动改动现有界面布局和交互。
  - 若无明确要求，不新增或调整页面功能入口，保持当前界面和功能表现与原来一致。

[HTTP 解密性能优先]
- Date: 2026-05-21
- Context: 用户要求继续补强拦截能力，同时明确要求开启 HTTP 解密模式时尽量不要影响上网速度
- Instructions:
  - 开启 HTTP 解密后，优先只对命中动态解密目标、已知广告厂商或明显广告特征路径的流量做深度处理。
  - 普通请求优先直通，避免对大部分正常流量做不必要的请求头改写、解码和重写，降低对上网速度的影响。

[MITM 模式积极增强优先]
- Date: 2026-06-13
- Context: 用户明确要求开启 MITM 模式后，以增强拦截效果为首要目标，只要性能占用和网速仍可接受，就不需要保守；后续又要求全流量全路由接管
- Instructions:
  - 开启 MITM 模式后，优先提高广告请求、广告响应、奖励解锁、信息流、启动页和播放器广告的深度检查与阻断强度。
  - 在手机性能占用和网速仍可接受的范围内，MITM 模式下可以采用比普通模式更积极的拦截阈值和特征判定。
  - MITM 增强可以采用全流量全路由接管，但必须保留证书门槛、普通联网 passthrough、熔断回退和构建测试验证，目标是提升广告覆盖同时保持不断网。

[广告拦截优先且保持联网速度]
- Date: 2026-05-29
- Context: 用户要求继续增强广告拦截，尽量把广告都拦下来，同时保持 App 正常上网功能和网速
- Instructions:
  - 广告拦截增强可以继续采用更积极的命中策略，优先覆盖明确广告物料、广告厂商、小说广告、评论流广告、推荐流广告和消息中心广告。
  - 强拦逻辑优先建立在明确广告字段、广告路径、广告厂商和广告物料链接上，保持普通业务流量和网速稳定。

[对齐并超越 AdGuard 拦截能力]
- Date: 2026-06-13
- Context: 用户要求只要 AdGuard 能拦截的广告，寒枫也要尽量能拦截，并在五项能力上持续做到更强
- Instructions:
  - 后续增强优先对齐并持续强化 AdGuard 有效的能力层，包括规则语义兼容、协议覆盖、HTTPS MITM 成熟度、App 级广告识别、稳定性和误伤控制。
  - 每轮增强都应尽量落到可测试的小步改动，覆盖 MITM 响应体识别、HTML cosmetic 清洗、脚本注入、协议降级和真机日志样本补规则。
  - Shizuku 权限优先用于提升连接归属识别、应用级规则命中和流量定位准确率，再把这些识别结果用于增强拦截决策。

[充分利用现有权限提高拦截能力]
- Date: 2026-05-29
- Context: 用户要求继续执行当前增强任务，并充分利用 App 已具备的所有功能权限来提高拦截能力
- Instructions:
  - 后续增强优先把现有已接入的 `VPN`、`HTTP decrypt`、`QUERY_ALL_PACKAGES`、`Shizuku` 等能力真正用满，用于提升连接归属识别、应用级规则命中、小说强拦判断和解密路由准确率。
  - 权限利用优先聚焦在提升拦截准确率和覆盖率，保持现有界面与授权边界不变。

[Shizuku 应用级强拦联动]
- Date: 2026-06-01
- Context: Agent 在执行“把 Shizuku 的权限用满并对全流量更精准拦广告”时发现
- Category: 排错调试
- Instructions:
  - `AdBlockVpnService` 适合把 `Shizuku` 连接归属结果直接用于热路径决策，只对已识别高风险应用启用更积极的广告基础设施域名拦截。
  - 应用级强拦需要继续保留白名单、鉴权域名、媒体主业务和业务核心域保护，避免把更强的归属识别扩展成误杀正常流量。

[Shizuku 就绪检查统一流程]
- Date: 2026-06-06
- Context: Agent 在执行“优化 Shizuku 功能”时发现
- Category: 工作流协作
- Instructions:
  - Shizuku 的页面入口适合统一走共享的“预热 user service -> 检查 connection owner / ad control 存活 -> 再给出不可用提示”的流程，避免设置页、治理页、首页各自维护一套判断逻辑。
  - 当 Binder 可达但权限状态异常时，应同时参考增强服务是否已存活，再决定提示“兼容模式可用”还是“增强服务尚未就绪”。

[AdGuard 请求改写保守落地]
- Date: 2026-06-06
- Context: Agent 在执行“优化 Shizuku 功能并补 AdGuard header 支持”时发现
- Category: 排错调试
- Instructions:
  - `header=` 适合先只支持请求头覆盖子集，采用 `Header-Name: value` 编码后透传到 MITM 请求改写链，优先覆盖已有头，其次补充缺失头。
  - 涉及响应头改写、复杂脚本注入和高副作用 header 语义时，优先继续保守处理，先保证导入兼容和请求改写稳定性。

[默认规则源与原因日志链路]
- Date: 2026-06-01
- Context: Agent 在执行“继续补组件级治理、默认规则源和命中原因展示”时发现
- Category: 工作流协作
- Instructions:
  - 远程规则源默认集合适合内置 `寒枫规则`、`EasyList`、`EasyPrivacy`、`oisd big`、`EasyList China`，其中体量更大的补充源可默认关闭，由用户按需开启。
  - VPN 热路径里的 DNS、HTTP、HTTPS 决策适合统一生成 `reason` 字段，再由日志面板和后续界面直接复用，避免各处各写一套原因文案。

[组件治理候选发现方式]
- Date: 2026-06-01
- Context: Agent 在执行“继续补组件级治理体验”时发现
- Category: 排错调试
- Instructions:
  - `SettingsActivity` 适合先读取目标包的 `Activity`、`Receiver`、`Service` 清单，再按 `splash`、`push`、`recommend`、`ad` 等关键词给组件打分，优先展示高相关候选组件。
  - 组件治理对外操作格式适合统一使用 `package/class` 形式，方便直接复用 `pm disable-user` 和 `pm enable` 链路。

[规则源疑似正常规则需先展示再删除]
- Date: 2026-05-29
- Context: 用户要求规则源导入后，对疑似正常规则先展示给用户看，并给出每条理由，再由用户决定删不删
- Instructions:
  - 规则源同步或导入后，疑似正常规则不能直接自动删除，应先向用户展示候选规则列表。
  - 每条候选规则都要给出具体理由，说明为什么判断为疑似正常规则。
  - 删除动作应由用户确认后执行，保留明确的“删除”与“保留”选择。

[规则源不得默认内置]
- Date: 2026-06-01
- Context: 用户明确要求不要内置规则源，并指出内置规则源删不掉且删除会闪退
- Instructions:
  - 规则源列表默认保持为空，只有用户手动添加后才显示规则源。
  - 不要在读取规则源配置时自动注入任何内置默认规则源。
  - 规则源项应允许用户删除，不能因为“内置”身份拦截删除操作。

[仅拦广告并保持 App 正常功能]
- Date: 2026-05-30
- Context: 用户要求继续补强拦截，同时开启拦截后不要影响 App 的正常功能，仅拦截广告
- Instructions:
  - 后续拦截增强优先收紧到明确广告流量，只对明确广告域名、广告路径、广告物料、广告 SDK 和广告跳转信号生效。
  - 普通业务接口、正文接口、登录鉴权、支付、媒体内容和其他主业务流量优先直通，保持 App 正常功能和网速稳定。
  - 复杂规则可以继续解析适配，但只有在规则能稳定收敛到明确广告目标时才参与拦截，避免把复杂组合规则宽降级成正常业务拦截。

[低耗电与低后台占用优先]
- Date: 2026-05-22
- Context: 用户要求在继续增强广告拦截的同时，进一步降低耗电量和后台占用
- Instructions:
  - 高频热路径优先使用内存缓存，减少 `SharedPreferences`、系统证书状态和其他持久化状态的重复读取。
  - 后台周期性工作与延迟任务应尽量收敛，避免重复 `postDelayed`、高频轮询和对普通流量的深度检查。
  - HTTPS/HTTP 深度处理只对明确命中规则、明确广告厂商或明显广告特征流量触发，普通流量优先直通。

[HTTP3 QUIC 精细处理优先]
- Date: 2026-05-22
- Context: 用户要求完善 HTTP/3 支持，同时保持联网稳定和低误杀
- Instructions:
  - `HTTP/3 / QUIC` 继续采用保守的精细判断策略，优先结合 DNS 路由缓存、白名单、规则命中和小说强拦信号决定是否阻断。
  - 对正常业务域名的 `UDP/443` 流量优先放行；对明确广告目标或需要回退到 TCP 做 MITM 的目标再阻断并推动回退。

[加密 DNS 反绕过边界]
- Date: 2026-04-24
- Context: Agent 在处理用户提出的 DoH/DoQ/DoT 与 HTTPS 解密拦截需求时发现
- Category: 依赖关系
- Instructions:
  - 当前项目可以继续增强 `DoH/DoQ/DoT/HTTPDNS` 反绕过域名与公共 DNS IP 黑名单。
  - 当前项目不做 `HTTPS MITM` 解密拦截，仍保持 DNS/IP 级最小接管 VPN 架构，优先保证稳定性与正常联网体验。

[规则解析兼容边界补充]
- Date: 2026-04-24
- Context: Agent 在继续扩充小说广告域名与规则导入兼容时发现
- Category: 代码模式
- Instructions:
  - `RuleRepository` 的导入解析现在额外兼容行尾注释、`ipset=/.../`、`nftset=/.../` 以及更多 `hostname/domain` 精确域名别名变体。
  - 兼容扩展仍只提取“明确域名型”规则，不能把 `keyword`、`regex`、`path`、`ip-cidr`、逻辑组合规则降级成整域名拦截。

[直接提供代码与放置路径]
- Date: 2026-04-22
- Context: 用户要求生成可直接导入 Android Studio 打包的项目文件
- Instructions:
  - 回复以简体中文输出。
  - 优先直接提供代码和文件放置路径，便于用户在本地建立工程文件。

[Android 广告拦截工程骨架]
- Date: 2026-04-22
- Context: Agent 在执行 Android 广告拦截 App 初始化开发时发现
- Category: 代码结构
- Instructions:
  - 工程采用单模块 `app/` 结构，使用 Kotlin + XML + ViewPager2 三屏滑动布局。
  - VPN 实现采用 `VpnService` 仅接管本地 DNS 地址和常见 DoT 目标地址的路由，避免全流量代理导致断网。
  - 规则、统计、白名单、日志分别放在 `data/`，DNS 报文和包解析分别放在 `dns/` 与 `service/`。

[正式外链与规则页视觉要求]
- Date: 2026-04-22
- Context: 用户补充正式下载链接、办卡链接、QQ群号与左右两侧页面详细布局规格
- Instructions:
  - 规则下载按钮点击后先复制密码 `aehi`，再打开 `https://hanfengnb.lanzoul.com/b0j1elsrg`。
  - 办卡按钮点击后打开 `https://h5.lot-ml.com/ProductEn/Index/120d6424545c4be5`。
  - QQ 群按钮点击后按群号 `573309536` 唤起 QQ 加群。
  - 左侧规则页需要增加标题、规则统计标签、规则列表标题和带计数的多选工具栏。
  - 右侧统计页需要增加“拦截详细”和“拦截排行榜”标题，并优化统计卡片与排行榜布局。

[品牌标识与资源占位要求]
- Date: 2026-04-22
- Context: 用户指定正式包名、应用名，并要求预留自定义图标、背景图、排行榜奖牌图标接口
- Instructions:
  - Android 包名使用 `com.HanFeng`。
  - 应用名使用 `寒枫`，中间主界面标题使用 `寒枫 · 广告拦截`。
  - 需要预留自定义应用图标资源接口，方便后续替换。
  - 需要预留自定义背景图资源接口，左右中三个界面共用。
  - 排行榜前三名需要使用金银铜奖牌图标，并预留可直接替换的资源文件名。

[拦截准确性优先]
- Date: 2026-04-22
- Context: 用户强调广告拦截必须尽量精准，且不能影响手机正常上网
- Instructions:
  - 所有对外说明文案应优先强调“仅拦截命中广告规则的域名，其他流量完全透传”。
  - 避免在文案中承诺当前实现未完整支持的规则能力，优先保证网络可用性与真实描述一致。

[沉浸式界面与白名单要求]
- Date: 2026-04-22
- Context: 用户要求修复状态栏遮挡、背景图拉伸、白名单逻辑与闪退问题，并输出完整目录说明
- Instructions:
  - 三个界面的文字和控件都必须位于状态栏下方，不能被状态栏遮挡。
  - 背景图需要自适应显示，避免明显拉伸。
  - 所有控件卡片和弹窗使用白色半透明背景与黑色文字。
  - 白名单逻辑必须是默认全部应用受拦截，加入白名单的应用完全放行。
  - 优先修复影响使用的闪退问题，并在最终回复中列出全部目录、文件名和功能说明。

[Android 构建与 VPN 热重载约束]
- Date: 2026-04-22
- Context: Agent 在继续排查“闪退/不可用”问题时发现
- Category: 构建方法
- Instructions:
  - 项目 `app` 模块需要 `org.jetbrains.kotlinx:kotlinx-coroutines-android`，因为 `AdBlockVpnService` 使用了 `CoroutineScope`、`Dispatchers` 和 `launch`。
  - 本地构建需要可用的 JDK 17 环境；当前工作区若未设置 `JAVA_HOME`，`./gradlew assembleDebug` 会在进入编译前失败。
  - 白名单变更后需要重载 `AdBlockVpnService` 才能立即更新 `addDisallowedApplication` 生效范围。

[签名与系统版本兼容要求]
- Date: 2026-04-22
- Context: 用户补充 Android 发布签名与适配范围要求
- Instructions:
  - 发布包需要支持 V1、V2、V3 三种 APK 签名方案。
  - 应用需要兼容 Android 7 到 Android 16。

[权限与低版本兼容优先]
- Date: 2026-04-22
- Context: 用户要求优先修复闪退、兼容性和权限问题
- Instructions:
  - 在继续功能开发前，优先修复 app 闪退、权限申请和 Android 7+ 兼容问题。

[Android 7 兼容实现要点]
- Date: 2026-04-22
- Context: Agent 在为 Android 7+ 兼容做静态修复时发现
- Category: 环境配置
- Instructions:
  - `minSdk` 调整到 24 后，应避免直接依赖 `java.time.LocalDate` 这类在旧版本上需要额外 desugaring 的 API。
  - Android 13+ 需要在运行时申请 `POST_NOTIFICATIONS`，适合在用户开启 VPN 前请求，以减少前台服务通知异常。

[稳定性与性能优化优先级]
- Date: 2026-04-22
- Context: 用户进一步明确闪退、卡顿、规则管理和 VPN 行为的优化方向
- Instructions:
  - 优先修复点击“使用说明”“规则”闪退以及无规则时断网问题。
  - 打开黑白名单等重页面时，应用列表加载必须放到后台线程，并显示进度圈，完成后自动隐藏。
  - RecyclerView 页面避免耗时渲染，应用图标应异步加载，列表布局尽量精简。
  - VPN 线程应降低优先级，并尽量使用阻塞模式避免空转耗 CPU。
  - 规则厂商映射应扩充为“中文名 (英文名)”格式，规则分组默认折叠。
  - 非广告规则筛选需要展示明确结果列表，并让用户选择“保留所选”或“删除其余”。
  - 统计数据需要在拦截事件发生后及时刷新到 UI。

[规则导入分析与规则缓存]
- Date: 2026-04-23
- Context: Agent 在执行“高级规则分析功能”和规则页修复时发现
- Category: 代码模式
- Instructions:
  - 规则页保留“直接导入”和“高级规则分析”两个入口；高级分析需在导入前展示安全规则、例外规则、重复项、被跳过的高级修饰符和厂商分布。
  - `RuleRepository` 需要缓存规则列表、域名集合和自定义厂商映射，避免 VPN 每次 DNS 判断都重复读取 `SharedPreferences`。
  - DNS 命中判断应基于域名后缀候选集合和缓存域名集合做快速匹配，减少 VPN 路径上的主线程外开销。

[厂商归类与 Gradle 镜像要求]
- Date: 2026-04-23
- Context: 用户要求继续扩充厂商识别并修改 Wrapper 下载源
- Instructions:
  - 厂商识别要覆盖国内外主流广告与平台厂商，并尽量把同集团、同母公司的子品牌归并到同一厂商分组。
  - Gradle Wrapper 默认使用腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.8-bin.zip`。
  - `gradle-wrapper.properties` 中保留阿里云镜像 `https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.8-bin.zip` 作为注释备用地址。

[规则导入与主要按钮样式调整]
- Date: 2026-04-23
- Context: 用户要求继续调整规则页交互、日志入口位置、主按钮视觉和排行榜紧凑度
- Instructions:
  - 高级规则解析要与导入规则文件合并，选择文件后自动导入，再在后台完成分析并向用户展示分析结果。
  - 原“高级规则分析”位置改为“导出日志”功能入口。
  - 所有主要操作按钮使用白色半透明背景，约 50% 不透明度，保留黑色细边框、圆角和深灰文字。
  - 需要继续修复点击已导入规则、打开使用说明时的闪退现象。
  - 右侧排行榜条目和卡片都要更紧凑：条目上下间距缩小，卡片整体最小高度同步缩小。

[厂商与应用排行榜识别增强]
- Date: 2026-04-23
- Context: 用户继续要求减少“其它/未知应用”并按本地应用统计拦截来源
- Instructions:
  - 厂商识别优先使用命中的规则分组结果，其次再回退到域名关键字识别，以减少“其它 (Other)”。
  - 厂商库继续补充全球主流广告与程序化广告平台，并将历史别名归并到统一集团名称。
  - 应用排行榜优先显示本地应用名和包名；`未知应用` 与 `其它` 在排行榜排序中应尽量靠后显示。

[规则点击交互与排行榜视觉继续收紧]
- Date: 2026-04-23
- Context: 用户要求继续修复规则列表闪退、说明入口失败、右侧排行榜空隙和识别精度问题
- Instructions:
  - 已导入规则项改为单击无响应，避免点按闪退；长按进入删除操作。
  - “使用说明”点击后不能再弹失败提示，应改为更稳定的展示方式。
  - 右侧排行榜条目上下间隙继续缩小，排行榜容器高度也要进一步缩短。
  - 主要面板和控件视觉需要明显保持白色半透明，不能看起来像黑色半透明。
  - 厂商分类要继续细化，尽量覆盖国内常见大厂与主流广告平台。
  - 应用拦截统计应尽量直接识别真实本地应用，减少出现“未知应用”。

[厂商识别采用更激进关键词匹配]
- Date: 2026-04-23
- Context: 用户要求国内外厂商识别都增加关键词命中能力，例如小米可通过 `mi` 识别
- Instructions:
  - 厂商识别除现有域名模式外，还应增加更激进的关键词匹配能力。
  - 国内外主流厂商都需要补充常见品牌词、缩写词、SDK 词和域名片段。
  - 对高频厂商可使用短关键词辅助识别，但要尽量放在更长、更明确的词后面，降低误判。

[右侧页底部外链入口布局]
- Date: 2026-04-23
- Context: 用户要求把“免费领流量卡”和“加入群聊”从主界面移到右界面排行榜下方
- Instructions:
  - “免费领流量卡”和“加入群聊”不再放在主界面按钮区。
  - 两个按钮需要放到右界面排行榜最下面，横向并列显示。

[首页标题与主控件视觉调整]
- Date: 2026-04-23
- Context: 用户要求继续调整主界面标题文案、字体气质和三个主控件的位置
- Instructions:
  - 主界面标题文案从“寒枫广告拦截”改为“寒枫”。
  - 首页标题字体要更飘逸一些，可优先使用更具手写感或装饰感的系统字体方案。
  - 主界面剩余三个主要控件需要整体下移一点，避免视觉重心过高。

[细边框与排行榜完整展开要求]
- Date: 2026-04-23
- Context: 用户要求统一边框颜色、完整展开排行榜，并继续减少“未知应用”
- Instructions:
  - 所有按钮、卡片、弹窗的细边框统一使用浅黑色。
  - 右侧排行榜点击“查看更多”后，必须真正展开显示完整榜单，而不是只改文案。
  - 应用拦截排行中的“未知应用”需要继续通过缓存和回退识别尽量替换为真实应用名。

[首页控件位置与统一容器底色]
- Date: 2026-04-23
- Context: 用户要求首页控件再下移一点，并让所有控件和按钮与规则详细列表容器使用同样的颜色和透明度
- Instructions:
  - 首页三个主控件需要继续下移一点。
  - 所有按钮、控件、卡片、弹窗按钮的底色和透明度统一与规则详细列表容器一致。
  - 统一优先复用 `bg_panel` / `hf_surface` 这一套颜色源，避免按钮和容器颜色再出现偏差。

[完整榜单弹窗与翻页动画]
- Date: 2026-04-23
- Context: 用户要求排行榜完整榜单改为弹窗查看，并让滑动切页更像书翻页
- Instructions:
  - 点击“查看完整榜单”时，使用弹窗展示完整厂商或应用榜单。
  - 左右滑动切换三个页面时，需要增加更柔和的书页翻动动画，降低切换生硬感。
  - 应用识别继续增强，优先减少排行榜里的“未知应用”。

[三页独立背景的主容器约束]
- Date: 2026-04-23
- Context: Agent 在排查“颜色没变化、背景图不够独立”时发现
- Category: 代码结构
- Instructions:
  - `activity_main.xml` 不应再叠加总背景图或总遮罩层，否则会压住 `fragment_home.xml`、`fragment_rules.xml`、`fragment_stats.xml` 各自的背景图效果。
  - 三页独立背景生效时，主容器应尽量只保留 `ViewPager2`，让页面自己的背景和半透明控件直接呈现。

[VPN 上游 DNS 过滤约束]
- Date: 2026-04-23
- Context: Agent 在排查“广告未拦住、DNS 可能回环”时发现
- Category: 依赖关系
- Instructions:
  - `AdBlockVpnService.resolveDnsServers()` 需要过滤本地虚拟 DNS 地址（如 `10.99.0.2`、`fd66:66::2`），避免 VPN 启动后把查询再次发回自己导致无效回环。
  - 若系统动态 DNS 不可用，应回退到多个常见公共 DNS，优先保证未命中规则时仍能正常解析。

[日志导出单文件覆盖要求]
- Date: 2026-05-31
- Context: 用户要求下载目录中的日志文件只保留一个，每次生成时覆盖上一次
- Instructions:
  - 导出到下载目录的日志文件使用固定文件名，并复用同一个目标文件。
  - 每次重新生成日志文件时，覆盖上一次内容，并清理下载目录中同名旧副本。

[小说类广告拦截与交互优化要求]
- Date: 2026-04-23
- Context: 用户补充小说 App 常见广告类型、权限体验、排行榜展示和页面切换要求
- Instructions:
  - 要尽量拦截小说类 App 常见广告，包括开屏、Banner、章节插屏、原生广告、弹窗广告、任务中心广告、退出挽留页等，但不能影响正常网络连接。
  - 三个页面切换动画需要继续优化，降低当前过硬的切换感。
  - “查看完整榜单”点击后应像“使用说明”一样进入独立文本窗口展示完整榜单。
  - 应用需要的权限应尽量主动申请，减少用户手动去系统里开启的步骤。
  - 应用拦截排行榜只显示名次、应用名和数量，不显示包名。

[首页按钮状态与点击反馈要求]
- Date: 2026-04-23
- Context: 用户要求首页主控按钮状态实时变化、所有按钮增加点击效果，并继续强化兼容性与稳定性
- Instructions:
  - 点击“开启拦截”后，首页主按钮文案需要切换为“停止拦截”。
  - 所有按钮都需要有明显但稳定的点击反馈效果。
  - 左右界面切换动画继续朝“翻页效果”优化。
  - 主控大按钮保持白色半透明底、黑色细边框。
  - 兼容性优先，尽量减少因状态切换和动画带来的闪退问题。

[沉浸式状态栏与首页主控件位置要求]
- Date: 2026-04-23
- Context: 用户要求背景图延伸到状态栏，但控件本身保持避开状态栏，并且首页三个主控件尺寸不要改动
- Instructions:
  - 首页三个主控件的大小保持不变，不要继续改宽高。
  - 首页三个主控件整体再下移一些。
  - 背景图需要延伸到手机状态栏区域。
  - 页面控件本身不要延伸到状态栏内，保持沉浸式状态栏下的安全间距。

[移除翻页动画与按钮统一卡片样式]
- Date: 2026-04-23
- Context: 用户反馈所有界面重叠且划不动，要求删除翻页效果，并统一主要按钮样式到统计卡片样式
- Instructions:
  - 删除页面翻页动画，优先保证左右滑动稳定可用。
  - `免费领取流量卡`、`加入群聊`、`开启拦截`、`使用说明`、`黑白名单`、`添加`、`导入并分析`、`下载规则`、`导出日志`、`筛选非广告规则` 这些按钮统一使用“拦截详细”统计卡片同源的样式和颜色。

[按钮白色半透明与 VPN 启动修复]
- Date: 2026-04-23
- Context: 用户要求继续执行按钮样式和开启拦截故障修复
- Instructions:
  - 所有按钮颜色统一改成白色，并保留半透明效果。
  - 点击“开始拦截/开启拦截”时必须正确申请权限并拉起 VPN 授权链路。
  - 需要优先修复点击开启拦截时未打开 VPN 且闪退的问题。

[规则筛选闪退与按钮白色可见性]
- Date: 2026-04-23
- Context: 用户反馈点击“筛选非广告规则”闪退，且按钮仍显示为黑色半透明
- Instructions:
  - 优先修复“筛选非广告规则”点击后的闪退问题。
  - 所有按钮必须明确呈现为白色半透明，不能看起来像黑色半透明。

[加强规则分析与广告拦截覆盖]
- Date: 2026-04-23
- Context: 用户要求增强规则分析、加强大小广告厂商拦截，减少漏拦广告
- Instructions:
  - 规则导入分析要尽量兼容更多安全的域名规则格式。
  - 厂商识别要继续覆盖大小广告平台和聚合 SDK。
  - 内置广告规则需要继续补充常见广告 SDK、聚合平台和小说类广告相关域名。

[按钮默认强制白色半透明]
- Date: 2026-04-23
- Context: 用户要求所有控件按钮默认改成白色半透明，并强制保持白色半透明
- Instructions:
  - 按钮和主要控件的默认底色必须强制为白色半透明。
  - 主题层需要避免系统或 Material 默认样式把按钮覆盖成深色。

[筛选规则失败与拦截器实战增强]
- Date: 2026-04-23
- Context: 用户要求修复“筛选非广告规则”点击后失败，并继续把广告拦截器做得更能打
- Instructions:
  - “筛选非广告规则”入口必须可稳定使用，不能只提示失败。
  - 广告拦截增强应优先提升真实命中率，但仍保持 DNS 级、命中才拦、尽量不断网的原则。

[未知广告域名入口与指定厂商补拦]
- Date: 2026-04-23
- Context: 用户要求增加未知/可疑广告域名查看入口，按钮更白，并补拦指定厂商广告
- Instructions:
  - 需要提供未知或可疑广告域名的可见入口，方便继续补规则。
  - 控件按钮透明度要再增加一点，但仍保持白色半透明。
  - 需要补充 QXM、UBIX、VIVO、中关互动 的厂商识别和拦截覆盖。

[规则页交互修正]
- Date: 2026-04-23
- Context: 用户要求修复规则页两个入口的实际交互问题
- Instructions:
  - “筛选非广告规则”应先弹出窗口展示筛出的规则，再让用户选择删除哪些、保留哪些。
  - “可疑域名”入口不能只读展示，应支持继续添加拦截或其它后续操作。

[可疑域名页面增强]
- Date: 2026-04-23
- Context: 用户要求继续增强可疑域名交互
- Instructions:
  - 可疑域名需要支持搜索。
  - 需要支持批量添加拦截规则。

[可疑域名页筛选与规则页刷新]
- Date: 2026-04-23
- Context: 用户要求继续完善可疑域名页与规则页联动
- Instructions:
  - 可疑域名页面需要增加“只看未添加”的筛选开关。
  - 批量添加后需要自动返回并刷新规则页。
  - QXM、UBXI、 中关互动需要按公开广告请求域名、上报域名继续补充识别与拦截覆盖。

[可疑域名已添加筛选与 SDK 标识识别]
- Date: 2026-04-23
- Context: 用户要求继续增强可疑域名筛选，并把厂商 SDK 包名接入识别链路
- Instructions:
  - 可疑域名页面需要增加“只看已添加”筛选。
  - “只看未添加”和“只看已添加”应互斥，避免筛选状态冲突。
  - QXM、UBIX、中关互动的 SDK 标识和核心包名需要并入厂商识别词表，供日志识别和归类复用。

[厂商命名与可疑域名列表增强]
- Date: 2026-04-23
- Context: 用户要求继续增强广告厂商覆盖和可疑域名页展示与操作
- Instructions:
  - 导入规则分析里的厂商分类名称统一使用“中文名 (国际名)”格式。
  - 趣盟广告、美团等广告厂商需要继续补充更完整的识别与拦截覆盖。
  - 可疑域名列表项高度要更高，中间空白不要太大。
  - 可疑域名列表需要显示出现时间和出现次数。
  - 用户需要能对可疑域名做单选或多选后添加进拦截规则。

[厂商别名归一化与可疑域名交互]
- Date: 2026-04-23
- Context: Agent 在执行厂商命名统一和可疑域名页收尾时发现
- Category: 代码模式
- Instructions:
  - `RuleRepository.normalizeVendorName()` 需要支持链式别名归一化，否则 `Google (Google Ads)` 这类历史名称不会最终落到“中文名 (国际名)”格式。
  - 可疑域名页保留“单条添加”直达操作，同时应给列表项长按入口挂回“手动分类后添加/复制域名”等附加操作。

[排行榜预览与说明文案收紧]
- Date: 2026-04-23
- Context: 用户要求继续调整统计页排行榜卡片与使用说明页的紧凑度
- Instructions:
  - 拦截排行榜卡片内的名次上下间距继续缩小。
  - 每个排行榜卡片默认显示前五项，完整榜单仅在点击“查看完整榜单”后查看。
  - 使用说明中每条说明之间的间距需要缩小。
  - 使用说明末尾增加第 13 条：应用如有BUG或有更好的建议请进群反馈。

[专业化 DNS 拦截与统计稳定性增强]
- Date: 2026-04-23
- Context: Agent 在修复高风险问题并增强拦截稳定性时发现
- Category: 代码模式
- Instructions:
  - 上游 DNS 查询失败时不能直接吞包，应该回 `SERVFAIL`，避免用户体感断网。
  - 被拦截域名对非 A/AAAA 查询类型也要返回合法 DNS 响应，避免客户端查询超时。
  - 排行榜详情不要通过大字符串 `Intent` 传递完整榜单，应在详情页内按类型重新读取统计数据。
  - 统计排行榜持久化 map 需要裁剪上限，避免长期使用后数据无限膨胀。
  - Hosts 规则解析需要支持一行多个域名，提高规则导入覆盖率。

[联网稳定性优先于拦截架构]
- Date: 2026-04-23
- Context: 用户明确表示不强求本地 VPN + DNS 级拦截架构，只要求拦广告时不影响正常网络
- Instructions:
  - 优先保证用户正常联网，不要为了拦截硬拦会误伤网络的流量。
  - VPN 只应处理本应用明确接管的本地 DNS 请求，不要把常见公共 DNS 或 DoT 目标整段黑洞掉。

[DNS 稳定性增强策略]
- Date: 2026-04-23
- Context: Agent 在继续增强“不断网优先”的广告拦截能力时发现
- Category: 代码模式
- Instructions:
  - 上游 DNS 应优先尝试系统当前 DNS，再回退到内置公共 DNS，整体去重后依次尝试。
  - 单次 DNS 查询应允许对每个上游做有限重试，避免偶发抖动直接导致失败。
  - 可以使用短时 DNS 响应缓存，但缓存必须短 TTL、可按原请求事务 ID 重写，避免影响正常解析。

[DNS 健康退避与陈旧缓存容灾]
- Date: 2026-04-23
- Context: Agent 在继续提升联网稳定性时发现
- Category: 代码模式
- Instructions:
  - 上游 DNS 需要记录近期失败次数、冷却时间和最近成功时间，优先使用更健康的上游。
  - 过期不久的 DNS 缓存可以只在所有上游都失败时短暂兜底，避免网络瞬时抖动导致用户体感断网。
  - 陈旧缓存只能作为失败兜底，不能替代正常实时解析。

[规则引擎与强拦截需求]
- Date: 2026-04-23
- Context: 用户要求继续增强规则兼容与反绕过能力
- Instructions:
  - 修复“筛选非广告规则”打开失败问题。
  - 导入规则时尽量减少把国内大厂商广告规则误判为“无法识别”。
  - 规则引擎需要继续适配更高级但不会明显误伤网络的规则能力。
  - 需要增加主流公共加密 DNS 反绕过能力，并评估对阿里云 DoH、腾讯 DoH、百度 DoH、运营商 DNS 域名的拦截。
  - 需要增加广告 SDK 常用 IP/网段黑名单能力，但仍以不影响用户正常上网为前提。

[反绕过域名分类与复杂规则降级]
- Date: 2026-04-23
- Context: Agent 在继续修复规则筛选与导入兼容性时发现
- Category: 代码模式
- Instructions:
  - 加密 DNS 反绕过域名需要单独归类，不能继续落到 `其它 (Other)`，否则会被“筛选非广告规则”误删。
  - 对带 `://`、`*`、`^`、`|` 等特征但无法提取域名的规则，应优先记为复杂规则或不支持项，而不是直接算作 `invalidRules`。
  - 反绕过域名名单应尽量使用精确服务域名，避免把整站根域名都归入拦截保护分类而误伤正常流量。

[最小 IP 黑名单接管策略]
- Date: 2026-04-23
- Context: Agent 在继续实现“尽量不断网”的反绕过能力时发现
- Category: 代码模式
- Instructions:
  - IP 黑名单应优先采用“只给黑名单 CIDR 加 VPN route，命中后直接丢弃”的最小接管方案，避免把全量流量纳入 VPN。
  - 默认内置名单先覆盖公共 DNS / 反绕过 IP，广告 SDK 的更大 IP 段后续再按证据逐步补充。
  - 命中黑名单 IP 时应静默丢弃，避免高频日志写入影响性能。

[拦截效果、流畅度与兼容性优先]
- Date: 2026-04-23
- Context: 用户要求继续强化拦截效果，同时提升运行流畅度和不同安卓版本机型兼容性
- Instructions:
  - 在增强拦截时，优先补充保守且高命中的公共 DNS / 广告目标，不要用激进规则换误伤。
  - 性能优化优先落在 VPN 热路径、DNS 上游选择和缓存路径，减少频繁系统查询和对象分配。
  - 兼容性优化优先采用 best-effort 方式处理机型差异，避免单个能力失败导致 VPN 整体建立失败。

[规则来源统计与暂不支持规则清理]
- Date: 2026-04-23
- Context: 用户要求继续增强规则管理体验与内置规则覆盖
- Instructions:
  - 规则页摘要需要显示内置规则数和用户导入规则数，必要时可附带手动规则和暂不支持规则数量。
  - 暂不支持的复杂规则应保留为可管理样本，允许用户通过“筛选非广告规则”流程集中删除。
  - 内置规则库继续补充国内外广告 SDK 域名，但仍需按厂商分类并保持保守，避免误伤正常主站和非广告业务域名。

[支持规则与样本统计口径分离]
- Date: 2026-04-23
- Context: Agent 在继续收口规则页摘要与导入分析文案时发现
- Category: 代码模式
- Instructions:
  - 规则页和导入分析中的“规则总数”应优先表示真实参与拦截的 supported rules。
  - `暂不支持` 规则只作为可管理样本单独统计和清理，不应混入“当前可拦截规则数”。

[广告拦截覆盖率与性能约束]
- Date: 2026-04-23
- Context: 用户希望尽量拦下所有 App 广告，同时继续优先保证性能、耗电、空间占用和网络稳定
- Instructions:
  - 增强拦截能力时，优先选择轻量、低误伤、低耗电的方案，不为了覆盖率引入重型全流量代理。
  - 任何需要全 TCP/HTTPS 接管、明显增加耗电或影响联网稳定的方案，都应先明确架构代价，再决定是否实施。

[路径规则安全降级约束]
- Date: 2026-04-23
- Context: Agent 在处理用户提供的小说 App 路径级广告规则时发现
- Category: 代码模式
- Instructions:
  - 对 `||domain^*/path`、`|https://domain/path` 这类路径级规则，DNS 规则解析不能偷偷降级成整域名拦截。
  - 当前架构下这类规则应视为 unsupported/complex pattern，而不是解析成 `domain`，避免误伤正文接口和主业务 API。

[主流小说 App 拦截增强方向]
- Date: 2026-04-23
- Context: 用户要求继续补充 1/2/3 三类增强，并尽量覆盖主流小说软件广告
- Instructions:
  - 优先补充主流小说 App 常见第三方广告平台域名、公共 DNS/反绕过 IP seed，以及应用识别关键词。
  - 对番茄小说、七猫小说、起点读书、QQ阅读、书旗小说、掌阅、咪咕阅读、米读小说、纵横小说、17K、长读等应用，优先增强识别和统计归类。
  - 即使用户希望“拦截更死”，也不要把不确定的正文主 API 或共享 CDN 整域名直接加入默认黑名单。

[全局广告拦截与资源占用约束]
- Date: 2026-04-23
- Context: 用户要求尽量屏蔽手机所有广告，同时保证系统和 App 运行流畅
- Instructions:
  - 继续优先增强小说 App 底部广告、翻页广告和常见插屏广告的拦截命中率，但不能以明显误伤正文接口为代价。
  - 拦截开启时应尽量保持低后台占用、低耗电、低额外内存分配；拦截关闭后不应残留自启动或后台驻留行为。
  - 任何新增拦截能力都需要兼顾流畅度、网络稳定性和关闭后的资源释放。 

[阅读类广告联盟持续扩充]
- Date: 2026-04-23
- Context: Agent 在继续补充阅读类广告命中时发现
- Category: 代码模式
- Instructions:
  - 默认规则优先持续扩充广告属性明确的竞价、素材分发、广告投放、广告测量域名。
  - 对 DoubleClick、AppLovin、Unity Ads、Vungle、InMobi、PubMatic、Taboola、Outbrain、Amazon Ads、Xandr 等阅读类高频广告联盟，持续细分二级域名覆盖。

[规则扩充的保守边界]
- Date: 2026-04-23
- Context: Agent 在继续补充“全都补上”时发现
- Category: 代码模式
- Instructions:
  - 即使持续扩充域名名单，也应优先选择 ad, ads, pub, bidder, auction, syndication, measurement, sdk-assets 这类广告属性明确的域名。
  - 对 personalization、recommendation、main api、content cdn 这类边界不清的域名，默认不要直接加入内置拦截名单。

[规则筛选交互与拦截优先级补充]
- Date: 2026-04-24
- Context: 用户进一步明确规则页交互、导入分析与拦截优先级要求
- Instructions:
  - “筛选非广告规则”按钮点击后必须弹出结果窗口，窗口内逐条展示被识别出的非广告规则，并允许用户通过每条右侧勾选框选择后删除。
  - “导入并分析”需要继续增强规则识别覆盖，尽量减少把可支持规则误判为无法识别。
  - 功能和界面整体形态不能改变，修复应尽量保持现有页面结构与视觉不变。
  - 不论是重构还是修补，首要目标都是让广告拦截真实生效，同时继续保证网速与正常联网体验。

[规则导入兼容格式补充]
- Date: 2026-04-24
- Context: Agent 在排查“导入后仍拦不住广告”时发现
- Category: 代码模式
- Instructions:
  - 外部规则文件常见的不只是 Hosts/AdGuard 域名规则，还包括 `DOMAIN-SUFFIX`、`DOMAIN`、`HOST-SUFFIX`、`HOST`、`dnsmasq`/`server=`/`local=` 这类规则集格式。
  - 如果导入分析不识别这些格式，用户会出现“规则已导入但实际没多少可拦截规则”的体感问题。

[IPv6 本地 DNS 规范化要求]
- Date: 2026-04-24
- Context: Agent 在排查“VPN 已开但广告拦截体感无效”时发现
- Category: 代码模式
- Instructions:
  - `AdBlockVpnService` 判断本地 DNS 目标地址时，不能直接用 IPv6 字面量字符串比较，必须先做 `InetAddress` 标准化。
  - 否则像 `fd66:66::2` 这类地址在真机包解析后可能变成展开格式，导致 IPv6 DNS 查询未被识别和接管。

[DNS 决策日志需要节流]
- Date: 2026-04-24
- Context: Agent 在继续增强“拦截为什么没生效”的排查能力时发现
- Category: 代码模式
- Instructions:
  - `AdBlockVpnService` 的 DNS 调试日志应记录关键决策点：进入 VPN、命中拦截、缓存放行、上游放行、SERVFAIL、非本地 DNS 绕过、黑名单 IP 丢弃。
  - 这些日志必须按 `domain/qtype/reason` 做节流，避免高频 DNS 请求导致日志 I/O 影响性能和网络体验。

[三页背景图与奖牌图标要求]
- Date: 2026-04-24
- Context: 用户要求恢复早期版本的视觉资源能力
- Instructions:
  - 首页、规则页、统计页都必须支持分别使用不同的背景图。
  - 三页背景图优先通过 `assets/custom/` 下的独立文件加载，避免继续与 `drawable` 占位资源发生同名冲突。
  - 排行榜前三名需要显示金银铜奖牌图标；若运行时未找到奖牌资源，才退回数字名次。
  - 应用图标继续保留自定义资源入口，允许后续替换前景图标资源。

[视觉资源放置约定]
- Date: 2026-04-24
- Context: 用户明确要求将三页背景图和前三奖牌统一放在 assets/custom
- Instructions:
  - 三个界面背景图统一从 `app/src/main/assets/custom/` 读取，文件基名分别为 `home_background`、`rules_background`、`stats_background`。
  - 排行榜前三名奖牌图标统一从 `app/src/main/assets/custom/` 读取，文件基名分别为 `medal_gold`、`medal_silver`、`medal_bronze`。
  - 应用图标不再由代码侧定制，改为使用 Android Studio 的图标自定义流程处理。

[全面稳定性要求]
- Date: 2026-04-24
- Context: 用户要求本轮修改后整体不要出错，且 App 内功能要全部正常
- Instructions:
  - 需要优先消除编译错误和明显结构性错误，避免继续引入新问题。
  - 修复时要检查关键页面和功能链路，确保不是只让代码编过而功能失效。

[Android 编译验证前置条件]
- Date: 2026-06-01
- Context: Agent 在执行“检查所有代码，避免再出现崩溃”时发现
- Category: 环境配置
- Instructions:
  - 本项目执行 `./gradlew :app:compileDebugKotlin` 或其他 Android 构建命令前，需要先通过 `ANDROID_HOME` 或 `/workspace/local.properties` 提供可用的 Android SDK 路径。
  - 缺少 Android SDK 时，完整编译验证会在任务初始化阶段直接失败，稳定性排查需要先区分“代码问题”和“环境缺 SDK”。

[手动输入规则增强]
- Date: 2026-04-24
- Context: 用户要求输入框支持一次粘贴多条域名同时添加
- Instructions:
  - 规则页输入框需要支持批量粘贴多条域名或规则行并一次性添加。
  - 批量手动输入应优先复用现有安全解析逻辑，只导入明确的域名规则，避免把复杂规则误降级为整域拦截。

[规则适配范围与 MITM 激进度]
- Date: 2026-05-26
- Context: 用户要求尽量适配所有类型的规则，并在不明显影响网络和性能的前提下提升 MITM 激进度
- Instructions:
  - 规则引擎应继续扩大可解析、可落地、可参与拦截的规则类型覆盖，优先把更多规则接入现有 DNS 与 MITM 链路。
  - 开启 MITM 模式后，可在网络稳定和性能可接受的范围内采用更积极的深度检查与阻断策略，减少因门槛过高导致的漏拦。

[Root 守护脚本模式]
- Date: 2026-07-11
- Context: Agent 在执行"集成 KSU 模块为原生功能"时发现
- Category: 代码模式
- Instructions:
  - 项目中需要长期轮询进程或文件状态的 Root 守护任务，遵循"JVM 仅作 launcher"模式：通过 `com.HanFeng.adblocker.shizuku.SuSession` 拼 watcher.sh 脚本写入 /data/adb/<namespace>/，再 `nohup sh` 启动后台进程，PID 写入 .pid 文件。
  - 不要在 JVM 内 Thread { while(true) { suSession.execute(...) } }，因为寒枫被系统杀掉后守护必须继续运行；JVM 重新打开 SuSession 后通过 `kill -0 $(cat pid)` 判断守护是否仍在。
  - 工作目录命名约定：/data/adb/<FeatureName>/watcher.sh、watcher.pid、watcher.log。脚本中的 `$` 必须用 Kotlin 字符串的 `\$` 转义，不要用 `$$`（shell 中是当前 PID 不是变量标识符）。

[SNI 拦截与 MITM 解耦]
- Date: 2026-07-12
- Context: Agent 在执行"评论区/激励/翻页内容级广告未拦"排查时发现
- Category: 排错调试
- Instructions:
  - `AdBlockVpnService.handleHttpDecryptPacket` 历史上在 `httpDecryptEnabled=false` 时直接 return false，导致 SNI 拦截（`shouldBlockBySni`/`SniInterceptor.evaluate`）和 QUIC SNI 拦截都没机会运行——纯 DNS 模式下只有规则库 domain 匹配生效。
  - SNI 拦截不需要解密 HTTPS：只看 TLS ClientHello 明文 SNI 字段匹配规则后发 TCP RST，对证书绑定 App 也有效。已通过 `shouldBlockBySniWithoutMitm` 在 `httpDecryptEnabled=false` 时独立运行。
  - `SniInterceptor.evaluate` 零 MITM 依赖：只依赖 `RuleRepository`（规则库）+ `ScoredBlockCache`（学习缓存）+ `isProtectedTrafficDomain`（业务保护），纯 DNS 模式可用。
  - 评论区/底部卡片/翻页/激励类内容级广告（URL 路径区分，如 `api.xxx.com/comment/list`）SNI 仍拦不住——同域名不同路径，只能在 `httpDecryptEnabled=true` 时由 `HttpMitmFilter` 内容级过滤拦掉。

[Shizuku fork 内置集成]
- Date: 2026-07-18
- Context: Agent 在执行"Shizuku 全量 fork 内置主 app"时发现
- Category: 构建方法
- Instructions:
  - shizuku-fork 子工程含 11 个 Gradle module：`aidl / shared / common / api / provider / server-shared / rish / starter / server / manager`，全部在 `/workspace/shizuku-fork/`。除 `rish` (Android 无模拟器运行环境，但能编 AAR) 和 `manager` (含 native build)外其他都纯 Java/Kotlin 库。
  - 主 app 通过 `implementation(project(":shizuku-fork:manager"))` 等依赖把 fork module 集成进 APK，APK 自动包含 `lib/<abi>/libshizuku.so` (starter native binary)、`librish.so` (rich shell)、`libadb.so` (adb 配对 SSL)全部 4 个 ABI。
  - 集成模式：A 路线，客户端 SDK 仍用 `dev.rikka.shizuku:api:13.1.5`，只把 starter native binary 内置。`BuiltInShizukuStarter.activateViaRoot` 从找外部 Shizuku APK 改为读主 app 自身 `applicationInfo.sourceDir`+`nativeLibraryDir/libshizuku.so`，root shell `<libshizuku.so> --apk=<apkPath>` 启动 server。
  - `BuiltInShizukuStarter` 需在 app 启动时调 `BuiltInShizukuStarter.init(context)` 保存 application context 才能跑 activate。
  - fork starter.cpp 内 `SERVER_NAME="hanfeng_shizuku_server"`（避免与官方 Shizuku process 名冲突），`PACKAGE_NAME="com.HanFeng.shizuku"`（拼 authority），`SERVER_CLASS_PATH="rikka.shizuku.server.ShizukuService"`（Java 包名不变）。
  - Shizuku License 第 6 条限制字符串：applicationId=`com.HanFeng.shizuku`、permission=`com.HanFeng.permission.shizuku.*`、intent extra prefix=`com.HanFeng.shizuku.intent.extra.*`、REQUEST_PERMISSION action=`com.HanFeng.intent.action.REQUEST_PERMISSION`，全部不得用 `moe.shizuku.privileged.api` / `moe.shizuku.manager.permission.*` 等。Java 内部包名 `moe.shizuku.*` / `rikka.shizuku.*` 保留不动。
  - 启动主 app 句柄 `BuiltInShizukuStarter.stop` 中 `pkill -f 'hanfeng_shizuku_server'` 与 `rm /dev/socket/hanfeng_shizuku_server`。
  - 主 app Build 时强制 `androidx.core:core:1.13.1` 与 `core-ktx:1.13.1` 否则 fork:manager 拉到 1.16.0 要 AGP 8.6+ 与现行 AGP 8.5.0 不兼容（见 `app/build.gradle.kts` `configurations.all resolutionStrategy.force`）。
  - fork manager module 路径 `/workspace/shizuku-fork/manager/`：res 已精简到只保留 `drawable/ic_launcher.xml + ic_system_icon.xml + ic_default_app_icon_background.xml` + `values/strings.xml + styles.xml + themes.xml + themes_overlay.xml` + `values-night/styles.xml` + `values-v31/themes_overlay.xml` + `mipmap-xxxhdpi/ic_launcher.png` 等。所有 layout 已删（RequestPermissionActivity 已改纯 Java 构造对话框）。所有 values-XX locale strings 已删（只保留默认 values/strings.xml）。
  - NDK 路径：`/usr/lib/android-sdk/ndk/26.1.10909125`（已装），cmake 3.22.1 在 `/usr/lib/android-sdk/cmake/3.22.1/bin/`。CMakeLists.txt `cmake_minimum_required(VERSION 3.22)`，原本要 3.31+ 因环境 cmake 老改降了。
  - shizuku-fork 子 modules 的 build.gradle.kts 中 plugin 不声明版本（通过 `settings.gradle.kts` pluginManagement.plugins 集中管理）。
  - shizuku-fork/:aidl 必须设 `buildFeatures.aidl = true`，否则 AIDL 不生成 stub → server/common/api 找不到 `moe.shizuku.server.IShizukuService` 包报错。
  - `moe.shizuku.manager.application` 是 fork manager 模块全局 lateinit 变量（见 `application.kt`），需要主 app 启动时调 `moe.shizuku.manager.init(application)` 才能让 `Starter.kt` 拿到包路径。当前主 app 暂未做这步——只 BootCompleteReceiver 在开机时才会触发该路径，运行态没用。
  - 编译验证：`./gradlew :app:assembleDebug` 全量通过；APK 含上述 12 个 .so。

[设备标识三个字的修正]
- Date: 2026-07-18
- Context: Agent 在执行"root 区域的主板 ID 显示成内核版本号、SN 码错误"修复时发现
- Category: 业务规则
- Instructions:
  - 主板 ID 正确含义 = SoC 平台代号（全小写英文），如高通 lahaina/taro/kalama、谷歌 oriole/panther/shiba、MTK mt6895/mt6983/sun。绝不可能是 3.9.0 这种内核版本号，也绝不可是 PVT/EVT 工程版本号。
  - 真正能读到主板平台代号的 prop 集（共 6 条，按主→次排序）：ro.board.platform / ro.boot.board.platform / ro.board.hardware / ro.hardware / ro.boot.hardware / ro.soc.model。
  - 之前错误把 ro.boot.hardware.revision / ro.boot.hwversion / ro.boot.hwlevel / ro.boot.hardware.sku / ro.boot.hardware.id / ro.boot.hardware.mlbid / ro.boot.em.modelid / ro.boot.motherboard.id 塞进"主板 ID"，这些 prop 在大多数设备上由 bootloader 写成内核版本号字符串或工程版本号（PVT/EVT），已彻底移除。
  - 主板 ID 随机生成器应当从真实已知 SoC 平台代号表(lahaina/taro/sun/oriole/mt6895/...)选一个返回，而不是瞎编 8 位 hex 字符串。
  - 主板 ID 校验 ^[a-z0-9._\-]+$（必须小写，与历史 SoC 命名一致）。
  - SN 码真实含义 = 拨号盘 *#06# 第三行「SN:」行的值，就是 ro.serialno / ro.boot.serialno 这组硬件序列号，与"修改主板序列号"工具修改的是同一组 prop。两者只是 UI 同一值的不同入口名。SN_PROPS 只含 3 条：ro.serialno / ro.boot.serialno / persist.sys.serialno。
  - 之前错误 SN_PROPS 还含 gsm.serial(实为 IMEI1 别名，改它等于改 IMEI)、ro.product.sn/sys.sn/sys.xiaomi.sn/ro.xiaomi.sn(产品 SN 不是拨号盘 SN)、persist.radio.sn/ril.sn/gsm.sn(RIL-side serial 可能等于 IMEI)，全部已移除。
  - SN 码正确格式 = 厂商自定义不定长字符串，可能含字母、数字、斜杠(如 50936/R3YT02307)；不是 16 位十六进制(那是 Android ID 的格式)；generateRandomSn 已是「5 位 digits + "/" + 8 位 uppercase letter」，符合该格式。
  - SettingsActivity prefill 优先：主板 ID 取 ro.board.platform > ro.boot.board.platform > ro.hardware > ro.boot.hardware > ro.board.hardware > ro.soc.model；SN 码取 ro.serialno > ro.boot.serialno > persist.sys.serialno。不再 fallback 到不存在或被乱填的 prop。
  - 用户区分澄清：主板 ID(平台代号) 与 主板序列号(ro.serialno) 与 SN 码(同 ro.serialno，只是另一个入口)——主板 ID 才是真正独立字段,后两者是同源。


[项目知识：证书安装模块]
- Date: 2026-07-26（2026-07-28 修正）
- Context: 用户在确认任务时说明；后续用户反馈"安装证书到系统"功能失效
- Category: 运维部署
- Instructions:
  - SystemCertInstaller 当前实现优先持久化写盘（remount rw /system + cp；失败再 tmpfs overlay）；
    两个手段都失败才退化到 bind mount（重启失效）。
  - persistent 标记反映是否写盘：success.persistent=true 时 UI 提示"重启后仍生效"，
    false 时提示"重启后失效"。
  - 目标目录：/system/etc/security/cacerts 主路径（旧设备）；/apex/com.android.conscrypt/cacerts
    为 Android 14+ conscrypt 引擎实际加载点，但 APEX 不可写，仅可用 bind mount + nsenter。
