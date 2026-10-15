# 构建与开发

本 Fork **仅支持 Android 手机和平板**，不再构建或发布 Windows、macOS 等其他平台客户端。桌面系统仅作为 Android 开发主机使用。

## 当前构建范围

`settings.gradle.kts` 只包含 `:core` 与 `:app`：前者为纯 Kotlin 分句、翻译和会话流水线，后者为 Android Compose UI、控制器、采集服务、模型库与浮窗。桌面客户端、macOS/Windows采集与打包工具、桌面测试和Windows工作流已移除。`gradlew.bat`保留供Windows开发机编译Android使用。

## 环境与命令

- JDK 17、Android SDK Platform 35（`compileSdk` / `targetSdk` 35）。
- `minSdk` 29，仅打包 `arm64-v8a`。
- 无需自行编译原生库；使用 sherpa-onnx 1.13.8 预编译 AAR。当前 Android CI 安装 `build-tools;35.0.0`。

```bash
git clone https://github.com/ZOUYIQAQ/yuyi
cd yuyi
source tools/android-env.sh
./setup.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

`tools/android-env.sh` 只改变当前 shell：优先沿用有效 `JAVA_HOME`，否则尝试 Homebrew openjdk@17；SDK 默认 `$HOME/Library/Android/sdk`，沿用已设置的 SDK 路径；追加存在的命令目录。它不修改用户 shell 配置，需要用 `source` 加载。

`setup.sh` 仅下载运行时 `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar`，固定大小 **38,691,998 字节**，SHA-256 为 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`。已存在且校验通过则跳过；先写 `.part`、校验后转正，超时、有限重试，失败以非零状态退出。脚本不下载 ASR/VAD 权重。

需要代理时可为 setup 设置 `HTTPS_PROXY`；Gradle 可按环境设置 `-Dhttps.proxyHost`、`-Dhttps.proxyPort`。这与应用关于页的更新行为无关。

| 产物 | 路径 |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |

## 模型与许可打包

- `assets.exclude("models/**")` 排除历史模型目录，APK 不包含 ASR 或 VAD 权重。
- 安装后进入「模型库」主动下载；每个 ASR 包包含各自 VAD，无需独立选择。
- 固定源、字节数与 SHA-256 在 `models/ModelCatalog.kt`；逐文件验证后落盘，下载成功不自动启用。用户需点击「启用模型」，同传运行期间禁止切换。
- 权重位于私有 `files/models/<模型标识>/asr/` 与 `vad/`。
- 许可文本随 APK 的 `assets/licenses/` 分发，包括 ReazonSpeech、SenseVoice 与 Silero，不与模型下载文件放在同一目录。事实依据见 [MODEL_SOURCES.md](MODEL_SOURCES.md)。

