# 安卓同传字幕改造：目标与持续进度

## 不可丢失的目标

1. GitHub Fork：ZOUYIQAQ/simul-subtitle，上游 KorenKrita/simul-subtitle。
2. 仅保留 Android 产品与构建入口；不再开发或分发桌面端。
3. Kotlin + Jetpack Compose Material 3 完整重做主界面，统一导航、组件、字体、色彩、状态反馈。原生采集、前台服务、字幕状态机优先复用。
4. 必须适配手机和平板：按窗口宽度切换底部导航/侧边导航，宽屏合理双栏，验证横屏、分屏及大字号。
5. APK 不内置识别模型和 VAD 模型。安装后在模型库手动选择下载；至少 ReazonSpeech 与 SenseVoice 两种可用识别模型，下载、进度、取消、失败重试、严格校验、启用与切换形成完整闭环。
6. 下载源覆盖国内网络。核查真实文件、大小、SHA-256、许可，优先国内可访问源，并有受控备用源；不能仅凭链接存在宣称国内可用。
7. 安装本地开发环境，交付可安装 APK。必须实际运行视觉验收，包含手机、平板、横屏、字号与关键交互，并记录范围和限制。
8. 保留翻译服务、场景/术语、字幕样式和记录功能；不能为重做界面静默移除现有功能。

## 权限与边界

- 已授权 Fork、安装开发工具、实现与测试、子代理协作。
- 代码保存在 /Users/qece/Desktop/code/simul-subtitle，分支 codex/android-material-models。
- 用户已审核并授权本次本地提交，随后明确暂停推送与Release发布；仅在当前仓库配置GitHub用户名和隐私邮箱。
- 不触碰 ds_auto_bot 的运行资源，不操作已连接的远程 ADB 设备，验收使用专用本地模拟器。
- 本轮用户已明确授权桌面专属文件的物理删除，23个文件已移除，清单见`.qa/android-only-delete-manifest.json`；范围包含desktop源码、mac/win工具、旧桌面截图与Windows CI。保留Android依赖的core及Windows开发机编译Android所需的gradlew.bat。
- 不创建 AI 定时任务。测试不发送飞书警报。本项目当前未发现飞书集成。
- 新增/重写函数补充中文注释，新增用户文案、日志均使用中文。临时产物加入 .gitignore。

## 当前状态与接续入口

本轮1.2.0已完成实现与本机验收，所有追加任务已核查；用户已批准纳入本次本地提交，暂不推送或发布。旧1.1.0证据在后文保留为历史，不作为本轮结果。

- 新APK：`.artifacts/simul-subtitle-android-1.2.0-arm64.apk`，14,144,838字节，SHA-256 `27627e75a7715c3d3d69c19aa71288abce80b6b75858fea7f12d247ae734c93c`。
- 单元测试core198/app33通过，设备31项通过，低矮横屏修复后自适应2项再次通过；完整系统字幕链路1项通过。最终Debug/AndroidTest/Release构建和Lint通过。
- 手机、平板横竖屏、700dp窗口、手机横屏、浅深色、动态色、200%字号已验收；11张新版文档展示图与本地引用已核对。
- 23个已获授权桌面文件已物理删除；模型包仍为安装后由用户手动下载。
- 详细日志、设备边界与签名说明见[Android验收记录](ANDROID_QA.md)。子代理与本地HTTPS服务器已关闭，专用模拟器保留。

## 本轮任务与验收清单（2026-10-15，已完成）

本轮追加API预设、真实字幕行数上限、主题色、页面美化与桌面物理删除。以下项目已按最终日志、实际截图和构建结果核对；设备覆盖边界见验收记录。

