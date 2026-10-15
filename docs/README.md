# 使用与技术文档

安装与快速上手见[中文首页](../README.md)或[英文首页](../README.en.md)。当前构建仅支持 Android 手机和平板；桌面客户端与专用工具已移除。

## 页面索引

| 页面 | 入口与用途 |
|---|---|
| 同传 | 语言、场景、当前服务、开始与停止；仅提示缺少的必要权限 |
| 字幕 | 显示限制、文字样式、尺寸与持续浮动预览 |
| 设置 → 模型库 | 成套下载 ASR 与 VAD，校验后手动启用，停止同传后切换 |
| 设置 → 场景与术语 | 编辑入口，编辑背景与术语，调用翻译 API 生成术语草稿 |
| 设置 | 分组首页汇总入口，进入翻译 API、字幕样式、外观、系统权限、高级设置、使用记录、关于 独立详情页和模型／场景管理 |

设置首页按「字幕与翻译」「个性化」「应用」分组列出条目，点入对应的独立详情页，返回由顶栏与系统返回统一处理。界面编排参考 FlClash 的 Material You 层级与卡片组织，保留本项目自身的 Compose 技术栈，使用官方 Compose Material3 1.4.0 稳定组件；容器描边与分割线按层级使用，同一层级保持一致。

| 同传 | 设置 → 模型库 | 翻译 API |
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

| 设置首页 | 字幕保留方式 | 关于页 |
|---|---|---|
| ![设置首页](images/screens/settings.png) | ![字幕保留方式](images/screens/sentence-range.png) | ![关于页](images/screens/about.png) |

新安装默认使用从图标原图提取的「山茶」暖色主题，橙棕、奶油、山茶红和鼠尾草绿协调搭配。同传主操作固定在导航栏上方，滚动时仍可点击；手机按钮至少72dp，平板限宽480dp并靠右。运行后原位切换为「停止同传」。已有手动配色会保留。

| 首次权限配置 | 翻译 API 配置 | 下载启用模型 |
|---|---|---|
| ![权限引导](images/screens/onboarding-permissions.png) | ![API引导](images/screens/onboarding-api.png) | ![模型引导](images/screens/onboarding-model.png) |

| 模型删除确认 | 可用地址确认 | 未保存退出确认 |
|---|---|---|
| ![模型删除](images/screens/model-delete.png) | ![地址确认](images/screens/api-endpoint.png) | ![退出确认](images/screens/api-unsaved.png) |

| 暖色冷启动 | 深色冷启动 |
|---|---|
| ![暖色启动](images/screens/startup-warm.png) | ![深色启动](images/screens/startup-dark.png) |

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

| 横屏预览（原大局部） | 图标选色 | 自定义主题色 |
|---|---|---|
| ![横屏字幕局部预览](images/screens/subtitle-landscape.png) | ![纯色图标选色](images/screens/icon-colors.png) | ![自定义主题色](images/screens/custom-color.png) |

| 按字数保留 | 场景编辑固定返回 |
|---|---|
| ![按字数保留字幕](images/screens/character-limit.png) | ![场景编辑左上返回](images/screens/scene-editor.png) |

界面截图为羽译 1.4.0 在 2026-10-15 本机 API 35 模拟器的真实截图，平板图按平板窗口验收，未冒充实体设备。悬浮字幕图也来自本轮真实系统内录与离线识别，译文为本地HTTPS固定夹具；设置中的示例单独标注。

外观支持任意RGB十六进制输入及HSV调色，确认后保存。桌面图标仅保留矢量彩色、纯色两种样式，背景与飘落装饰独立；默认彩色、插画背景和飘落花瓣，开关同时控制右下绿叶。纯色可选颜色并默认匹配主题。

字幕显示策略互斥选择按句数（默认）或按译文字数，不再叠加行数、区间下限或字数阈值。新安装默认仅译文、无耗时。可拖动悬浮预览随参数更新，支持横屏/竖屏画幅切换，离开页面后销毁。场景编辑有固定左上返回按钮。

羽译1.4.0使用独立包ID`io.github.zouyiqaq.yuyi`，不会覆盖旧hinome包或自动读取其数据。测试连接不自动保存，只有明确替换或保存才应用；未保存退出会确认。非仅定稿模式的实时草稿预留在字幕黑框外，启动页图标也使用独立透明背景的人物资源。

首次打开仅引导权限、翻译API和下载启用模型，每步可跳过。完成后不再自动显示，设置可重新打开。主导航为同传／字幕／设置；首页不展示会话文本，文件记录仅在主动开启保存后生成。
