# HyperAiNotificationCloud

面向 LSPosed API 102 的 HyperOS 通知云端摘要模块，使用 OpenAI 兼容接口生成摘要，沿用系统通知展示逻辑：收起显示摘要，展开显示原文。

## 配置

在模块设置页填写 API 地址、API Key、模型和提示词。API Key 默认为空，仅保存到设备上应用的私有配置中，源码和构建产物不包含个人凭据。

触发策略支持三个独立设置：

- **亮屏总结**：默认开启；关闭后，仅在息屏时总结。
- **通知数量阈值**：默认 1 条；按尚未发送总结请求的通知计数，至少 1 条。
- **等待时间**：默认 0 秒；达到数量阈值且屏幕条件满足时开始计时。等待期间新通知加入同一批，不延长计时。通知取消后低于阈值，或关闭亮屏总结后亮屏，计时取消，条件再次满足时重新计时。

保存配置即时应用于待处理通知。所有应用开关、最小通知字数继续独立生效。

在 LSPosed 中启用模块，勾选系统框架、通知管理服务和设置，安装或更新模块后重启设备。单独修改模块配置无需重启。

## 构建

需要 JDK 17 或更新版本、Android SDK Platform 34 和 Build Tools 35.0.0；可通过 `ANDROID_SDK_ROOT` 指定 SDK。编译依赖位于 `libs/libxposed-api-102.jar`。

```bash
./build.sh
```

调试 APK 输出到 `dist/`，中间产物位于 `build/`。源码路径由脚本自动识别。

macOS 正式签名使用外部 keystore，密码从钥匙串读取。指定 `RELEASE_KEYSTORE` 后同时输出正式 APK；`RELEASE_KEY_ALIAS` 默认 `finall`，`RELEASE_KEYCHAIN_SERVICE` 默认 `hyper-ai-third-party-mcp.release-keystore`，钥匙串账户使用当前系统用户。

## 代码

- `Config.java`：配置键和无凭据的默认值。
- `MainActivity.java`：配置界面和测试入口。
- `SummarizeProvider.java`：提供触发配置，执行云端 API 请求。
- `ModuleMain.java`：系统 Hook、原生通知队列和摘要回调。
- `SummaryScheduler.java`：亮屏条件、数量阈值、等待时间及息屏唤醒调度。
