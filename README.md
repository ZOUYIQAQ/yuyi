# 羽译 · Yuyi

羽译是面向 **Android 直播与视频的悬浮听译字幕工具**：在设备本地识别正在播放的语音，通过你自己配置的文字翻译 API，将译文显示在其他应用上方。

[下载正式版](https://github.com/ZOUYIQAQ/yuyi/releases/latest) · [快速上手](#快速上手) · [使用文档](docs/README.md) · [English](README.en.md)

仅支持 **Android 10 及以上、arm64-v8a 手机和平板**。APK 不内置识别模型，安装后选择下载并启用。

## 下载

进入 [最新正式版](https://github.com/ZOUYIQAQ/yuyi/releases/latest)，下载 `yuyi-v1.8.4-android-arm64-v8a.apk` 或后续版本对应的 APK，直接安装即可。每个版本同时提供 `SHA256SUMS.txt` 校验文件。

如果此前安装的是 Actions 或本地测试包，因正式版签名不同，无法直接覆盖安装。请先保留需要的配置和记录，再自行卸载测试版并安装正式版。卸载会清除本地配置、已下载模型和记录；后续正式版使用同一签名更新。

## 界面

| 同传 | 模型库 | 字幕样式预览 |
|---|---|---|
| ![同传首页](docs/images/screens/home.png) | ![模型库](docs/images/screens/models.png) | ![字幕样式预览](docs/images/screens/subtitle.png) |

<details>
<summary>平板同传界面</summary>

![平板同传](docs/images/screens/tablet-home.png)

</details>

## 核心体验

- **边看边读字幕**：在直播或视频上方显示译文，也可开启原文。点按字幕展开工具栏，拖动调整位置、缩放或锁定。
- **本地识别，自选翻译服务**：提供两种识别模型；翻译支持自配 OpenAI 兼容 API，可保存多套配置，按需切换。
- **按观看习惯调整字幕**：默认只显示译文，保留最近 3 句定稿；可调整字号、配色、透明度和字幕框大小，配色列表直接展示各套字幕效果，也可通过可拖动的浮动预览调整细节。
- **手机和平板适配**：默认采用「山茶」暖色外观，支持深浅色、自定义配色，以及 Android 12 及以上的动态颜色。

还可在「设置 → 场景与术语」补充直播背景与固定译名。使用记录默认关闭，需要时再主动开启。

## 快速上手

首次打开会依次引导权限、翻译 API 和识别模型；也可稍后在设置中补齐。

1. **授权**：按引导允许录音和悬浮显示；每次开始同传时，确认系统的屏幕共享／音频采集授权。
2. **配置翻译 API**：进入「设置 → 翻译 API」，选择预设或填写兼容接口的地址、模型 ID 和密钥，测试连接后保存。测试本身不会自动保存配置。
3. **下载并启用模型**：进入「设置 → 模型库」，选择一个模型下载，完成后点击「启用模型」。
4. **开始听译**：在「同传」选择原声语言、译文语言和场景，点击「开始同传」，再切换到正在播放的直播或视频。

### 选哪个识别模型？

| 模型 | 原声语言 | 下载大小 | 特点 |
|---|---|---|---|
| ReazonSpeech | 日语、英语 | 约 74.1 MiB | 体积较小，适合以日英内容为主、希望少占存储空间的用户 |
| SenseVoice Small | 中文、英语、日语、韩语、粤语 | 约 229.1 MiB | 语言覆盖更多，识别结果带标点与数字规整 |

两种下载包均包含所需的静音检测模型。切换模型前请先停止同传。

## 常见问题与入口

**可以完全离线翻译吗？** 语音识别在本地完成，翻译需要你配置的文字 API；使用在线服务时需要联网，识别文字会发送给该服务，原始音频不上传。也可连接自己在本机或局域网提供的兼容翻译接口，详见[翻译配置](docs/translation.md)。

**为什么有些视频没有字幕？** 音源应用、受保护内容及系统的内录限制会影响采集；其他允许内录且同时播放的声音也可能被识别，使用时请暂停无关音源。

**会保存观看记录吗？** 默认不保存会话记录，也不保存原始音频。文字记录需在「设置 → 使用记录」主动开启，详见[使用记录](docs/logs.md)与[隐私说明](docs/privacy.md)。

更多说明见[文档目录](docs/README.md)；自行构建见[构建文档](docs/build.md)。

## Fork 与致谢

羽译基于 **KorenKrita** 的 [simul-subtitle](https://github.com/KorenKrita/simul-subtitle)，本 Fork 由 **ZOUYIQAQ** 维护，可与原版同时安装，数据与权限独立。

应用代码采用 [MIT 许可](LICENSE)，保留原作者版权声明。感谢 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[ReazonSpeech](https://github.com/reazon-research/ReazonSpeech)、[SenseVoice](https://github.com/FunAudioLLM/SenseVoice) 和 [Silero VAD](https://github.com/snakers4/silero-vad)；模型与依赖遵循各自许可，见[模型来源与许可](docs/MODEL_SOURCES.md)。
