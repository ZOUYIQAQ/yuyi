# 整体架构

## 端到端数据流

Android 应用与共享核心 `:core` 共用同一套分句、识别与翻译流水线；只有最前端的**系统声音采集**与最末端的**悬浮窗渲染**是 Android 侧实现。仓库已移除桌面客户端与专属采集工具：

```text
系统 / 应用播放的声音（16 kHz 单声道 PCM）
  Android : AudioPlaybackCapture（录制允许捕获的媒体/游戏音频）
        │
        ▼
  Silero VAD（每 32 ms 一帧，输出“正在说话”的概率）
        │
        ▼
  Segmenter（将连续语音切分为句子）
    • 边听边出：说话期间每 0.7 s 输出一次草稿（当前句累积音频）
    • 停顿定稿：静音满 0.4 s 定稿；单句最长 7 s，超长时在该句最长停顿处切分
    • 短句等待：不足 1.2 s 的短语音先等最多 1.5 s，若继续开口则合并为同一句
    • Jev 断句（可选）：满 5 s 或停顿 0.4 s 时先问 Jev 是否说完；未说完则继续蓄音，最长 10 s 强制切分
        │
        ▼
  ASR 工作线程：模型库中已下载并启用的识别模型（ReazonSpeech transducer 或 SenseVoice，sherpa-onnx int8 离线）
       识别模型与 Silero VAD 都不随 APK 分发，需在安装后由用户在模型库下载并校验
        │
        ▼
  MT 工作线程：用户自备的 OpenAI 兼容翻译接口（BYOK，流式请求）
    • 可选开启 Jev 译文检查：发现明显误译时附带修正提示重译一次
        │
        ▼
  悬浮字幕窗：草稿较淡、较小并带省略标记，定稿采用所选字幕配色
```

---

## 模块与代码结构

仓库当前只构建两个 Gradle 模块：共享核心 `:core` 与 Android 客户端 `:app`。桌面客户端与专属构建、采集和测试工具已删除。

### 1. 跨平台核心（`core/src/main/java/io/github/korenkrita/simulsub/`）

不依赖 Android SDK，由 Android 应用与单元测试直接共用：

| 文件 | 职责 |
|---|---|
| `Segmenter.kt` | 语音分句状态机：周期草稿、停顿定稿、超长切分、短句合并等待、Jev 断句协同 |
| `Pipeline.kt` | ASR 与 MT 双工作线程调度、过时草稿折叠、长句开头吞字补齐、滑动上文窗口、相同文本译文复用、过载保护 |
| `Translation.kt` | 翻译提示词构建（面向前缀缓存排布）、场景与术语表管理、支持语言列表、BYOK 流式客户端与关闭思考参数自动探测 |
| `Jev.kt` | TypeSafe Jev 客户端及「句尾是否说完」「译文是否译错」两个结构化判断定义 |
| `SessionLog.kt` | 会话使用记录（JSONL）后台异步写入，采用 `.part` 临时文件并在正常结束时原子转正 |
| `ServiceStatus.kt` | 运行时状态与错误提示定义 |
| `SetupOrder.kt` | 首次启动与配置引导顺序判定 |

### 2. Android 客户端（`app/src/main/java/io/github/korenkrita/simulsub/`）

| 文件 | 职责 |
|---|---|
| `CaptionService.kt` | 前台服务：采集 `AudioPlaybackCapture` 音频、从应用私有模型目录加载会话固定的所选模型、驱动 `Pipeline`、管理悬浮窗生命周期 |
| `Engines.kt` | `sherpa-onnx` 离线识别与 Silero VAD 的 Android 封装（按模型类型映射 `asr/` 与 `vad/` 下的文件路径） |
| `models/` | 模型库：固定清单与 SHA-256（`ModelCatalog.kt`）、私有存储根 `files/models`（`ModelRepository.storageRoot`）、下载与落盘校验（`ModelDownloader.kt`、`ModelInstaller.kt`）及系统前台任务（`ModelDownloadWorker.kt`） |
| `ui/` | Jetpack Compose Material 3 界面：同传、模型库、场景、设置与记录，按窗口宽度在底部导航与侧边导航之间切换 |
| `CaptionOverlayView.kt` | 悬浮字幕视图、四角拖拽缩放与工具栏交互 |
| `MainActivity.kt` | Compose 宿主、系统权限与采集授权、日志分享和外部链接 |
| `AppController.kt` | 页面状态与业务动作、翻译草稿保存与测试、模型库协调、场景编辑、日志索引与清理、手动检查发行版 |
| `Prefs.kt` | 设置持久化与 Android Keystore 密钥加密 |
| `AppUpdater.kt` | 保留的旧更新实现，当前 Compose 关于页不使用其自动检查、下载或安装流程 |

