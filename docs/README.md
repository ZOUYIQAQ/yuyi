# 使用与技术文档

安装与快速上手见[中文首页](../README.md)或[英文首页](../README.en.md)。当前构建为 Android 手机和平板应用；桌面客户端与专用工具已移除。

## 页面索引

| 页面 | 入口与用途 |
|---|---|
| 同传 | 语言、场景、权限、开始与停止、会话字幕 |
| 模型库 | 成套下载 ASR 与 VAD，校验后手动启用，停止同传后切换 |
| 场景术语 | 独立导航入口，编辑背景与术语，调用翻译 API 生成术语草稿 |
| 设置 | 翻译 API、字幕样式、外观、高级设置、使用记录、关于 |

| 同传 | 模型库 | 翻译 API |
|---|---|---|
| ![同传](images/screens/home.png) | ![模型库](images/screens/models.png) | ![翻译 API](images/screens/translate.png) |

| 实际悬浮字幕 | 字幕设置预览 | 外观 |
|---|---|---|
| ![实际悬浮字幕](images/screens/caption.png) | ![设置预览示例](images/screens/subtitle.png) | ![外观](images/screens/appearance.png) |

| 场景术语 | 深色界面 |
|---|---|
| ![场景术语](images/screens/scenes.png) | ![深色界面](images/screens/dark.png) |

| 平板同传 | 平板模型库 | 平板设置 |
|---|---|---|
| ![平板同传](images/screens/tablet-home.png) | ![平板模型库](images/screens/tablet-models.png) | ![平板设置](images/screens/tablet-settings.png) |

## 技术索引

| 文档 | 内容 |
|---|---|
| [整体架构](architecture.md) | 采集、VAD、分句、ASR、翻译、浮窗；控制器与 UI 边界；过载保护 |
| [语音识别](asr.md) | 两种模型的语言、下载与启用、许可位置和解码差异；历史评测的适用边界 |
| [翻译 API](translation.md) | 服务预设与官方来源、保存与测试、协议与关闭思考探测、上下文和场景术语 |
| [Jev](jev.md) | 可选在线断句与译文检查、失败降级、第三方服务边界 |
| [隐私](privacy.md) | 本地数据、网络请求、权限和密钥存储 |
| [使用记录](logs.md) | 分享、确认删除与清空、活动记录保护、JSONL 与 logcat 指标 |
| [构建](build.md) | Android 环境、运行时准备、测试、打包、签名与当前更新行为 |
| [模型来源核查](MODEL_SOURCES.md) | 固定来源、字节数、SHA-256 与许可证据 |
| [Android 验收](ANDROID_QA.md) | 设备验收记录与待核对项目 |
| [实施计划](IMPLEMENTATION_PLAN.md) | 改造计划与实施进度 |

截图文件与设备验收记录分别维护；截图展示不等于所有设备、音源或付费翻译服务已通过验收。

截图来自2026-10-15本地API35模拟器，平板图按平板窗口验收，未冒充实体设备。悬浮字幕实测使用真实系统内录与离线识别，译文为本地HTTPS固定夹具；设置中的示例单独标注。
