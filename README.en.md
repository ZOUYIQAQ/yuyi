# 羽译 · Yuyi

Yuyi provides **floating translated subtitles for live streams and videos on Android**. It recognizes playback audio locally on your device, sends the recognized text to your own translation API, and displays subtitles over other apps.

[Download test build](https://github.com/ZOUYIQAQ/yuyi/actions/workflows/android.yml) · [Quick start](#quick-start) · [Documentation (Chinese)](docs/README.md) · [简体中文](README.md)

**Android only**: supports **Android 10+ phones and tablets with arm64-v8a**. Recognition models are downloaded and activated after installation; they are not bundled in the APK.

## Download

There is currently no GitHub Release available. Open this fork's [Actions workflow](https://github.com/ZOUYIQAQ/yuyi/actions/workflows/android.yml), select the most recent successful run, and download **安卓安装包** (Android installation package) under **Artifacts**. Extract it and install `app-debug.apk`. You must sign in to GitHub to download artifacts.

Future releases will be listed on this fork's [Releases page](https://github.com/ZOUYIQAQ/yuyi/releases).

## Screens

| Live | Model library | Subtitle style preview |
|---|---|---|
| ![Live](docs/images/screens/home.png) | ![Model library](docs/images/screens/models.png) | ![Subtitle style preview](docs/images/screens/subtitle.png) |

<details>
<summary>Tablet live view</summary>

![Tablet live](docs/images/screens/tablet-home.png)

</details>

## Core experience

- **Read subtitles while watching**: show translations over a live stream or video, with optional source text. Tap subtitles to reveal controls, then move, resize or lock the overlay.
- **Local recognition, your choice of translation service**: choose from two recognition models and configure an OpenAI-compatible translation API. Save multiple profiles and switch as needed.
- **Adjust subtitles to suit your viewing**: translations only by default, keeping the latest 3 finalized sentences. Change font size, colors, opacity and subtitle window size, compare color schemes directly in the menu, and fine-tune them with a draggable floating preview.
- **Phone and tablet layouts**: a warm Camellia theme by default, with light/dark modes, custom colors and Android 12+ dynamic colors.

Add stream background and preferred translations under **设置 → 场景与术语 (Settings → Scenes and terms)**. Usage recording is off by default and can be enabled when needed.

## Quick start

First launch guides you through permissions, the translation API and recognition models. You can also complete these steps later in Settings.

1. **Grant permissions**: allow audio recording and display over other apps as prompted. Confirm the system screen-sharing/audio-capture authorization each time you start a session.
2. **Configure translation**: open **设置 → 翻译 API (Settings → Translation API)**. Choose a preset or enter a compatible endpoint, model ID and API key, test the connection, then save. Testing alone does not save the configuration.
3. **Download and activate a model**: open **设置 → 模型库 (Settings → Model library)**, download a model, then tap **启用模型 (Activate model)**.
4. **Start listening and translating**: on **同传 (Live)**, select source and target languages and a scene, tap **开始同传 (Start)**, then switch to the playing stream or video.

### Which recognition model?

| Model | Source languages | Download size | Features |
|---|---|---|---|
| ReazonSpeech | Japanese, English | About 74.1 MiB | Smaller download for viewers mainly watching Japanese or English content who want to save storage |
| SenseVoice Small | Chinese, English, Japanese, Korean, Cantonese | About 229.1 MiB | Broader language coverage, with punctuation and normalized numbers in recognition results |

Both packages include the required voice activity detection model. Stop the session before switching recognition models.

## FAQ and links

**Is translation fully offline?** Speech recognition runs locally, while translation requires your configured text API. Online services require a network connection and receive recognized text; raw audio is not uploaded. You can also connect to a compatible translation endpoint hosted on your device or local network. See [Translation configuration](docs/translation.md).

**Why do some videos produce no subtitles?** The source app, protected content and system playback-capture restrictions can prevent capture. Other apps playing capturable audio at the same time may also be recognized; pause unrelated audio during use.

**Does it save viewing records?** Session recording is off by default, and raw audio is not saved. Enable text records explicitly under **设置 → 使用记录 (Settings → Usage records)**. See [Usage records](docs/logs.md) and [Privacy](docs/privacy.md).

See the [documentation index](docs/README.md) for more information, or the [build guide](docs/build.md) to build the app yourself. These documents are in Chinese.

## Fork and acknowledgements

Yuyi is based on [simul-subtitle](https://github.com/KorenKrita/simul-subtitle) by **KorenKrita**. This fork is maintained by **ZOUYIQAQ** and can be installed alongside the original, with separate data and permissions.

Application code is licensed under [MIT](LICENSE), retaining the original author's copyright notice. Thanks to [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [ReazonSpeech](https://github.com/reazon-research/ReazonSpeech), [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) and [Silero VAD](https://github.com/snakers4/silero-vad). Models and dependencies retain their own licenses; see [Model sources and licenses](docs/MODEL_SOURCES.md).