## 草稿与定稿策略

- **过时草稿自动折叠**：识别队列中若积压了多份草稿，旧草稿会被直接合并丢弃，只识别最新一份。若在工具栏选择「说完再出」，会跳过用于字幕展示的草稿识别与翻译；启用 Jev 断句时仍可能识别候选句尾用于判断。
- **定稿按序处理**：正常运行时定稿按顺序识别与翻译，不按草稿折叠策略丢弃；用户停止、失败或过载收尾可能取消剩余任务，见下方过载保护。
- **长句开头吞字自动补齐**：语音较长时，离线识别模型偶发吞掉定稿开头的词。若检测到草稿已识别出的前半句在定稿中丢失，流水线会自动用草稿前半句补齐再拼接定稿后半句；接缝处若出现重复字词，可通过「设置 → 高级设置 → 去掉衔接处重复的字」开关消重（默认关闭）。
- **相同原文译文复用**：同一句话的草稿与定稿（或相邻草稿）识别出的文本与提示词完全相同时，直接复用上一次译文，不重复发起翻译请求。

---

## 过载保护

当识别或翻译速度持续跟不上语速时，流水线不会无限积压队列，而是按明确的三步策略停机排空：

1. **停止新采集**：当等待翻译的定稿累计音频时长超过 **30 秒** 时，立即停止采集新音频，并在悬浮窗提示过载。
2. **排空已收队列**：继续翻译已进入队列的定稿，最长等待 **60 秒**（用户手动点击停止则会立即结束）。
3. **清理与收尾**：超时或排空后，取消剩余未处理的句子与进行中的网络请求，释放模型资源，并在悬浮窗明确报告「全部翻译完」或「已放弃 N 句」；报告后不再有新字幕弹出。

---

## 延迟统计

启用使用记录时，会记录字幕从采集到上屏的各阶段耗时；logcat 另有耗时统计（详见 [logs.md](logs.md)）。Android 端采集时间优先采用 `AudioRecord` 硬件时间戳；设备不支持硬件时间戳时回退为读取时间，并在耗时数字前标注 `~`。

## 页面、设置与显示边界

`ui/UiContract.kt` 定义同传、模型库、场景术语、设置四个导航入口。Compose UI 交还业务动作给 `AppController`，不在页面中下载模型、操作文件或请求翻译。翻译 API 的草稿、保存与测试相互分开；`TranslationPresets.kt` 仅填写草稿，跨端点应用预设清空草稿凭据、请求头与 User-Agent。

设置分为翻译 API、字幕样式、外观、高级设置、使用记录、关于。上下文由 `Pipeline.PREVIOUS_SENTENCES = 2` 与 `MAX_PREVIOUS_SENTENCES = 5` 控制，窗口内容是非空定稿原文；不由字幕可见行数决定。

`CaptionOverlayView` 同时用于实际浮窗与设置示例。最多显示行数为 0（不限）或 1–20，按实际排版合计原文与译文行，保留最新行；耗时与状态不计入。仍受 `CaptionService.MAX_CAPTION_FRAC = 0.4` 与固定高度限制，取更严格值。此设置不改变流水线历史、日志或翻译上下文。

`ThemePalettes.kt` 提供海蓝、松绿、鸢尾、暖砂、蔷薇、岩灰六种配色；Android 12 起可用动态色，旧系统回退手动配色。浅深模式独立选择，应用主题不改字幕配色。

模型包各带 VAD，下载校验后需手动启用，运行会话内固定模型。许可文本在 APK `assets/licenses/`，权重在应用私有 `files/models/`，两者分开。

## 当前更新入口

`AppController.checkUpdate()` 仅在用户点击时 GET `https://api.github.com/repos/ZOUYIQAQ/simul-subtitle/releases/latest`，15 秒总超时；成功显示 `tag_name`，404 提示尚未发行，其他错误显示失败信息。它不比较版本号，不下载 `update.json` 或 APK。另一个「打开项目仓库」动作通过外部应用打开 `https://github.com/ZOUYIQAQ/simul-subtitle`。

旧更新类、权限或 Provider 的保留不代表当前 UI 接通自动更新；当前没有自动检查、代理回退下载、应用内安装或跳过版本偏好。
