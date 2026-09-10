# Ditto

把官方 Kimi Code CLI 完整运行在 Android 与 iOS 本地环境中的非官方移动端容器。

本项目不是兼容层，也没有第二套 Agent 内核。代码理解、文件修改、Shell、审批、会话、Skills、MCP、登录与模型配置全部由官方 [`@moonshot-ai/kimi-code`](https://www.npmjs.com/package/@moonshot-ai/kimi-code) 提供；移动 App 只负责 Alpine/Node 运行环境、本地回环连接、触屏 WebView 与系统文件能力。

## 架构

```text
Android / iOS App
  ├─ Alpine Linux（手机本地）
  ├─ Node.js 22.19+
  ├─ 官方 Kimi Code CLI
  │    └─ kimi web --host 127.0.0.1
  └─ 原生 WebView → Kimi Code 本地 Web UI / REST / WebSocket
```

- 唯一 Agent 引擎：Kimi Code CLI。
- 数据保存在设备内的 `~/.kimi-code` 与 `/workspace`。
- 首次启动在设备内安装固定版本的 CLI；应用不会伪造 Kimi Code 的客户端身份。
- Kimi Code OAuth、API Key、会话和权限规则直接复用官方实现。

## 当前开发状态

主干正在从旧应用架构迁移到 Kimi Code 单内核。Android 和 iOS 使用同一套 Compose 移动容器，平台层分别提供本地 Alpine 运行时。

Android 构建：

```powershell
./gradlew :app:assembleDebug
```

iOS 构建需在 macOS/Xcode 中执行，工程入口位于 `iosApp/Aether.xcodeproj`。

## 声明

这是社区开发的非官方客户端，与 Moonshot AI / Kimi 官方无隶属关系。Kimi Code CLI 本身采用 MIT License；本仓库其余代码遵循仓库根目录的 GPL-3.0 License。
