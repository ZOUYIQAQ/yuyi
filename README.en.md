# SimulSub · 同传字幕

[简体中文](README.md) · [Documentation (Chinese)](docs/README.md) · [Releases](https://github.com/ZOUYIQAQ/simul-subtitle/releases)

Android floating subtitles for audio playing on your device. Speech recognition and voice activity detection run locally; your own translation API receives text. The current build supports Android phones and tablets. Desktop clients and their dedicated tools have been removed.

## Get started

Requires **Android 10 (API 29)+ and arm64-v8a**.

1. Open **模型库 (Model library)** and download a recognition package. Each package includes its own Silero VAD; no separate selection is needed. After verification, tap **启用模型 (Activate model)** manually.
2. Open **设置 → 翻译 API (Settings → Translation API)**. Select a preset or enter your OpenAI-compatible endpoint, model ID and API key, then save. **测试连接 (Test connection)** saves the form first and sends requests that may incur charges.
3. On **同传 (Live)**, select source/target languages and a scene, grant the requested permissions and system capture authorization, then start subtitles.

Capture depends on the source app, DRM, Android version and device implementation. Compatibility with every player or video is not guaranteed. Capture is not isolated by process: other eligible apps playing audio at the same time may also be transcribed.

## Screens

| Live | Model library | Translation API |
|---|---|---|
| ![Live](docs/images/screens/home.png) | ![Models](docs/images/screens/models.png) | ![Translation API](docs/images/screens/translate.png) |

| Actual floating overlay | Style preview in settings | Appearance |
|---|---|---|
| ![Actual overlay](docs/images/screens/caption.png) | ![Settings preview](docs/images/screens/subtitle.png) | ![Appearance](docs/images/screens/appearance.png) |

| Scenes and terms | Dark theme |
|---|---|
| ![Scenes](docs/images/screens/scenes.png) | ![Dark theme](docs/images/screens/dark.png) |

| Tablet live | Tablet models | Tablet settings |
|---|---|---|
| ![Tablet live](docs/images/screens/tablet-home.png) | ![Tablet models](docs/images/screens/tablet-models.png) | ![Tablet settings](docs/images/screens/tablet-settings.png) |

Screenshots were captured on the local API 35 emulator on 2026-10-15, including tablet-sized windows. The actual overlay uses real audio capture and offline recognition with a fixed local translation fixture. Layout references [FlClash](https://github.com/chen08209/FlClash), implemented using native Compose Material 3.

Compact windows use bottom navigation; wider windows use a navigation rail and adapt page columns. The settings preview is a sample; the actual overlay is shown separately above.

## Recognition and translation

| Recognition package | Source languages | Download including VAD |
|---|---|---|
| ReazonSpeech | Japanese, English | 77,745,395 bytes (about 74.1 MiB) |
| SenseVoice Small | Chinese, English, Japanese, Korean, Cantonese | 240,193,589 bytes (about 229.1 MiB) |

The APK contains no ASR or VAD weights. Packages are downloaded to private app storage and checked against fixed sizes and SHA-256 hashes. Activation is manual; stop a running session before switching models. License texts ship in the APK under `assets/licenses/`, rather than being downloaded alongside model files. See [ASR](docs/asr.md) and [model sources](docs/MODEL_SOURCES.md).

Translation presets include DeepSeek official (`https://api.deepseek.com/v1`, `deepseek-flash`), SiliconFlow (`https://api.siliconflow.cn/v1`, `deepseek-ai/DeepSeek-V4-Flash`) and OpenRouter (`https://openrouter.ai/api/v1`, model ID must be entered). Presets only fill an editable draft: they neither save nor send requests. Switching presets across endpoints clears the draft's old key, custom headers and User-Agent. Custom mode preserves the form. Defaults were checked against official documentation on **2026-10-15** and may change; this is not verification of a user's paid API access. Sources and protocol details: [Translation](docs/translation.md).

## Settings and controls

- **场景术语 (Scenes and terms)** is a separate navigation destination for background descriptions and terminology. AI term generation uses your configured API; review and save the resulting draft.
- **设置 → 字幕样式 (Subtitle style)** controls colors, text size, opacity, source text, timings, width, height and retained sentences. **最多显示行数 (Maximum displayed lines)** accepts 0 (unlimited) or 1–20, counting wrapped source and translated lines together and keeping the newest lines. Timing/status text is excluded. The 40% screen-height cap and fixed height still apply, with the stricter limit winning. This affects display only, not session history or prompt context.
- **设置 → 外观 (Appearance)** offers Ocean, Forest, Iris, Warm Sand, Rose and Slate palettes, plus Android 12+ dynamic colors. Older systems use the selected palette. Light/dark/system mode is independent of palette selection; overlay colors are separate.
- **设置 → 高级设置 (Advanced)** contains translation context, join deduplication and optional Jev sentence/translation checks. Jev is off by default and sends text to a third-party service when enabled.
- **设置 → 使用记录 (Usage records)** is off by default. Finished text logs can be shared, individually deleted after confirmation or cleared after confirmation. Active logs cannot be shared or deleted.

Tap the floating subtitles to reveal controls, drag to move, or resize from the corners while controls are visible. Lock and stop controls are available; reset position under Subtitle style.

## Updates and development

**设置 → 关于 → 检查更新 (About → Check updates)** reads only this fork's [latest release endpoint](https://api.github.com/repos/ZOUYIQAQ/simul-subtitle/releases/latest) and displays its tag. **打开项目仓库 (Open repository)** opens the project page for manual review/download. There is no automatic checking, in-app APK download/install, update proxy fallback or skipped-version preference in the current flow.

See [Build](docs/build.md) for `:core` and `:app`. macOS/Windows builds are no longer distributed. More: [Architecture](docs/architecture.md), [Privacy](docs/privacy.md), [Logs](docs/logs.md), [Jev](docs/jev.md).

Application code: [MIT](LICENSE). Thanks to [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [ReazonSpeech](https://github.com/reazon-research/ReazonSpeech), [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) and [Silero VAD](https://github.com/snakers4/silero-vad). Models and dependencies retain their own licenses; Jev is an optional cloud service.
