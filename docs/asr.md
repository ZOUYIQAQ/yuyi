# 语音识别（ASR）

## 模型库可选识别模型

APK **不包含**任何识别模型与 VAD 模型：`sherpa-onnx` 运行时库由 `setup.sh` 放进 `app/libs/`，模型由你在安装后进入应用「模型库」手动选择下载。首次安装完成后私有目录里没有 ASR 与 VAD 文件，下载并校验通过之前无法开始字幕。

首批提供两种识别模型，逐文件字节数与 SHA-256 以 [MODEL_SOURCES.md](MODEL_SOURCES.md) 为准；模型库内嵌同一份清单，下载完成后逐文件校验再落盘：

| 模型 | 覆盖语言 | 结构 | 下载体积 | 许可 |
|---|---|---|---|---|
| ReazonSpeech k2 v2 ja-en | 日语、英语 | transducer（`encoder.int8.onnx` + `decoder.onnx` + `joiner.int8.onnx` + `tokens.txt`） | 77,745,395 字节（约 74.1 MiB），含 Silero VAD | 上游项目 Apache-2.0，ONNX 权重未附独立许可声明 |
| SenseVoice-Small 2024-07-17 int8 | 中文、英语、日语、韩语、粤语 | sense_voice 单模型（`model.int8.onnx` + `tokens.txt`） | 240,193,589 字节（约 229.1 MiB），含 Silero VAD | FunASR Model Open Source License Agreement v1.1，自定义模型协议 |

### 存储、加载与校验

| 项 | 实现 |
|---|---|
| 存储位置 | 应用私有内部目录 `files/models/<模型标识>/`（`ModelRepository.storageRoot`）：`asr/` 放识别文件，`vad/silero_vad.onnx` 放静音检测模型 |
| 打包方式 | ASR/VAD 权重不使用 `assets` 打包；许可文本作为 APK 的 `assets/licenses/` 随包，权重在卸载时随应用数据清除 |
| 校验后启用 | 每个文件都带固定的字节数与 SHA-256，先校验再落盘，成功后仍需用户手动启用，校验不通过不可启用；取消或失败不会留下可用的半成品 |
| 会话内固定 | 开始字幕后整个会话从同一个已校验目录读取模型，过程中不切换模型 |
| 两种模型各自成套 | 两套 ASR 各自成套包含 Silero VAD，不需单独选择下载；都使用 `asr/tokens.txt`；不能在同一目录混装两份词表，下载源优先级只用于切换到同一 SHA-256 的备用地址 |

### ReazonSpeech 关键实现细节

采用 `sherpa-onnx` 的 int8 量化导出版（[csukuangfj/reazonspeech-k2-v2-ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en)，原始模型由 [ReazonSpeech](https://github.com/reazon-research/ReazonSpeech) 发布），由单个模型同时覆盖**日语**和**英语**识别。

**首尾静音填充**：送入识别前，音频前后各补 **0.3 s 静音**——实测若有效语音从第 0 个采样点直接开始，该模型容易吞掉句首第一个词。

**双语混识与语言选择**：该模型本身没有“指定识别语言”的参数，也不输出语种标签。用户在「同传」页选择的原声语言（日语 / 英语 / 自动识别）不改变底层声学识别，而是用于告知后续的翻译提示词与 Jev 判断当前原文语种。

**已知局限**：在听日语节目时，偶有短片段被模型当作发音相近的英语单词输出。

### SenseVoice-Small 关键实现细节

**不做首尾静音填充**：与 ReazonSpeech 不同，送入识别的音频不额外补 0.3 s 静音。

**解码参数**：以 `language = "auto"` 解码并开启反向文本规整，模型可输出标点、大小写、阿拉伯数字与语种标签；Android 的 `SherpaAsr.recognize()` 会清除 `<|…|>` 标签后显示文字。原声下拉目前没有独立粤语项，粤语可使用自动识别。

---

## 与 SenseVoice-Small 的实测对比

### 测试基准

以下数据是作者在 Mac 上的自测结果，**不是 Android 手机或平板的实测数据**：安卓端的真实延迟、内存与识别效果仍需在设备上单独验收，不能直接沿用下表数值。

- **硬件与环境**：Mac M4 Pro，2 线程，int8 量化，`sherpa-onnx` 1.13.8。
- **解码方式**：整句离线解码，音频首尾各补 0.3 s 静音。
- **数据集**：FLEURS 朗读语音测试集（日语 650 句、英语 647 句；注：朗读语料与真实直播口语存在差异）。
- **评测口径**：日语计算字错误率 **CER**（NFKC 归一化，去除标点与空格）；英语计算词错误率 **WER**（转小写，去除标点）。

### 对比结果

| 指标 | ReazonSpeech k2 v2 ja-en（模型库可选） | SenseVoice-Small (2024-07-17，模型库可选) |
|---|---|---|
| **日语 CER** | **8.48%** | 7.52% |
| **英语 WER** | 22.66% | **8.33%** |
| **语种标签输出** | 无 | 有（ja 649/650，en 647/647） |
| **文本输出形态** | 日语无标点；英语全大写、无标点、数字拼写为单词 | 自带标点、大小写与阿拉伯数字 |
| **实时率（RTF，解码耗时 / 音频时长）** | **0.0075** | 0.0164（慢约 2.2 倍） |
| **单句解码中位数（日 / 英）** | **93 ms / 71 ms** | 203 ms / 157 ms |
| **模型文件体积**（仅 asr 文件；含 Silero VAD 的完整下载量见上一节） | **77 MB** | 239 MB |
| **加载后进程内存增量（macOS）** | **约 +214 MiB** | 约 +837 ~ 973 MiB |

### 历史评估的适用边界

1. **指标说明**：错误率基于上述自定义文本归一化脚本计算，不可与公开论文榜单直接横向对比；耗时仅统计 `decode_stream()` 在 Mac 上的表现。
2. **语言强制参数无效**：实测将 SenseVoice 的参数设为 `language="ja"` 时，夹杂的英语片段仍会被识别为英语，无法靠该参数规避跨语种误识。
3. **直播场景抽查**：在真实直播录音切片上对比，两款模型在短促口语片段上的输出分歧较大，因无标准参考文本，难以单凭朗读集断言谁在直播场景下绝对占优。
4. **许可不同**：ReazonSpeech 上游项目采用 Apache-2.0，ONNX 权重仓库未附独立许可声明，SenseVoice-Small 权重使用 FunASR 自定义模型协议，两者都不等同于标准的 MIT/Apache 代码许可；分发或再发布前请以 [MODEL_SOURCES.md](MODEL_SOURCES.md) 记录的许可原文为准。

**如何使用这份历史数据**：这组 Mac 评测不能推出 Android 上的延迟、内存、耗电或模型优劣结论。按原声语言选择可覆盖的模型，再在目标手机或平板和实际音源上评估。下载、校验后手动启用；同传运行期间不可切换，停止后可换已安装模型。

许可文本随 APK 存放于 `assets/licenses/ReazonSpeech-LICENSE.txt`、`assets/licenses/SenseVoice-MODEL_LICENSE.txt` 与 `assets/licenses/Silero-VAD-LICENSE.txt`，并非下载到模型目录的文件。来源和许可事实见 [MODEL_SOURCES.md](MODEL_SOURCES.md)。

---

## 后续可探索方向

- 针对纯日语观看场景，评估是否可切换或可选加载纯日语模型 [reazonspeech-k2-v2](https://huggingface.co/reazon-research/reazonspeech-k2-v2)，验证能否消除日语被误识为英语单词的情况（待实测）。
