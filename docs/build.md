# 构建与开发

## 当前构建范围

`settings.gradle.kts` 只包含 `:core` 与 `:app`：前者为纯 Kotlin 分句、翻译和会话流水线，后者为 Android Compose UI、控制器、采集服务、模型库与浮窗。桌面客户端、macOS/Windows采集与打包工具、桌面测试和Windows工作流已移除。`gradlew.bat`保留供Windows开发机编译Android使用。

## 环境与命令

- JDK 17、Android SDK Platform 35（`compileSdk` / `targetSdk` 35）。
- `minSdk` 29，仅打包 `arm64-v8a`。
- 无需自行编译原生库；使用 sherpa-onnx 1.13.8 预编译 AAR。当前 Android CI 安装 `build-tools;35.0.0`。

```bash
git clone https://github.com/ZOUYIQAQ/simul-subtitle
cd simul-subtitle
source tools/android-env.sh
./setup.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

`tools/android-env.sh` 只改变当前 shell：优先沿用有效 `JAVA_HOME`，否则尝试 Homebrew openjdk@17；SDK 默认 `$HOME/Library/Android/sdk`，沿用已设置的 SDK 路径；追加存在的命令目录。它不修改用户 shell 配置，需要用 `source` 加载。

`setup.sh` 仅下载运行时 `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar`，固定大小 **38,691,998 字节**，SHA-256 为 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`。已存在且校验通过则跳过；先写 `.part`、校验后转正，超时、有限重试，失败以非零状态退出。脚本不下载 ASR/VAD 权重。

需要代理时可为 setup 设置 `HTTPS_PROXY`；Gradle 可按环境设置 `-Dhttps.proxyHost`、`-Dhttps.proxyPort`。这与应用关于页的更新行为无关。

| 产物 | 路径 |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |

## 模型与许可打包

- `assets.exclude("models/**")` 排除历史模型目录，APK 不包含 ASR 或 VAD 权重。
- 安装后进入「模型库」主动下载；每个 ASR 包包含各自 VAD，无需独立选择。
- 固定源、字节数与 SHA-256 在 `models/ModelCatalog.kt`；逐文件验证后落盘，下载成功不自动启用。用户需点击「启用模型」，同传运行期间禁止切换。
- 权重位于私有 `files/models/<模型标识>/asr/` 与 `vad/`。
- 许可文本随 APK 的 `assets/licenses/` 分发，包括 ReazonSpeech、SenseVoice 与 Silero，不与模型下载文件放在同一目录。事实依据见 [MODEL_SOURCES.md](MODEL_SOURCES.md)。

可检查 APK 内容，确认没有误打包 `.onnx` / `.tflite` 且包含许可文本：

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk
```

## 回归与设备验收

`.github/workflows/android.yml` 执行核心测试、Android 本地单元测试、lint、Debug 打包和 APK 模型排除检查。设备 UI 回归需另外准备 Android 设备；当前 ABI 为 arm64，不能将仅有 x86 原生库的模拟环境当作模型运行验收。

```bash
./gradlew :app:connectedDebugAndroidTest
```

编译、单元测试、UI 示例和真实采集分别提供不同证据。真实浮窗、模型完整下载与启用、旋转/分屏、采集授权与拒绝路径、日志活动保护、付费翻译服务可用性需在相应设备和账户上验证。验收状态以 [ANDROID_QA.md](ANDROID_QA.md) 为准；本文命令不表示本轮已全部执行。

## 当前更新与发行

「设置 → 关于」有两个独立动作：

1. 「检查更新」调用 `AppController.checkUpdate()`，只读取 `https://api.github.com/repos/ZOUYIQAQ/simul-subtitle/releases/latest`，总超时 15 秒。成功显示发行标签；404 提示 Fork 尚无安装包；其他错误显示失败信息。
2. 「打开项目仓库」打开 `https://github.com/ZOUYIQAQ/simul-subtitle`，用户自行查看发行版并下载安装。

当前不比较 `versionCode` 判断新旧，不读取 `update.json`，没有自动检查、应用内 APK 下载/安装、多代理回退，也没有「跳过版本」「稍后提醒」「永不检查」等偏好。仓库保留的 `AppUpdater.kt`、旧 Provider 与安装权限不是当前 UI 已启用功能，不应据此恢复旧更新协议说明。

## 签名与发布前检查

包名为 `io.github.korenkrita.simulsub`。当前 Release 构建配置仍引用 `signingConfigs.getByName("debug")`，因此签名取决于构建机器的 keystore；不能把其他机器新生成的 debug key 当作原发行证书。覆盖安装要求相同包名、兼容版本号与相同签名；正式发行前必须确认维护者指定的证书和密钥管理方案。

构建后使用已安装 SDK 的 `apksigner` 核对证书，使用 `aapt` 或 `apkanalyzer` 核对包名与版本；与实际目标旧包比较。不要把旧文档的证书指纹或旧上游仓库发行流程作为本 Fork 已验证事实。发布说明、标签和 APK 版本应一致；发布后回读安装包核对哈希。当前客户端不要求上传 `update.json`。