- [x] 翻译服务预设：DeepSeek、硅基流动、OpenRouter等可信官方地址，选后可改地址和模型；保留全手填；只更新草稿，不自动保存/发送请求；跨完整端点切换清空旧凭据，避免密钥串用。
- [x] 字幕实际排版行数上限：0不限、1～20行；原文与译文合计，诊断行另计，保留最新行；适配换行、字号、宽度、固定高度，与历史句数/字数限制独立。
- [x] 配色选择：多套协调的Material3主题、浅深色、Android12+动态取色；选择持久化，旧系统明确回退。
- [x] 页面重新编排：同传页突出主要操作与状态，减少大块提示；设置结构更清楚；手机/平板均实用。
- [x] 桌面物理清理核对：23个获授权文件已删除，核对清单、Android构建与文档引用；清单与Android构建均已核对。
- [x] 模型库卡片对齐：标题、状态、说明、语言/大小、操作区有统一层级与位置；长文与大字号保持可读，完整详情可展开。
- [x] 用真实手机/平板窗口重新视觉验收，含浅深色、动态色、200%字号、横屏、预设编辑和实际行数控制。
- [x] 重新构建可安装APK，跑适当单元、设备、Lint及代码审阅；不提交/推送。
- [x] README与全部相关说明同步当前代码：替换当前展示的全部旧截图，补入新版截图；修正导航名称、模型语言、许可、更新机制、预设、配色、行数设置。历史桌面图不再作为当前UI展示。

分工：主代理处理翻译预设、偏好/状态/服务衔接、主题色、构建集成和文档最终验收；Sol/high代理Kuhn处理原生字幕实际行数限制和对应设备测试；另委派Sol/high处理不重叠的页面重排。


### 本轮修复记录

- 用户已明确授权此次删除且无需再次询问。已物理移除23个桌面专属文件；完整清单`.qa/android-only-delete-manifest.json`。保留Android依赖的core及可在Windows开发机编译Android的gradlew.bat。
- 预设、六种手动主题及动态取色、真实字幕最多行数、页面重排均已接合。core198、app33单元通过，Lint通过。
- 首轮设备29项中27通过：原生限行11项全部通过；模型长文发现Text自然宽度测量溢出，已固定文字占满列宽，3项对齐测试复测通过。新功能5项交互与状态检查也通过。最终整套31项设备回归已通过。
- README与技术文档已更新，11张新版截图已替换；视觉检查、完整字幕链路、最终APK安装和文档链接核查均已完成。

### 视觉参考补充

