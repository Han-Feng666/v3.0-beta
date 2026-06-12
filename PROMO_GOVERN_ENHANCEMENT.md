推广治理增强补丁说明

目标：增强解密和解析能力，识别更多可治理推广 App

修改方案（按优先级）：

1. **增强标签获取** - 已在 buildThirdPartyPromoTarget 中使用 safeGetApplicationLabel
2. **添加缓存机制** - 避免重复查询 PackageManager
3. **增强日志记录** - 记录每个 App 的识别过程和结果
4. **放宽识别条件** - 在 looksLikeThirdPartyPromoApp 中添加更多关键词
5. **增强风险评估** - 多维度计分制评估
6. **增强分类推断** - 30+ 分类维度精准匹配

当前版本已通过编译（BUILD SUCCESSFUL），主要需要增强 looksLikeThirdPartyPromoApp 函数的识别能力。

建议修改：
- 在 looksLikeThirdPartyPromoApp 的高置信度标签列表中增加更多推广相关关键词
- 在 高置信度包名列表中增加更多推广相关包名模式
- 在知名第三方包名前缀列表中添加更多厂商前缀
- 添加日志记录便于调试

增强后的识别逻辑：
1. 知名第三方包名前缀匹配（最精确）
2. 高置信度标签匹配（最宽松）
3. 高置信度包名匹配（较宽松）
4. OEM 推广组件匹配（较严格）

命中任意一条即认为是可治理推广 App。
