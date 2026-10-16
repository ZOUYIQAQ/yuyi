# 羽译 · Yuyi

[简体中文](README.md) · [Documentation (Chinese)](docs/README.md) · [Releases](https://github.com/ZOUYIQAQ/yuyi/releases)

**Android phones and tablets only.** This fork has removed other platform clients and their dedicated tooling. It does not provide Windows, macOS, or other platform builds.

An Android fork of [KorenKrita/simul-subtitle](https://github.com/KorenKrita/simul-subtitle), originally authored by **KorenKrita** and maintained in this fork by **ZOUYIQAQ**. This revision targets **1.6.2 (versionCode 30)**, adds optional persistent background space for interim captions, and keeps the named API profiles, HTTP local/LAN endpoints and incremental draft translation from upstream 1.0.2, with improved narrow-window/large-text appearance controls and saved auxiliary-key editing; see [Architecture](docs/architecture.md) for attribution. The upstream MIT copyright and license are preserved. Package `io.github.zouyiqaq.yuyi` can coexist with the original `io.github.korenkrita.simulsub`, with separate data and permissions.

The default icon uses the supplied vector contours with the illustrated background and falling decorations. Appearance offers color and monochrome styles, backgrounds and a falling-decorations switch controlling both the petal and bottom-right leaf. Monochrome offers ten preset colors, with automatic nearest-theme matching or a fixed manual choice.

Android floating subtitles for audio playing on your device. Speech recognition and voice activity detection run locally; your own translation API receives text. The current build supports Android phones and tablets. Desktop clients and their dedicated tools have been removed.

In **字幕 (Subtitles)**, **预留临时字幕空间 (Reserve interim-caption space)** keeps one line of background after interim text disappears. It is off by default and can only be changed when **仅显示定稿 (Final captions only)** is off. The choice is remembered while disabled. This does not move finalized captions or consume their sentence limit.

## Get started

Requires **Android 10 (API 29)+ and arm64-v8a**.

First launch guides permissions, the translation API, and model download/activation in that order. Each step can be skipped, and the guide can be reopened from Settings. Cold starts read actual system permission grants; missing required permissions appear in an emphasized card at the top of Live.

1. Open **设置 → 模型库 (Settings → Model library)** and download a recognition package. Each package includes its own Silero VAD; no separate selection is needed. After verification, tap **启用模型 (Activate model)** manually.
2. Open **设置 → 翻译 API (Settings → Translation API)**. Select or add a named profile, then choose a preset or enter your OpenAI-compatible endpoint, model ID and API key, and save. Saving keeps the inputs and displays **已保存 (Saved)**. **测试连接 (Test connection)** tests the draft without saving it. Requests may incur charges; apply a successful result with **替换并保存 (Replace and save)**.
3. On **同传 (Live)**, select source/target languages and a scene, grant the requested permissions and system capture authorization, then start subtitles.

Capture depends on the source app, DRM, Android version and device implementation. Compatibility with every player or video is not guaranteed. Capture is not isolated by process: other eligible apps playing audio at the same time may also be transcribed.

## Screens

| Live | Model library | Subtitle style preview |
|---|---|---|
| ![Live](docs/images/screens/home.png) | ![Models](docs/images/screens/models.png) | ![Subtitle style preview](docs/images/screens/subtitle.png) |

<details>
<summary>Tablet live</summary>

![Tablet live](docs/images/screens/tablet-home.png)

</details>

Only four representative screenshots are displayed here; screenshot files and historical QA records are retained.

These four screenshots show Yuyi 1.6.1 running on the local API 35 emulator on 2026-10-16, including tablet-sized windows. The subtitle screenshot shows the settings preview.

Layout references [FlClash](https://github.com/chen08209/FlClash) for Material You hierarchy and card grouping while keeping this project's own Compose stack, built with official stable Compose Material 3 1.4.0 components. Container outlines and dividers follow the hierarchy instead of being removed everywhere; items at the same level are styled alike.

Compact windows use bottom navigation; wider windows use a navigation rail and adapt page columns. The settings home groups entries and each row opens a detail page (translation API, subtitle style, appearance, permissions, advanced, usage records, about), model management, or scene management; the top bar and system back handle returning. The settings preview is a sample, rather than an actual floating overlay.

Each main route, Settings home and detail page, model library, and scene list remembers its own list position. Phone single- and two-column layouts keep separate positions. Form API keys are excluded from SavedState.

## Recognition and translation

| Recognition package | Source languages | Download including VAD |
|---|---|---|
| ReazonSpeech | Japanese, English | 77,745,395 bytes (about 74.1 MiB) |
| SenseVoice Small | Chinese, English, Japanese, Korean, Cantonese | 240,193,589 bytes (about 229.1 MiB) |

The APK contains no ASR or VAD weights. Packages are downloaded to private app storage and checked against fixed sizes and SHA-256 hashes. Activation is manual; stop a running session before switching models. License texts ship in the APK under `assets/licenses/`, rather than being downloaded alongside model files. See [ASR](docs/asr.md) and [model sources](docs/MODEL_SOURCES.md).

Translation presets include DeepSeek official (`https://api.deepseek.com/v1`, `deepseek-flash`), SiliconFlow (`https://api.siliconflow.cn/v1`, `deepseek-ai/DeepSeek-V4-Flash`) and OpenRouter (`https://openrouter.ai/api/v1`, model ID must be entered). Presets only fill an editable draft: they neither save nor send requests. Connection tests never save the draft automatically: **Replace and save** explicitly applies a successful full endpoint; leaving with unsaved edits asks for confirmation. Switching presets across endpoints clears the draft's old key, custom headers and User-Agent. Custom mode preserves the form. Defaults were checked against official documentation on **2026-10-15** and may change; this is not verification of a user's paid API access. Sources and protocol details: [Translation](docs/translation.md).

The API page manages up to 20 named profiles, each with its own endpoint, model, key, User-Agent and headers. Existing settings remain in the Default profile. Add, rename, switch or delete with confirmation; at least one profile remains, and unsaved edits require confirmation before being discarded. During a session, an empty profile keeps the existing service; a complete profile takes effect for subsequent sentences. HTTP and HTTPS endpoints are editable, with a cleartext warning for HTTP local/LAN services on trusted networks. Release HTTPS trusts system CAs only, and translation requests do not follow redirects. The first draft is translated immediately; later drafts need at least five Unicode code points of accumulated change from the last queued draft. Finals bypass this threshold.

New installations start with **Camellia (山茶)**, sampled from the icon: orange-brown, cream, camellia red and sage green. On Live, the primary action stays centered at the bottom of the initial viewport above navigation while content scrolls, at least 72dp tall and no wider than 480dp. The same position becomes Stop while running. The subtitle settings preview defaults to the bottom-left at available widths of 600dp or more and bottom-right on narrower layouts; a dragged position is retained. The 1.5.1 fixes for transparent layout tails, visible system bar insets and stable subtitle baselines remain.

## Settings and controls

- **场景术语 (Scenes and terms)** is under Settings for background descriptions and terminology. AI term generation uses your configured API; review and save the resulting draft.
- **设置 → 字幕样式 (Subtitle style)** controls colors, size, opacity, width, height and one retention policy: sentence count (latest three final sentences by default, configurable from 1–10, plus one latest live draft), or translated-character count (10–300 Unicode code points, retaining the newest tail of an overlong sentence). No additional final-subtitle line limit or combined sentence/character thresholds apply. New installations show translation only, without timings; source text and timings remain optional. A draggable floating preview stays visible while scrolling and offers portrait/landscape preview frames. Finals and drafts share one continuous rounded background. The draft follows finals with a 2dp gap and always occupies at most one line; enabling source text combines source and translation on that same line. The window reserves a fixed draft budget, so showing or hiding the draft leaves the last final line's baseline unchanged. Controls use a separate overlay window and do not move the main subtitles when shown or hidden. Physical window height still bounds visible content. These settings do not modify source finals, logs or translation context.
- **设置 → 外观 (Appearance)** offers Ocean, Forest, Iris, Camellia, Rose and Slate palettes, arbitrary RGB hex input and HSV sliders, plus Android 12+ dynamic colors. Older systems use the selected palette. Light/dark/system mode is independent of palette selection; overlay colors are separate.
- **设置 → 高级设置 (Advanced)** contains translation context, join deduplication and optional Jev sentence/translation checks. Jev is off by default and sends text to a third-party service when enabled.
- **设置 → 使用记录 (Usage records)** is off by default. Finished text logs can be shared, individually deleted after confirmation or cleared after confirmation. Active logs cannot be shared or deleted.

Tap the floating subtitles to reveal controls, drag to move, or resize from the corners while controls are visible. Lock and stop controls are available; reset position under Subtitle style.

## Updates and development

**Settings → App updates** checks for updates at startup by default, with an option to disable automatic checks and a manual check action. It supports reminding later, skipping a specific version, and restoring reminders. The update button will open this fork’s GitHub APK download link in the browser, or the [release page](https://github.com/ZOUYIQAQ/yuyi/releases) if no APK is available. The app will not download or install updates internally. Available releases are those actually published on GitHub.

See [Build](docs/build.md) for `:core` and `:app`. macOS/Windows builds are no longer distributed. More: [Architecture](docs/architecture.md), [Privacy](docs/privacy.md), [Logs](docs/logs.md), [Jev](docs/jev.md).

Application code: [MIT](LICENSE). Thanks to [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [ReazonSpeech](https://github.com/reazon-research/ReazonSpeech), [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) and [Silero VAD](https://github.com/snakers4/silero-vad). Models and dependencies retain their own licenses; Jev is an optional cloud service.

The three main destinations are Live, Subtitles, and Settings. Model and scene management are inside Settings, with shortcuts on Live. First launch guides only permissions, the translation API, and model download/activation; every step can be skipped and the guide can be reopened from Settings. The home screen hides granted permissions and no longer displays in-memory session text. Session files are created only after recording is explicitly enabled.
