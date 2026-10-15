# Android 改造验收记录

本记录对应工作分支 `codex/android-material-models`。所有设备命令显式指定本地 `emulator-5580`，没有操作已连接的远程设备。

## 火羽同传1.2.1身份改造（2026-10-15）

- 用户已批准本次本地提交，暂不推送或发布。应用名「火羽同传」，应用ID `io.github.zouyiqaq.hinome`，versionCode22；内部Kotlin命名空间保留共享核心兼容，不影响系统共存。
- 本机API35模拟器已同时安装新包1.2.1与旧包名1.2.0，分别使用`/data/user/0/io.github.zouyiqaq.hinome`与`/data/user/0/io.github.korenkrita.simulsub`。新应用的启动入口、logs/updates两个FileProvider归属及图标加载通过真实设备检查。未将旧包二进制冒称为未修改的上游原版。
- 新应用下33项设备回归通过，`.qa/branding/device-tests.log`；最终资源下2项身份与图标检查再次通过，`.qa/branding/final-identity-tests-debug.log`。设备测试使用debug变体，Release另行安装与启动检查。
- core198/app33单元测试、Debug/AndroidTest/Release及Lint构建通过，见`.qa/branding/build.log`与`final-build.log`；包内没有ONNX/TFLite权重、测试音频或调试CA，签名检查通过。
- 新APK `.artifacts/hinome-subtitle-1.2.1-arm64.apk`，14,145,206字节，SHA-256 `9c8f5e9fbcf9d225148225e3e788a1911729159983b7889cd39b4893867fb56a`，使用本机验收签名。
- 图标仅为暂用矢量草稿，用户尚未认可并已暂停图标设计；本次保留源文件和当前资源，不能视作最终视觉验收通过。
- 实际运行与测试位于本机Android SDK创建的`simul_phone`（API35/ARM64），ADB明确指定`emulator-5580`。此前采用无窗口模式；为便于用户操作，改为可见窗口，保留应用与数据。旧版截图记录仍对应各自版本。

## 本轮1.2.0验收状态（2026-10-15，完成）

本轮API预设、真实字幕行数上限、主题色、页面美化、文档截图与桌面物理删除已完成。用户已批准纳入本次本地提交，暂不推送或发布。

- `:core:test`198项、`:app:testDebugUnitTest`33项通过；`.qa/refinement-validation-final.log`。
- 设备回归31项全部通过：原生限行11项、模型对齐3项、预设/偏好/配色5项、新UI交互2项、自适应2项、控制器5项、记录3项；`.qa/refinement-device-final.log`，19.522秒。
- 补充修复低矮横屏侧栏标题占位后，自适应2项复验通过，`.qa/refinement-final-adaptive.log`；最终Debug/AndroidTest/Release及Lint构建成功，`.qa/refinement-final-build.log`。Lint 0 error、138 warning，未压低检查规则。
- 真实内录→VAD→离线ASR→本地HTTPS流式翻译夹具→悬浮字幕定稿通过；`.qa/refinement-capture-final.log`，43.763秒，OK(1)。截图前等待主线程和绘制稳定，最多3行保留最新实际排版行。该夹具固定回复，不代表云翻译准确率。
- 修复同域不同路径接口沿用凭据：完整规范化聊天端点变化时清空密钥、请求头与UA；基础地址与完整chat地址等价时保留。编辑地址取消旧探测，不自动保存或发请求。设备两项新增严格回归通过。
- 新Release：`.artifacts/simul-subtitle-android-1.2.0-arm64.apk`，versionCode21，14,144,838字节，SHA-256 `27627e75a7715c3d3d69c19aa71288abce80b6b75858fea7f12d247ae734c93c`。R8与资源压缩开启，APK签名验证通过，安装、启动和导航通过。使用本机验收签名，尚未发布正式发行版。
- 包内容仅arm64-v8a，无ONNX/TFLite权重、测试WAV、调试CA；`.qa/refinement-apk-verification.json`与`.qa/refinement-signature.txt`。
- 新版11张展示图在`docs/images/screens/`，清单及尺寸校验见`.qa/refinement/screenshot-manifest.json`；README中英文与技术文档本地链接全部有效。实际浮窗和设置预览分别标注。
- 本机API35模拟器：手机1080×2400/420dpi、平板2560×1600及1600×2560/240dpi、横屏2400×1080/420dpi、700dp窗口1050×1800/240dpi；浅深色、动态色、200%字号均实际检查。低矮横屏移除侧栏标题占位，四个导航入口可达；宽屏模型各段对齐，大字号和窄屏保持可滚动。
- 这是模拟器窗口适配验收；未覆盖实体平板、旧Android实体机或所有音源App。Android12以下的手动配色回退按代码分支实现，未在API29实体机实测。本轮未重新下载全部模型，上轮完整校验与直连证据仍见`MODEL_SOURCES.md`。
- 子代理已关闭，本地HTTPS验收服务已停止；模拟器恢复手机100%字号供人工检查。未操作远程ADB，未创建定时任务。

