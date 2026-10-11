# LilycoApprove

**Android 无障碍自动点按助手** —— 无障碍服务读全树、按文本定位节点并代点 Approve / Reject，
全程离线；内置端侧 GGUF 推理与离线 TTS，可经 Termux 桥接扩展。

<p align="left">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2011%2B-3ddc84">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-30-blue">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-36-blue">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-green">
</p>

---

## 它解决什么问题

很多流程卡在**一个「确认 / 拒绝」按钮**上：需要人手点，但内容重复、时机琐碎。
LilycoApprove 把这一步交给无障碍服务——**读屏找到目标节点，代你点下去**，
不需要 root、不注入目标进程、不上传任何屏幕内容。

## 三条能力

| 能力 | 实现 | 说明 |
|---|---|---|
| **无障碍执行器** | `ApproveAccessibilityService` | 读 `rootInActiveWindow` 全树 → `findAccessibilityNodeInfosByText` 按文本定位 → `ACTION_CLICK`（自动向上找可点击祖先）或 `dispatchGesture` 手势点按 |
| **端侧推理** | `aichat` 模块（llama.cpp JNI） | 内置 `router_v13-Q4_K_M.gguf`，`GgufMetadataReader` 读元数据，`InferenceEngine` 经 JNI 到 `ai_chat.cpp`；**数据不出设备** |
| **离线语音** | `TtsManager` + sherpa-onnx | VITS `vits-icefall-zh-aishell3` 中文 TTS，本地合成，不依赖系统语音服务 |

可选 **Termux 桥接**（`TermuxBridge`，需 `com.termux.permission.RUN_COMMAND`）：
把更重的命令交给 Termux 执行。

## 使用前提

- Android **11+**（`minSdk 30`）
- 在系统设置里为 LilycoApprove 打开**无障碍服务**（首次启动有引导）

## 构建

CI（`.github/workflows/android-ci.yml`）在云端完成全部依赖拉取与构建：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

云端步骤会自动准备：

- llama.cpp（JNI 桥源码，`b11045`）
- 端侧模型 `router_v13-Q4_K_M.gguf`
- sherpa-onnx AAR `1.13.8`
- VITS 中文 TTS 模型 `vits-icefall-zh-aishell3`

产物：`app/build/outputs/apk/debug/*.apk`（CI artifact `lilyco-approve-debug`）。

## 目录结构

```
app/                              主应用（Compose UI + 无障碍服务 + TTS）
  ApproveAccessibilityService.kt  读屏 + 代点
  TtsManager.kt / TtsSeed.kt      离线语音
  TermuxBridge.kt                 可选 Termux 桥
  ui/main/                        MainScreen / ChatSection / ViewModel
aichat/                           端侧推理模块（llama.cpp JNI + GGUF 读取）
gradle/                           版本目录（libs.versions.toml）
```

## 许可

MIT —— 见 [LICENSE](LICENSE)。