- 用户指定视觉参考：[FlClash](https://github.com/chen08209/FlClash)。借鉴Material You层级、中性色调、紧凑卡片、属性行与自适应导航；本项目继续使用原生Compose Material3。实现已在本地模拟器完成视觉验收，覆盖边界见验收记录。
- 卡片编排：模型库刷新并入标题操作，属性改标签/值双列，压缩冗余高度并保留可展开详情；主题选项使用无描边色调容器，提供协调的色调选择。手机原声/字幕语言常规字号并排，大字号回退单列以保留可读性；主操作使用56dp圆角按钮。最终视觉与回归已完成。

## 旧1.1.0历史记录（不代表本轮验收通过）

以下保留原有目标实施、测试结果、日志路径与交付证据。历史复选框只表示旧版检查点，不用于追踪本轮任务。

### 旧1.1.0实施步骤（历史勾选记录）

- [x] Fork 仓库、克隆本地、建立分支、索引源码图谱。
- [x] 安装 JDK 17、SDK 35、构建工具与 ARM64 模拟器；完成基线构建。
- [x] 固定模型目录与下载源，验证实际文件、校验值与许可。
- [x] 实现可测试模型存储/下载/完整性/选择状态；至少两模型。
- [x] 改为文件路径加载模型，接入原生服务；未安装模型时阻止启动并引导下载。
- [x] Compose Material 3 完整界面：同传、模型库、场景、设置与记录；手机/平板自适应。
- [x] 仅 Android 构建，setup 只下载运行时库，不下载或打包模型；排除 assets/models。
- [x] 单元/集成测试、构建、Lint、检查 APK 不含模型。
- [x] 本地模拟器实测首次启动、下载/取消/重试/启用/切换、权限和字幕路径。
- [x] 手机与平板视觉验收，含深浅色、横屏、分屏、大字号与点击区域；修复后重验受影响页面。
- [x] 最终代码审阅、记录证据、交付 APK 与限制，等待人工审核。

### 旧1.1.0交付检查点

2026-10-15旧1.1.0检查点：当时开发与本机验收已完成，代码未提交、未推送，等待用户审核。以下APK、截图及结果仅对应该检查点，不能作为本轮改动的最终验收结论。

- 交付APK：`.artifacts/simul-subtitle-android-1.1.0-arm64.apk`，14,080,270字节，arm64，Android10起，本地验收签名。
- 旧版验收范围：`docs/ANDROID_QA.md`；来源与完整物理直连证据：`docs/MODEL_SOURCES.md`。
- 旧版最终截图：`.qa/screenshots/release-*.png`，拼接预览`.qa/screenshots/final-overview.png`。
- 图谱项目`simul-subtitle`。源码基线97e0a49954fc01029468775d3d93e195adbf146f，工作分支`codex/android-material-models`。
- 下方按原顺序保留旧版实施中与完成时的检查点；其中“正在”“待”等描述均为当时状态，不覆盖本文前面的本轮进度。

### 旧1.1.0验收证据与过程记录

每次更新此节写真实命令结果、设备规格、截图位置与尚缺检查。构建成功不能代替视觉和业务结果验收。

#### 子代理重建（用户明确要求）

原四个继承主模型的代理已停止，保留写入文件。重建分工：
- UI：GPT-6.1 Sol / high，仅 ui/。
- 模型管理：GPT-6.1 Sol / high，仅 models/ 与对应测试。
- 下载源核查：GPT-6.1 Sol / medium，仅 docs/MODEL_SOURCES.md 和 .qa/。
- 文档/环境脚本：DeepSeek Flash / low，仅 setup.sh、README、docs/build.md、tools/android-env.sh。
主代理负责 Gradle、Manifest、MainActivity、Engines、CaptionService、构建集成与最终视觉/业务验收。

#### 环境与基线验证

- JDK17：/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home。
- SDK：/Users/qece/Library/Android/sdk，API35/build-tools35/ARM64模拟器。
- AVD simul_phone，端口5580，设备必须明确指定 emulator-5580。
- 原版 core 回归：:core:test BUILD SUCCESSFUL，日志 .qa/baseline-core.log。
- Compose依赖接入后的基线APK构建成功（此时尚未整合新界面/模型），.qa/build.log。不能当作最终完成证据。
- MainActivity已替换Compose入口，AppController正在接合UiContract；Engines已改文件加载Reazon与SenseVoice，CaptionService发布状态并固定会话模型。尚未完成集成构建。

#### 第一轮集成与设备验收（当时进行中）

- 子代理已按用户要求换模型：UI与下载实现 Sol/high，来源 Sol/medium，文档 DeepSeek/low；来源、实现、UI任务已完成并关闭。
- docs/MODEL_SOURCES.md 与 .qa/model-source-check/handoff-models.json 提供7文件、20来源真实下载校验。ModelScope的Reazon decoder不匹配，使用HF-mirror固定版。国内物理直连只完整测了VAD及词表，不能声称全国无代理大文件验证完成。
- 新UI+模型+服务第一轮 core/app单元及APK构建成功，.qa/first-integration.log。
- Lint旧AGP8.7.3与Lifecycle2.9检查器二进制不兼容；AGP升级8.9.2后 lintDebug 成功，无禁用规则。残留warning主要旧资源/依赖更新提醒及应用context单例静态分析。
- 专用模拟器首次界面截图 .qa/screenshots/phone-initial.png；主按钮已优化为缺模型/翻译/权限的对应引导，自定义语言改折叠，模型详情折叠以减少首屏负担。
- Reazon真实下载后已校验安装，并用日语WAV实际解码：うちの中学は弁当制で持っていけない場合は五十円の学校販売のパンを買う，解码89毫秒（仅模拟器单例冒烟，非性能榜）。.qa/reazon-device-test.log，logcat包含原文。
- SenseVoice设备真实下载测试正在运行（adb instrument会话25451），尚未判定通过。
- UI交互测试首轮2项中1项因重复文本匹配失败，已改为选择第一个同名节点，需要重跑。不是产品崩溃。
- 主代理新增app/src/androidTest/.../AdaptiveUiTest.kt、ModelDeviceTest.kt与测试音频ja.wav（仅测试APK）；测试进程结束后重新构建最新测试包。
- 当前文档代理 Schrodinger 01a13dbd-015b-7662-8f66-f45d13410b6c，仅docs/README/architecture/asr/privacy；审阅代理 Linnaeus 01a13dbe-ee7e-7032-84b2-9baf4f753877 只读审阅，等待通知。
- 尚缺：两模型切换/取消与恢复、完整系统内录→识别→受控翻译→悬浮显示、平板/横屏/分屏/大字号/深色视觉、最终release APK、最终回归与代码审阅。

#### 模型实际解码结果

- 两模型都已通过真实设备侧下载/校验/启用/VAD/日语WAV解码测试。
- Reazon：.qa/reazon-device-test.log，OK(1 test)，已安装77,745,395字节；单例解码89ms。
- SenseVoice：.qa/sense-device-test.log，OK(1 test)，240,193,589字节；测试40.136s（包含实际下载与加载）；单例解码126ms。
- 原文与计时已保留 .qa/model-recognition-evidence.log。不能将这两条结果作为准确率或手机性能排名。

#### 第二轮验收与审阅修复

- core 196 项、app 29 项单元测试通过；实际 Compose 交互 2 项通过（.qa/ui-device-tests.log）。
- 手机/平板均已进入真实 Compose 页面；平板按2560×1600、240dpi窗口验收，非独立物理平板。浅色模型库、场景、API、字幕预览截图保存在 .qa/screenshots/tablet-*.png。
- 深色与200%字号模型库可读；侧导航原104dp标题拥挤，已改大字号144dp，待重拍。已收起获准权限的长说明、简化产品文案。
- 独立审阅确认Jev密钥草稿串用、启动时连接测试未取消、记录删除入口遗漏3项。Sol/high代理Gauss负责修复，主代理继续原生捕获链路验收。
- 完整链路测试仅调试变体信任10.0.2.2本地临时CA，发布变体不会合并debug证书/网络配置。受控HTTPS流式译文用于证明请求和显示路径，不用于声称云模型翻译质量。

#### 旧版最终验收推进（当时的接续检查点）

- Release R8构建成功，14,080,270字节，APK无ONNX/TFLite/测试WAV/调试CA，证据 .qa/apk-verification.json；保存 .artifacts/simul-subtitle-android-1.1.0-arm64.apk。
- 最新单元测试 core198、app29通过，Lint无error；独立审阅三项已修复，设备回归10项通过（.qa/review-device-tests.log）。
- 完整系统内录、VAD、SenseVoice、HTTPS流式夹具、悬浮字幕定稿验证通过，.qa/capture-device-test.log OK(1)，29.347秒；先前失败原因是本地HTTP夹具连接关闭头，不是通过放宽定稿断言修复。fixture已改HTTP/1.1及Connection:close。
- 已检查深色界面系统栏图标问题并修复，需最终Release截图确认；导航短标签避免200%字号四字挤压。
- 隔离包 io.github.korenkrita.simulsub.qa 已安装，不删除已有应用数据。生命周期cancel与start两阶段通过，force-stop后已重开，complete阶段正在等待真实下载完成；接着需selected重启恢复检查。
- 实测代码与详细范围见 docs/ANDROID_QA.md。仍待：生命周期完成判定、最终Release安装启动、手机横屏/中窗口及最终浅色截图整理、终末代码/构建状态检查。不提交推送。

#### 旧1.1.0完成状态（历史结论）

1. 工具链、仅Android构建、Compose Material3界面、多模型管理和原生文件加载均完成。
2. core198/app29单元测试、10项界面/控制器回归、两模型实际解码、完整内录到字幕定稿、下载取消/重试/进程恢复/选择恢复均通过。
3. Release APK安装启动和模型切换通过；手机横屏、700dp中等窗口、平板横竖屏、浅色/深色及200%字号均实际检查。修复了导航拥挤和深色系统栏可读性。
4. 最终物理网卡en1完整下载两套模型全部7个独特文件并核对SHA-256，证据`.qa/direct-asr/`；仅证明当前网络，不承诺全部运营商一致。
5. 最终构建/Lint通过，Lint136条warning、0 error；签名验证通过；APK不含任何语音模型或debug CA。
6. 子代理全部关闭；本地测试HTTPS服务器已停止。专用模拟器保留供人工验收，尺寸恢复手机、字号100%。不操作远程ADB，不创建定时任务。
7. 当时历史桌面源码尚未物理删除；产品/Gradle/发行入口均仅Android。本轮已获明确授权并物理移除23个桌面专属文件，详见前文本轮状态。Git未提交、未推送，Fork云端尚无本次改动。后续用户审核后才决定提交/推送/发行签名和发布。
