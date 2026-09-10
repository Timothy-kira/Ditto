# Kimi Code Mobile

An unofficial mobile container that runs the official Kimi Code CLI locally on Android and iOS.

This is not a compatibility shim and does not contain a second agent loop. Code understanding, file edits, shell commands, approvals, sessions, Skills, MCP, authentication, and model configuration are all provided by the official [`@moonshot-ai/kimi-code`](https://www.npmjs.com/package/@moonshot-ai/kimi-code). The app only owns the Alpine/Node runtime, loopback transport, touch-friendly WebView, and native file integration.

## Architecture

```text
Android / iOS app
  ├─ Alpine Linux (on device)
  ├─ Node.js 22.19+
  ├─ Official Kimi Code CLI
  │    └─ kimi web --host 127.0.0.1
  └─ Native WebView → local Kimi Code Web UI / REST / WebSocket
```

- The only agent engine is Kimi Code CLI.
- Device-local state lives in `~/.kimi-code` and `/workspace`.
- The app installs a pinned CLI version on first launch and does not spoof Kimi Code's client identity.
- OAuth, API keys, sessions, and permission rules use the official implementation directly.

## Development status

The main branch is being migrated to the single-engine Kimi Code architecture. Android and iOS share the Compose mobile container and provide their own local Alpine runtime hosts.

Build Android with `./gradlew :app:assembleDebug`. Build iOS on macOS with the Xcode project at `iosApp/Aether.xcodeproj`.

## Disclaimer

This community project is unofficial and is not affiliated with Moonshot AI or Kimi. Kimi Code CLI is MIT-licensed; the rest of this repository remains under the root GPL-3.0 license.
