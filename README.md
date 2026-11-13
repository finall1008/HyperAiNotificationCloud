# HyperAiNotificationCloud

面向 LSPosed API 102 的 HyperOS 通知云端摘要模块，使用 OpenAI 兼容接口生成摘要，沿用系统通知展示逻辑：收起显示摘要，展开显示原文。

## 配置

在模块设置页填写 API 地址、API Key、模型和提示词。API Key 默认为空，仅保存到设备上应用的私有配置中，源码和构建产物不包含个人凭据。

触发策略支持三个独立设置：

- **亮屏总结**：默认开启；关闭后，仅在息屏时总结。
- **通知数量阈值**：默认 1 条；按尚未发送总结请求的通知计数，至少 1 条。
- **等待时间**：默认 0 秒；达到数量阈值且屏幕条件满足时开始计时。等待期间新通知加入同一批，不延长计时。通知取消后低于阈值，或关闭亮屏总结后亮屏，计时取消，条件再次满足时重新计时。

保存配置即时应用于待处理通知。所有应用开关、最小通知字数继续独立生效。

模块配置由独立后台线程读取并缓存，通知调度与摘要写回只读取缓存，避免模块应用冷启动或 Provider 响应缓慢时阻塞 system_server 主线程。读取期间再次变更配置会合并后重新读取；读取失败时保留上次成功配置。

在 LSPosed 中启用模块，勾选系统框架、系统界面（`com.android.systemui`）、通知管理服务和设置，安装或更新模块后重启设备。单独修改模块配置无需重启。

## 收拢通知综合摘要

默认开启，可在触发策略中关闭。支持应用主动分组和 HyperOS 自动收拢，按系统实际生效的通知组分别处理。同组至少两条可总结通知、总字数超过最小字数时，一次云端请求生成综合要点和长通知各自的摘要；短通知保留原文。亮屏条件、待总结数量阈值和等待时间继续生效。

收拢时保留底层子通知的单行占位、卡片高度、叠卡阴影、圆角裁剪与展开动画不动，仅在 `NotificationChildrenContainer` 原有的两到三行预览槽位区域内覆盖单个多行 `TextView`（复用系统 `[≡+AI]` 徽章与主题字色），连贯展示整段综合摘要并随展开手势或动画同步淡出。展开组后显示各条通知的单条摘要或短原文，展开长通知查看完整原文。系统隐藏敏感内容或处于静默折叠条时保留原生显示。

每次最多总结最新十条通知，按通知时间排列。组成员或原文变化后立即清除过期摘要，并丢弃仍在请求中的旧结果；仅减少组成员时保留原生预览，等待新通知或内容更新触发下一次综合摘要。模型返回不合规 JSON 或缺少有效组摘要时保留原生预览，已解析出的有效单条摘要仍可应用。

## 构建

需要 JDK 17 或更新版本、Android SDK Platform 34 和 Build Tools 35.0.0；可通过 `ANDROID_SDK_ROOT` 指定 SDK。编译依赖位于 `libs/libxposed-api-102.jar`。

```bash
./build.sh
```

APK 输出到 `dist/`，中间产物位于 `build/`。源码路径和 APK 版本号由脚本自动识别。

正式签名使用外部 keystore：指定 `RELEASE_KEYSTORE` 后输出正式 APK，未指定时输出调试 APK。`RELEASE_KEY_ALIAS` 默认 `finall`。CI 通过环境变量 `HYPERAI_SIGNING_PASSWORD` 提供签名密码；macOS 本地构建从钥匙串读取，`RELEASE_KEYCHAIN_SERVICE` 默认 `hyper-ai-third-party-mcp.release-keystore`，钥匙串账户使用当前系统用户。

## GitHub Release

仓库 Secrets 保存 `RELEASE_JKS_BASE64`（正式 keystore 的 Base64 编码）与 `RELEASE_STORE_PASSWORD`（签名密码）。工作流只将 keystore 解码到 runner 的临时目录，构建完成后清理。

推送与 `AndroidManifest.xml` 中版本号一致的 `v` 前缀标签，例如 `v1.0.0`，会自动构建、核对正式签名证书，并发布包含 APK 和 SHA-256 校验文件的 GitHub Release。更新版本时同时递增 `versionCode`，以支持覆盖安装。

也可在 Actions 中手动运行 Release 工作流：选择版本标签时发布 Release，选择分支时仅生成构建制品。

## 代码

- `Config.java`：配置键和无凭据的默认值。
- `MainActivity.java`：配置界面和测试入口。
- `SummarizeProvider.java`：提供触发配置，执行云端 API 请求。
- `ModuleMain.java`：系统服务捕获、通知生命周期 Hook 和模块初始化。
- `SummaryScheduler.java`：亮屏条件、数量阈值、等待时间及息屏唤醒调度。
- `NotificationSummaries.java`：活跃通知分组、请求去重、过期校验和原生摘要写回。
- `GroupSummaryData.java`：原文留存、组内容指纹与结构化响应解析。
- `SystemUiGroupText.java`：收拢预览槽位的单 `TextView` 合并展示与展开过渡同步。

## 模拟器集成验证

先构建并安装模块，在 LSPosed 中启用上述作用域并重启模拟器，再运行：

```bash
ANDROID_SERIAL=emulator-5580 ./tests/run-android.sh
```

测试 APK 与已安装模块须使用相同签名；正式签名时沿用构建所用的 `RELEASE_KEYSTORE` 等环境变量。测试通过本地固定响应服务验证分组请求、短通知准入、原文保留、过期结果丢弃、原生预览与展开收拢、配置开关和自动分组，并将结果写入 `build/tests/result.txt`。测试临时替换 API 配置，结束时恢复原配置并清理测试通知。

添加 `-e live true` 可用设备现有 API 配置发送一组固定样例，单独核验真实模型的综合要点与单条摘要 JSON 协议；此模式保留设备配置。