可检查 APK 内容，确认没有误打包 `.onnx` / `.tflite` 且包含许可文本：

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk
```

## 回归与设备验收

`.github/workflows/android.yml` 执行核心测试、Android 本地单元测试、lint、Debug 打包和 APK 模型排除检查。设备 UI 回归需另外准备 Android 设备；当前 ABI 为 arm64，不能将仅有 x86 原生库的模拟环境当作模型运行验收。

```bash
./gradlew :app:connectedDebugAndroidTest
```

编译、单元测试、UI 示例和真实采集分别提供不同证据。真实浮窗、模型完整下载与启用、旋转/分屏、采集授权与拒绝路径、日志活动保护、付费翻译服务可用性需在相应设备和账户上验证。验收状态以 [ANDROID_QA.md](ANDROID_QA.md) 为准；本文命令不表示本轮已全部执行。

## 当前更新与发行

「设置 → 应用更新」集中管理更新；默认每次启动检查一次，首次引导结束后才请求，旋转或返回系统授权页不重复检查。可关闭自动检查，手动检查始终可用。发现新版可立即更新、稍后24小时提醒、跳过本版或关闭自动检查；设置中可恢复版本提醒。

`GithubReleaseUpdateSource`只请求本Fork的GitHub正式发行元数据，按数字组件比较版本，跳过草稿和预发行；404表示尚未发布。单次调用最多15秒、响应上限256KiB，可取消，不复用翻译密钥或请求参数。关闭自动检查取消未完成的自动请求并忽略迟到结果；自动失败只在更新设置保留结果，不弹错误打断使用。

「更新」通过系统浏览器打开该发行版的arm64 APK下载链接，无APK时打开其发布页。不在应用内下载或安装，不申请新的安装权限。服务端只需发布正确版本标签（如v1.5.0）并附APK，无需`update.json`。接口字段与下载地址来自[GitHub官方发行API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。浏览器选择器参考[Android Intent文档](https://developer.android.com/reference/android/content/Intent#CATEGORY_APP_BROWSER)。保留的旧AppUpdater类与Provider不接入当前流程，安装APK权限已移除。

## 签名与发布前检查

包名为 `io.github.zouyiqaq.yuyi`。当前 Release 构建配置仍引用 `signingConfigs.getByName("debug")`，因此签名取决于构建机器的 keystore；不能把其他机器新生成的 debug key 当作原发行证书。覆盖安装要求相同包名、兼容版本号与相同签名；正式发行前必须确认维护者指定的证书和密钥管理方案。

构建后使用已安装 SDK 的 `apksigner` 核对证书，使用 `aapt` 或 `apkanalyzer` 核对包名与版本；与实际目标旧包比较。不要把旧文档的证书指纹或旧上游仓库发行流程作为本 Fork 已验证事实。发布说明、标签和 APK 版本应一致；发布后回读安装包核对哈希。当前客户端不要求上传 `update.json`。

## 羽译身份与图标

当前应用名为羽译，应用ID为`io.github.zouyiqaq.yuyi`，可与原版`io.github.korenkrita.simulsub`共存；两者的数据、模型目录、权限、通知与后台任务由系统分别管理。新包不会自动读取旧包模型或密钥，需要重新配置。内部Kotlin命名空间暂保留上游名称以兼容共享核心与原生库，它不是系统安装包ID。两个FileProvider与WorkManager的authority使用应用ID生成。

图标使用用户图标包中的原始31路径SVG（`docs/images/icon.svg`）。彩色、纯色共用原轮廓；不再提供视觉重复的原图模式。完整人物、头花、蝴蝶结和飘落装饰默认保留，完整画幅缩入108dp图层中央64dp区域，边缘延展避免系统裁切。原PNG只作为取色参考保存在`docs/images/icon-original.png`，不作为可选图标模式。默认背景为柔和渐变与角部插画色块。

外观提供彩色/纯色、插画/鼠尾草绿/奶油白/浅山茶粉/暖墨色背景，以及统一飘落装饰开关（花瓣和右下绿叶同步，头花保留）。纯色提供10种预设配色，默认跟随主题相近色，手动选色后保持固定。图标颜色根据背景生成深浅角色；共110个资源入口（彩色10个、纯色100个），复用两张透明轮廓，不为每个色样重复打包位图。自定义和动态主题使用最接近的预设色；主题操作或恢复时仅在颜色确实改变后更新入口。所有可用组合通过系统入口切换。

`tools/generate-icon.py`使用Pillow、CairoSVG和NumPy，从仓库源文件生成全部资源与显式Kotlin资源映射。安装这些依赖到开发虚拟环境；本机Cairo通过`DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib`定位。重新生成示例：

```bash
python tools/generate-icon.py --preview-dir .qa/icon-import/preview
```

Manifest的110个v3图标别名指向同一个MainActivity，默认只启用彩色插画入口。API33+批量原子切换，旧版本先开启目标再关闭其他入口；使用DONT_KILL_APP，失败回滚组件状态，成功持久化选择。原图旧选择迁移至矢量样式，背景和花瓣保留；启动及恢复时校正已存偏好与实际入口，正常恢复只检查真实入口，不重复遍历全部组件。它改变真实桌面图标，不创建额外快捷方式。启动器刷新速度和系统主题图标着色由系统决定。

适配规则参考[Android官方自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

当前Material 3组件显式使用Google官方稳定版1.4.0，保留Compose BOM管理其他组件；设置改为分组入口与带返回顶栏的详情。选择器使用ModalBottomSheet，表单为填充TextField，分组为Card/ListItem，页面与尺寸变化使用Compose官方动画。未把实验性Expressive全套API误称为稳定版能力。

新安装使用原图取色的「山茶」手动主题（`ThemePalettes.DEFAULT_ID = "amber"`），动态取色默认关闭；已保存配色保留；原暖砂的稳定ID现在对应山茶。支持自定义RGB颜色与HSV调色，确认时保存并退出动态取色。同传主操作固定在导航上方，至少72dp高，平板最多480dp宽，点击仍沿用模型、翻译、权限和开始/停止的既有流程。


默认山茶主题取色：橙棕`#D38569`、山茶红`#E24E37`、奶油`#FAF0E2`、鼠尾草绿`#9AAF87`。前三个强调角色分别由发色、花色和绿叶色通过Google MaterialColors生成，奶油色用于中性表面。输入表单使用`adjustResize`，根布局仅避让系统栏和缺口，内容单独处理IME，底部导航不随键盘上移。字幕显示只选择按句数或按译文字数，不再叠加行数、最少句数和字数阈值；宽高等尺寸选项仍常显。默认仅译文、无耗时，悬浮预览支持横竖画幅。

启动图单独生成到`ic_splash_portrait_pixels.webp`，保留人物和飘落装饰，透明区域不带桌面背景；Android12起通过独立启动主题的`windowSplashScreenAnimatedIcon`指定，不改应用背景。依据[Android官方启动页文档](https://developer.android.com/develop/ui/views/launch/splash-screen)。

主导航为同传／字幕／设置；模型和场景作为设置内管理详情，首页保留快捷入口并记录返回来源。首次引导仅权限、翻译API、识别模型三步，完成键为appearance.onboarding_completed，均可跳过并从设置重新运行。首次引导的API表单复用正式配置页，隐藏可选请求参数，保留未保存离开确认。

启动整页背景通过SplashTheme同步：Android12起持久化资源主题，六种预设与应用浅深背景相等；自定义颜色在系统最早启动页用相近预设，Android12/13动态色使用邻近系统中性色，Android14起动态背景精确对应。Android10/11的最早启动背景为默认山茶随系统明暗；Activity首帧各模式均使用应用实际颜色。