### 本轮视觉参考与验收意图

视觉参考为[FlClash](https://github.com/chen08209/FlClash)，借鉴Material You层级、中性色调、紧凑卡片和自适应导航，继续采用原生Compose Material3。模型属性使用标签/值双列，刷新并入标题操作，完整详情仍可展开；主题选项采用无描边色调容器，提供六种手动主题及Android12+动态取色。手机原声/字幕语言常规字号并排，大字号回退单列，主操作使用56dp圆角按钮。实现与实际视觉验收已完成，证据和设备覆盖边界见上节。

真实行数上限按原文与译文实际排版合计，支持0不限及1～20行，诊断行另计并保留最新行；需覆盖换行、字号、宽度和固定高度，与历史句数/字数限制区分。API预设覆盖DeepSeek、硅基流动、OpenRouter等，选择后可编辑地址与模型，保留全手填；选择仅更新草稿，不自动保存或请求，跨完整端点切换清空旧凭据。对应交互、持久化和状态回归已通过。

### 本轮桌面物理清理（已获授权并完成）

用户已明确授权，23个桌面专属文件已物理移除，完整清单见`.qa/android-only-delete-manifest.json`。范围包括desktop源码、mac/win专属工具、旧桌面截图与Windows CI；保留Android依赖的core及在Windows开发机编译Android所需的gradlew.bat。该事实更新下文旧版“桌面代码保留”的历史状态，不代表本轮Android回归已完成。

## 旧1.1.0验收记录（历史证据）

以下章节的通过结论、测试计数、APK与截图均属于旧1.1.0检查点，不覆盖本轮新增实现，也不构成本轮最终通过结论。保留原有日志、限制与复现步骤供追溯。

### 环境与构建

- 本机：Apple Silicon，Homebrew OpenJDK 17。
- Android SDK 35、Build Tools 35、ARM64 Google APIs API35 模拟器。
- Gradle 8.11.1、AGP 8.9.2；使用 `source tools/android-env.sh` 后运行 Gradle。
- 产品最低 Android 10，只包含 arm64-v8a；未验证其他 Android 版本的实体设备。
- 旧1.1.0 Release 开启 R8 与资源压缩，当时交付使用本机调试证书签名供安装验收。正式发行前应另行管理发行签名。
- 旧1.1.0验收时，原桌面代码保留为历史，不进入Gradle或发行流水线；当时未物理删除。本轮经明确授权已物理删除23个桌面专属文件，详见前文本轮清理记录。

### 旧1.1.0已确认结果

- `:core:test`：198项通过；`:app:testDebugUnitTest`：29项通过。
- `:app:lintDebug`、`:app:assembleRelease` 通过。Lint仍有136条旧资源、依赖版本提醒及应用级Context等warning；未禁用Lint或降低检查规则。
- 模拟器 Compose 与控制器回归：10项通过，包括首次模型引导、下载动作、Jev草稿隔离、连接测试取消、记录删除与确认交互。
- 两种模型均在设备侧真实下载、校验、显式启用，并完成日语测试音频解码及VAD检测。下载完成不自动启用。
- APK内的assets仅有许可和基线优化资料，无ONNX/TFLite、模型目录或测试WAV；Release不含本地测试CA及调试网络配置。

### 真实模型冒烟

测试音频来自固定版本上游样例，出处见 `app/src/androidTest/assets/SOURCES.txt`。该音频只进入测试APK。

| 模型 | 完整大小（含各自VAD） | 单条模拟器解码结果 |
| --- | ---: | --- |
| ReazonSpeech | 77,745,395字节 | うちの中学は弁当制で持っていけない場合は五十円の学校販売のパンを買う |
| SenseVoice Small | 240,193,589字节 | うちの中学は弁当制で持っていきない場合は50円の学校販売のパンを買う。 |

单例89ms/126ms仅证明原生运行时实际执行，不能用于手机性能或准确率排名。

### 视觉与人体工学范围

使用同一个API35模拟器调整窗口大小与密度，检查手机约411dp、横屏手机、平板横竖屏、600～839dp中等窗口、深浅色及200%字号。此方法验证窗口适配，不能代替实体折叠屏、平板及厂商分屏系统测试。

窄窗口底部导航，600dp起侧栏，840dp起双栏/模型两列。大字号侧栏加宽；导航采用短标签避免四字名称挤压。主要动作至少48dp，开始/停止至少56dp；表单和长文可滚动，按钮组可换行。未把后台模型完整性校验或业务状态伪造为UI演示。

人工检查包含：首次引导、同传、模型库、场景列表与编辑、翻译配置、字幕预览、外观、使用记录。该轮截图以下方旧1.1.0最终截图清单为准；修复前截图仅作为历史证据保留，不作为本轮新UI展示。

### 完整字幕链路

`CapturePipelineDeviceTest` 使用真实系统 MediaProjection 和 AudioPlaybackCapture，AudioTrack播放日语样例，执行真实VAD/ASR，经本机HTTPS流式响应，最终检查字幕定稿和悬浮窗。

翻译服务是固定回复的测试夹具，不代表云翻译准确率。原文通过本地链路，不使用用户付费密钥。

复现步骤：

1. `./tools/prepare-qa-translation.sh` 生成30天本地证书，私钥只保存在被忽略的 `.qa/tls/`。
2. 从仓库根目录使用Python虚拟环境运行 `tools/qa-translation-server.py`。
3. 重新构建并安装debug和androidTest APK。只有debug信任 `10.0.2.2` 的该CA，其他域名及Release使用系统信任。
4. 运行 `CapturePipelineDeviceTest`，显式传入 `-e runCapture true`；在专用模拟器确认捕获整个屏幕。
5. 测试会还原原翻译/辅助服务/记录设置，并等待服务与原生会话停止。

本地夹具最初未明确发送连接关闭头，出现交替请求失败；保留 `.qa/capture-session.jsonl` 后定位为夹具HTTP连接行为，改为HTTP/1.1和明确 `Connection: close`。产品未增加可能重复计费的重试。

### 下载源与已知限制

详细URL、固定修订、SHA-256及许可见 `MODEL_SOURCES.md`。ModelScope优先，HF-mirror及官方源备用；Reazon decoder不能混用ModelScope中另一导出版。

7个独特文件、20个来源已完整下载并校验。首轮本机部分流量经过TUN；最终显式绑定en1和真实IP，完整直连下载了两套模型全部文件并重新核对SHA-256。SenseVoice大文件22.131秒，Reazon encoder7.360秒，decoder7.780秒；这些结果仅适用于当前本机网络，不能保证每个国内运营商一致。失败时保留原模型，未通过校验的文件不可启用。

恢复为文件级：复用已完整下载且通过校验的文件，未完成单文件会重新下载；没有声称字节级HTTP Range续传。系统强行停止应用后需用户重新打开应用，WorkManager才会重新调度。

### 审阅与交付边界

独立Sol/high审阅发现3项缺陷，均已修复并设备回归：服务商密钥草稿串用、开始/停止未取消连接测试、记录删除入口遗漏。

未执行Git提交、推送或发布Release；当前Fork云端还没有这些本地改动。检查更新指向该Fork发行页，当前没有发行版时会如实提示。

最终APK、截图、逐项日志与SHA-256保存在本机 `.artifacts/`、`.qa/`，这些临时交付产物均被Git忽略。

### 旧1.1.0最终设备结果与截图

- 完整字幕定稿测试：`.qa/capture-device-test.log`，OK（1项），29.347秒；退出前等待服务及原生会话释放。
- 下载生命周期：cancel、start、complete、selected四阶段均OK（1项/阶段）。使用全新`.qa`隔离包，未清除原应用；GSM限速时开始并取消，随后开始新任务，force-stop并重新打开后原任务自动完成，手动启用后再次重启保留选择。
- Release安装、启动及界面切换通过；实际包标记不含DEBUGGABLE。Release里实际点击切换到Reazon成功。原生ASR解码及完整捕获链路在debug设备测试中验证；未把它们冒称为实体手机或Release全链路测评。
- 最终Release截图：`release-phone-live-light.png`、`release-phone-models-dark.png`、`release-phone-large-scenes.png`、`release-phone-landscape.png`、`release-medium-settings.png`、`release-tablet-models-light.png`、`release-tablet-scenes-light.png`、`release-tablet-subtitle-light.png`、`release-tablet-large-light.png`，均在`.qa/screenshots/`。
- 200%字号下手机导航改短标签，平板侧栏加宽，未见标题遮挡、按钮重叠。横屏/中等窗口下内容可纵向滚动。未进行实体读屏、实体平板和多个厂商系统长时间测试。

旧1.1.0 APK：`.artifacts/simul-subtitle-android-1.1.0-arm64.apk`，14,080,270字节（约14.1MB/13.4MiB）。SHA-256：`8b99378459d3c1bdeb9a8f347a1641c99581ddcb38a397d52ebdadc83815e88e`。
