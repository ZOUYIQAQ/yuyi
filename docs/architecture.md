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

`ui/UiContract.kt` 定义同传、字幕、设置三个主导航及模型库、场景术语两个设置内详情入口。Compose UI 交还业务动作给 `AppController`，不在页面中下载模型、操作文件或请求翻译。翻译 API 的草稿、保存与测试相互分开；`TranslationPresets.kt` 仅填写草稿，跨端点应用预设清空草稿凭据、请求头与 User-Agent。

设置首页按字幕与翻译、个性化、应用分组，详情通过顶栏或系统返回回到来源；包括翻译API、字幕样式、外观、系统权限、高级设置、使用记录、关于，并提供模型库、场景管理和重新运行首次引导入口。关于页首屏声明 Android Fork 与原作者 KorenKrita，MIT 全文随 APK 提供，离线可读。所有仓库外链交给 MainActivity 的异常保护路径。上下文由 `Pipeline.PREVIOUS_SENTENCES = 2` 与 `MAX_PREVIOUS_SENTENCES = 5` 控制，窗口内容是非空定稿原文；不由字幕可见行数决定。

`CaptionBoard.setRetention()`是新版服务的唯一显示策略入口。`CaptionLimitMode`互斥选择句数或译文字数，默认句数。句数包含实时草稿，字数按译文Unicode码点计算，超长单句的快照仅保留最新尾部。只裁剪显示快照，不改原始定稿、日志或翻译上下文。旧区间接口保留作历史兼容与迁移，新句数从旧区间上限迁移；captionLimitMode、captionSentenceCount和keepChars在同一次偏好编辑中保存。

`CaptionOverlayView`用于实际浮窗与设置预览，实际服务不再使用手动行数上限；仅保留屏高40%和用户固定高度等物理约束。新安装默认showJa=false、showStats=false，显式保存的用户开关仍保留。设置预览是页面内可拖动面板，可切横屏/竖屏画幅，持续接收样式变化；离开设置页面销毁，不启动录音、模型或真正字幕服务。

`ThemePalettes.kt` 提供海蓝、松绿、鸢尾、山茶、蔷薇、岩灰六种配色；Android 12 起可用动态色，旧系统回退手动配色。新安装默认取自图标原图的山茶（amber），已有手动选择保留，动态取色默认关闭。浅深模式独立选择，应用主题不改字幕配色。

模型包各带 VAD，下载校验后需手动启用，运行会话内固定模型。许可文本在 APK `assets/licenses/`，权重在应用私有 `files/models/`，两者分开。

## 当前更新入口

`AppController.checkUpdate()` 仅在用户点击时 GET `https://api.github.com/repos/ZOUYIQAQ/yuyi/releases/latest`，15 秒总超时；成功显示 `tag_name`，404 提示尚未发行，其他错误显示失败信息。它不比较版本号，不下载 `update.json` 或 APK。另一个「打开本 Fork 仓库」动作通过外部应用打开 `https://github.com/ZOUYIQAQ/yuyi`。

旧更新类、权限或 Provider 的保留不代表当前 UI 接通自动更新；当前没有自动检查、代理回退下载、应用内安装或跳过版本偏好。


### 自定义主题与桌面图标

自定义颜色以不透明RGB种子保存到appearance.custom_color，保存时将color设为custom并退出动态取色，浅深色角色仍由Material官方算法生成。调色对话框只保存草稿，确认才派发，取消不写入。默认山茶使用原图发色D38569、花色E24E37、奶油FAF0E2和鼠尾草绿9AAF87。

LauncherIconChoice表示矢量彩色/纯色、背景、飘落装饰和纯色配色。资源生成器从用户SVG生成有限资源组合，飘落开关同时控制花瓣与绿叶，头花不受影响；LauncherIcons仅启停本包ActivityAlias并持久化选择，MainActivity仍为同一个业务入口。API33以上批量更新，旧系统先启用再关闭；写入失败恢复前态。图标对话框预览加载真实AdaptiveIconDrawable，不自行绘制近似图。

深色山茶主题仅降低橙棕与山茶红强调色的饱和度，保留色相来源，文字与容器色仍由官方算法生成；浅色图中取色方案保留。场景编辑的返回入口固定于Scaffold顶栏，滚动表单和键盘不改变其位置。

纯色图标采用10种预设色，自动匹配当前手动、自定义或动态主题的官方主色角色（Lab最近色）；手动色不会跟随主题变更。每色按背景使用官方深浅角色，110个Launcher入口共用2张透明轮廓。choice、follow_theme、resolved_tone独立保存，v3入口用于安全迁移旧已禁用的默认别名。

### 羽译1.4.0的模型与字幕窗口约束

- 模型删除在正式确认后运行，后台重查WorkManager与运行租约。下载、校验、模型租约和删除共用互斥闸门；停止按钮释放页面运行状态后，租约仍持续到管线与ASR/VAD彻底释放。删除被确认后全程不可取消，最终重扫与闸门释放必定执行，旧任务终态不会复活已删除模型。
- 实时草稿单独放在黑框下方的透明兄弟区域，预留通常为两行译文加可选一行原文，与当前草稿是否存在无关。正式黑框自动伸缩时以底边为锚向上展开；拖动、旋转与resize同步更新定位。实际服务和设置预览使用相同原生视图。
- 窗口高度较小时统一核算正式正文、诊断、内部边距与外部草稿预算；低横屏或大字号会减少草稿可见行及原文预留，优先保留可读的最新译文，不用裁断窗口或扩大黑底冒充外部预留。
- API测试使用临时草稿，不写入Prefs；建议绑定草稿及原存配置快照，只有显式「替换并保存」才提交。未保存退出确认由统一根导航处理。

首次引导状态由AppController持有，依次为PERMISSIONS、API、MODEL；最后继续或跳过写入appearance.onboarding_completed。导航层复用API未保存确认，模型步骤仅提供下载、取消和启用；不提前申请一次性的MediaProjection授权。模型库和场景列表保存进入来源，顶栏和系统返回使用同一动作。首页移除内存字幕预览，必要权限全部授予后不渲染权限分区。

系统启动页独立使用透明人物与飘落装饰，SplashTheme在Activity首帧前同步应用背景，并在Android12+用官方setSplashScreenTheme登记下次冷启动主题。六预设的浅深背景与应用完全一致；任意自定义种子的系统启动底色取最近的预设，Activity首帧为精确颜色。Android12/13动态色使用相近系统中性色，Android14+与Material3同用system_background。Android10/11最早启动底色仍使用默认山茶并跟随系统明暗。参考[官方SplashScreen API](https://developer.android.com/reference/android/window/SplashScreen#setSplashScreenTheme(int))。
