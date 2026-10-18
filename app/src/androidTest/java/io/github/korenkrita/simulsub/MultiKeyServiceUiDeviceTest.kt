package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 沿用当前手机或平板的真实窗口；全部动作只回填内存，不连接偏好、密钥仓库或外部服务。 */
class MultiKeyServiceUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private var hideKeyboard: () -> Unit = {}
    private var newCredentialNumber = 0
    private val savedCredentials = mutableMapOf<Pair<String, String>, PlatformKeyUiState>()
    private val asrId = "fixture-qwen-asr"
    private val translationId = "fixture-deepseek"

    /** 编辑态名称和密钥保留原文；保存仅去除名称首尾空格，密钥在显隐与重新进入后仍完全一致。 */
    @Test fun addNamedCredentialKeepsDraftRawAndTrimsOnlyLabelOnSave() {
        show()
        openSection("平台密钥")
        click(hasTestTag("platform-key-add-qwen"))
        val item = "qwen:fixture-new-1"
        val rawLabel = "  课程专用 密钥 +/=  "
        val savedLabel = "课程专用 密钥 +/="
        val rawKey = "  fixture-invalid-key._+-/=:中文原文  "
        reveal(hasTestTag("platform-key-label-$item"))
        compose.onNodeWithTag("platform-key-label-$item").performTextReplacement(rawLabel)
        reveal(hasTestTag("platform-key-$item"))
        compose.onNodeWithTag("platform-key-$item").performTextReplacement(rawKey)
        hideKeyboardOnUiThread()
        compose.runOnIdle {
            val draft = credential("qwen", "fixture-new-1")
            assertEquals(rawLabel, draft.credentialLabel)
            assertEquals(rawKey, draft.key)
            assertTrue(draft.isNew)
            assertTrue(draft.hasUnsavedChanges)
            assertEquals("默认密钥", credential("qwen", "default").credentialLabel)
            assertEquals("fixture-qwen-default", credential("qwen", "default").key)
        }
        reveal(hasTestTag("platform-key-$item"))
        eye(item, "显示阿里百炼 · 千问密钥").assertIsDisplayed()
        assertRenderedKey(item, "•".repeat(rawKey.length))
        eye(item, "显示阿里百炼 · 千问密钥").performClick()
        compose.onNodeWithTag("platform-key-$item").assertTextContains(rawKey)
        eye(item, "隐藏阿里百炼 · 千问密钥").assertIsDisplayed()
        assertRenderedKey(item, rawKey)
        eye(item, "隐藏阿里百炼 · 千问密钥").performClick()
        eye(item, "显示阿里百炼 · 千问密钥").assertIsDisplayed()
        assertRenderedKey(item, "•".repeat(rawKey.length))
        compose.runOnIdle { assertEquals(rawKey, credential("qwen", "fixture-new-1").key) }
        click(hasTestTag("platform-key-save-$item"))
        compose.runOnIdle {
            val saved = credential("qwen", "fixture-new-1")
            assertEquals(savedLabel, saved.credentialLabel)
            assertEquals(rawKey, saved.key)
            assertTrue(saved.hasSavedKey)
            assertFalse(saved.hasUnsavedChanges)
            assertFalse(saved.isNew)
            assertEquals(1, events.count { it == "保存:qwen:fixture-new-1" })
        }
        reveal(hasTestTag("platform-key-label-$item"))
        compose.onNodeWithTag("platform-key-label-$item").assertTextContains(savedLabel)
        compose.onNodeWithTag("platform-key-status-$item").assertTextEquals("密钥状态：已配置")
        screenshot("新增命名密钥已保存")
        compose.onNodeWithContentDescription("返回设置").performClick()
        openSection("平台密钥")
        reveal(hasTestTag("platform-key-label-$item"))
        compose.onNodeWithTag("platform-key-label-$item").assertTextContains(savedLabel)
        reveal(hasTestTag("platform-key-$item"))
        eye(item, "显示阿里百炼 · 千问密钥").performClick()
        eye(item, "隐藏阿里百炼 · 千问密钥").assertIsDisplayed()
        assertRenderedKey(item, rawKey)
        compose.onNodeWithTag("platform-key-$item").assertTextContains(rawKey)
        compose.runOnIdle {
            val saved = savedCredentials.getValue("qwen" to "fixture-new-1")
            assertEquals(savedLabel, saved.credentialLabel)
            assertEquals(rawKey, saved.key)
        }
    }

    /** 内置听写服务在列表弹窗中切换与跟随默认密钥，不打开编辑器或离开列表。 */
    @Test fun listeningCredentialDialogSwitchesInPlaceAndCanFollowDefault() {
        show()
        openSection("听写服务")
        cardAction(asrId, "选择密钥")
        assertDialog("千问 · 测试听写")
        screenshot("听写服务选择密钥弹窗")
        chooseCredential("课程密钥")
        assertDialog("千问 · 测试听写")
        compose.onNodeWithText("当前使用：课程密钥").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("course", state.settings.listeningProfiles.single { it.id == asrId }.credentialId)
            assertEquals(UiDestination.SETTINGS, state.destination)
            assertNull(state.settings.listeningEditor)
            assertEquals("profile:$asrId", state.live.listeningServiceId)
            assertEquals(listOf("绑定:$asrId:course"), events)
        }
        compose.onNodeWithText("完成").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        reveal(hasText("密钥：课程密钥") and hasAnyAncestor(hasTestTag("service-profile-$asrId")))
        screenshot("听写服务命名密钥已绑定")
        cardAction(asrId, "选择密钥")
        chooseCredential("跟随默认密钥")
        compose.onNodeWithText("当前使用：默认密钥").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle {
            assertEquals("", state.settings.listeningProfiles.single { it.id == asrId }.credentialId)
            assertEquals("绑定:$asrId:", events.last())
            assertNull(state.settings.listeningEditor)
        }
    }

    /** 内置翻译服务使用带翻译前缀的绑定回调，名称回填后仍停留原服务列表。 */
    @Test fun translationCredentialDialogUsesTranslationServiceIdAndStaysOnList() {
        show()
        openSection("翻译服务")
        cardAction(translationId, "选择密钥")
        assertDialog("DeepSeek · 测试翻译")
        chooseCredential("翻译备用密钥")
        compose.onNodeWithText("当前使用：翻译备用密钥").assertIsDisplayed()
        screenshot("翻译服务选择命名密钥")
        compose.onNodeWithText("完成").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        reveal(hasText("密钥：翻译备用密钥") and hasAnyAncestor(hasTestTag("service-profile-$translationId")))
        compose.runOnIdle {
            assertEquals("backup", state.settings.translationProfiles.single { it.id == translationId }.credentialId)
            assertEquals(listOf("绑定:translation:$translationId:backup"), events)
            assertEquals(UiDestination.SETTINGS, state.destination)
            assertNull(state.settings.translationEditorId)
            assertEquals(translationId, state.live.translationServiceId)
        }
    }

    /** 首页选择服务后小字展示绑定的密钥名称，切回另一听写服务同步切换说明。 */
    @Test fun homeServiceSelectionsShowTheirNamedCredentialDescriptions() {
        show(destination = UiDestination.LIVE)
        openServicePicker("listening-service-picker")
        choosePopup("千问 · 测试听写")
        reveal(hasText("密钥：课程密钥") and hasAnyAncestor(hasTestTag("listening-service-picker")))
        openServicePicker("translation-service-picker")
        choosePopup("DeepSeek · 测试翻译")
        reveal(hasText("密钥：翻译备用密钥") and hasAnyAncestor(hasTestTag("translation-service-picker")))
        screenshot("首页两步服务与命名密钥")
        compose.runOnIdle {
            assertEquals("profile:$asrId", state.live.listeningServiceId)
            assertEquals(translationId, state.live.translationServiceId)
            assertEquals(listOf("使用听写:profile:$asrId", "使用翻译:$translationId"), events)
        }
        openServicePicker("listening-service-picker")
        choosePopup("千问 · 测试实时听译")
        reveal(hasText("密钥：默认密钥") and hasAnyAncestor(hasTestTag("listening-service-picker")))
        compose.onNodeWithTag("translation-service-picker").assertDoesNotExist()
        compose.onNodeWithTag("translation-included").assertIsDisplayed()
        screenshot("首页实时听译默认密钥")
    }

    /** 本地模型仅提供下载或使用，听写没有新建入口；翻译保留自定义兼容接口创建入口。 */
    @Test fun localModelsExposeDownloadOrUseAndOnlyTranslationAllowsCreation() {
        show()
        openSection("听写服务")
        listOf("local:fixture-installed", "local:fixture-missing").forEach { id ->
            reveal(hasTestTag("service-profile-$id"))
            listOf("编辑", "选择密钥").forEach { label ->
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("service-profile-$id"))).assertDoesNotExist()
            }
        }
        compose.onNodeWithTag("create-listening-service").assertDoesNotExist()
        cardAction("local:fixture-installed", "使用")
        compose.runOnIdle { assertEquals("local:fixture-installed", state.live.listeningServiceId) }
        cardAction("local:fixture-missing", "下载模型")
        compose.runOnIdle {
            assertEquals(UiDestination.MODELS, state.destination)
            assertEquals("local:fixture-installed", state.live.listeningServiceId)
            assertTrue(events.none { it.startsWith("编辑:") || it.startsWith("创建:") })
        }
        compose.onNode(isSelectable() and hasText("设置")).performClick()
        openSection("翻译服务")
        reveal(hasTestTag("create-translation-service"))
        compose.onNodeWithTag("create-translation-service").assertIsDisplayed().assertIsEnabled()
        reveal(hasTestTag("service-profile-$translationId"))
        screenshot("翻译目录保留自定义创建")
    }

    /** 自定义接口与海外模板都通过创建弹窗进入编辑器，筛选和搜索不自动创建或切换活动服务。 */
    @Test fun createTranslationProviderSupportsCustomAndSearchedOverseasTemplates() {
        show()
        openSection("翻译服务")
        click(hasTestTag("create-translation-service"))
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("继续配置").assertIsNotEnabled()
        openServicePicker("new-translation-provider")
        choosePopup("自定义兼容接口")
        compose.onNodeWithText("继续配置").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("返回翻译服务列表").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("创建模板:custom", events.last())
            assertEquals("fixture-created-custom", state.settings.translationEditorId)
            assertEquals("custom", state.settings.api.presetId)
            assertEquals(translationId, state.live.translationServiceId)
        }
        reveal(hasText("服务地址") and hasSetTextAction())
        compose.onNode(hasText("服务地址") and hasSetTextAction()).performTextReplacement("https://fixture-compatible.example/v1")
        reveal(hasText("模型 ID") and hasSetTextAction())
        compose.onNode(hasText("模型 ID") and hasSetTextAction()).performTextReplacement("fixture-custom-model")
        click(hasText("保存翻译配置"))
        compose.runOnIdle {
            assertEquals("https://fixture-compatible.example/v1", state.settings.api.url)
            assertEquals("fixture-custom-model", state.settings.api.model)
            assertFalse(state.settings.api.hasUnsavedChanges)
            assertEquals(translationId, state.live.translationServiceId)
        }
        screenshot("创建自定义兼容接口编辑器")
        compose.onNodeWithContentDescription("返回翻译服务列表").performClick()

        listOf("openai" to "OpenAI", "gemini" to "Gemini").forEach { (id, query) ->
            click(hasTestTag("create-translation-service"))
            compose.onNode(hasText("海外平台") and hasAnyAncestor(isDialog())).performClick()
            openServicePicker("new-translation-provider")
            val title = TranslationPresets.entries.single { it.id == id }.title
            compose.onNode(hasText("阿里百炼 · 千问") and hasAnyAncestor(isPopup())).assertDoesNotExist()
            compose.onNode(hasText("自定义兼容接口") and hasAnyAncestor(isPopup())).assertExists()
            choosePopup(title)
            compose.onNodeWithTag("translation-provider-search").performTextReplacement(query)
            hideKeyboardOnUiThread()
            // 搜索会清空旧选择，必须重新选择目标后才能继续，防止创建筛选前的供应商。
            compose.onNodeWithText("继续配置").assertIsNotEnabled()
            openServicePicker("new-translation-provider")
            compose.onNode(hasText("Anthropic · Claude") and hasAnyAncestor(isPopup())).assertDoesNotExist()
            choosePopup(title)
            compose.runOnIdle { assertTrue(events.none { it == "创建模板:$id" }) }
            screenshot("海外模板搜索-$id")
            compose.onNodeWithText("继续配置").performClick()
            compose.onNodeWithContentDescription("返回翻译服务列表").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("创建模板:$id", events.last())
                assertEquals("fixture-created-$id", state.settings.translationEditorId)
                assertEquals(id, state.settings.api.presetId)
                assertEquals(TranslationPresets.entries.single { it.id == id }.url, state.settings.api.url)
                assertEquals(translationId, state.live.translationServiceId)
            }
            reveal(hasText("服务地址") and hasSetTextAction())
            compose.onNode(hasText("服务地址") and hasSetTextAction()).assertTextContains(
                TranslationPresets.entries.single { it.id == id }.url)
            screenshot("海外模板编辑器-$id")
            compose.onNodeWithContentDescription("返回翻译服务列表").performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("创建模板:custom", "创建模板:openai", "创建模板:gemini"), events.filter { it.startsWith("创建模板:") })
            assertEquals(translationId, state.live.translationServiceId)
        }
    }

    /** 创建隔离凭据与内置服务；不实例化控制器、偏好、网络客户端或字幕服务。 */
    private fun show(destination: UiDestination = UiDestination.SETTINGS) {
        val credentials = listOf(
            PlatformKeyUiState("qwen", "阿里百炼 · 千问", "fixture-qwen-default", true),
            PlatformKeyUiState("qwen", "阿里百炼 · 千问", "fixture-qwen-course", true, credentialId = "course", credentialLabel = "课程密钥"),
            PlatformKeyUiState("deepseek", "DeepSeek", "fixture-deepseek-default", true),
            PlatformKeyUiState("deepseek", "DeepSeek", "fixture-deepseek-backup", true, credentialId = "backup", credentialLabel = "翻译备用密钥"))
        savedCredentials.clear()
        credentials.forEach { savedCredentials[it.id to it.credentialId] = it }
        state = SimulSubUiState(destination = destination,
            live = LiveUiState(canStart = true, statusTitle = "已准备好", statusDetail = "", cloudReady = true,
                translationReady = true, listeningMode = ListeningMode.CLOUD_ASR, listeningServiceId = "profile:$asrId",
                translationServiceId = translationId, sourceOptions = listOf(UiOption("ja", "日语")),
                targetOptions = listOf(UiOption("zh", "中文")), sceneOptions = listOf(UiOption("", "通用")),
                permissions = listOf(PermissionUiState("audio", "录制播放音频", "测试权限", true),
                    PermissionUiState("overlay", "显示悬浮字幕", "测试权限", true))),
            settings = SettingsUiState(platformKeys = credentials, listeningMode = ListeningMode.CLOUD_ASR,
                listeningServiceId = "profile:$asrId", translationServiceId = translationId,
                listeningProfiles = listOf(
                    ServiceProfileUiState("local:fixture-installed", "本地 · 已安装测试模型", kind = "local"),
                    ServiceProfileUiState(asrId, "千问 · 测试听写", kind = "asr", selected = true, platformId = "qwen", credentialId = "course"),
                    ServiceProfileUiState("fixture-direct", "千问 · 测试实时听译", kind = "realtime", platformId = "qwen"),
                    ServiceProfileUiState("local:fixture-missing", "本地 · 待下载测试模型", kind = "local", configured = false)),
                translationProfiles = listOf(ServiceProfileUiState(translationId, "DeepSeek · 测试翻译", selected = true,
                    platformId = "deepseek", credentialId = "backup"))))
        refreshServicePresentation()
        val actions = SimulSubActions(
            onNavigate = { state = state.copy(destination = it) },
            onAddPlatformKey = { platformId ->
                newCredentialNumber++
                val template = state.settings.platformKeys.first { it.id == platformId }
                val draft = PlatformKeyUiState(platformId, template.label, credentialId = "fixture-new-$newCredentialNumber",
                    credentialLabel = "新密钥", isNew = true)
                state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys + draft))
                events += "新增:$platformId:${draft.credentialId}"
            },
            onPlatformKeyLabelChange = { platformId, credentialId, label ->
                updateCredential(platformId, credentialId) { it.copy(credentialLabel = label, hasUnsavedChanges = true) }
            },
            onPlatformCredentialChange = { platformId, credentialId, key ->
                updateCredential(platformId, credentialId) { it.copy(key = key, hasUnsavedChanges = true) }
            },
            onSavePlatformCredential = { platformId, credentialId ->
                // 对齐真实保存规则：只规范化名称，密钥值完整沿用草稿原文。
                updateCredential(platformId, credentialId) {
                    it.copy(credentialLabel = it.credentialLabel.trim(), hasSavedKey = true, hasUnsavedChanges = false, isNew = false)
                }
                savedCredentials[platformId to credentialId] = credential(platformId, credentialId)
                events += "保存:$platformId:$credentialId"
                refreshServicePresentation()
            },
            onDiscardPlatformKeys = {
                state = state.copy(settings = state.settings.copy(platformKeys = savedCredentials.values.toList()))
                refreshServicePresentation()
            },
            onServiceCredentialChange = { serviceId, credentialId ->
                events += "绑定:$serviceId:$credentialId"
                state = state.copy(settings = if (serviceId.startsWith("translation:")) state.settings.copy(
                    translationProfiles = state.settings.translationProfiles.map {
                        if (it.id == serviceId.removePrefix("translation:")) it.copy(credentialId = credentialId) else it
                    }) else state.settings.copy(listeningProfiles = state.settings.listeningProfiles.map {
                        if (it.id == serviceId) it.copy(credentialId = credentialId) else it
                    }))
                refreshServicePresentation()
            },
            onSelectListeningService = { id ->
                events += "使用听写:$id"
                val profile = state.settings.listeningProfiles.single { it.id == id.removePrefix("profile:") }
                val mode = when (profile.kind) { "local" -> ListeningMode.LOCAL; "asr" -> ListeningMode.CLOUD_ASR; else -> ListeningMode.ONLINE }
                state = state.copy(live = state.live.copy(listeningServiceId = id, listeningMode = mode),
                    settings = state.settings.copy(listeningServiceId = id, listeningMode = mode))
                refreshServicePresentation()
            },
            onSelectTranslationService = { id ->
                events += "使用翻译:$id"
                state = state.copy(live = state.live.copy(translationServiceId = id), settings = state.settings.copy(translationServiceId = id))
                refreshServicePresentation()
            },
            onCreateTranslationProvider = { presetId ->
                events += "创建模板:$presetId"
                val editorId = "fixture-created-$presetId"
                val draft = TranslationPresets.apply(TranslationApiUiState(profileId = editorId), presetId)
                state = state.copy(settings = state.settings.copy(translationEditorId = editorId, api = draft))
            },
            onApiDraftChange = { draft ->
                state = state.copy(settings = state.settings.copy(api = draft.copy(hasUnsavedChanges = true)))
            },
            onSaveApi = {
                events += "保存翻译草稿:${state.settings.api.profileId}"
                state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(hasUnsavedChanges = false,
                    hasSavedConfiguration = true)))
            },
            onCloseTranslationServiceEditor = {
                state = state.copy(settings = state.settings.copy(translationEditorId = null, api = TranslationApiUiState()))
            },
            onEditListeningService = { events += "编辑:$it" }, onEditTranslationService = { events += "编辑:$it" },
            onCreateListeningService = { kind, _ -> events += "创建:$kind" }, onCreateTranslationService = { events += "创建:$it" })
        compose.setContent {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            hideKeyboard = { focus.clearFocus(force = true); keyboard?.hide() }
            SimulSubApp(state, actions)
        }
    }

    /** 按完整平台和凭据标识定位，避免同平台多条记录互相覆盖。 */
    private fun credential(platformId: String, credentialId: String) =
        state.settings.platformKeys.single { it.id == platformId && it.credentialId == credentialId }

    /** 按调用方给出的更新回填内存；编辑回调保留原文，仅保存回调规范化名称。 */
    private fun updateCredential(platformId: String, credentialId: String, update: (PlatformKeyUiState) -> PlatformKeyUiState) {
        state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
            if (it.id == platformId && it.credentialId == credentialId) update(it) else it
        }))
    }

    /** 模拟主代理的展示映射，只把已保存的凭据名称放入选项与首页说明，不在说明中放密钥值。 */
    private fun refreshServicePresentation() {
        val listening = state.settings.listeningProfiles.map { decorate(it, false) }
        val translation = state.settings.translationProfiles.map { decorate(it, true) }
        val listeningOptions = listening.filter { it.configured }.map {
            UiOption(if (it.kind == "local") it.id else "profile:${it.id}", it.name,
                if (it.kind == "local") "已安装本地模型" else "密钥：${it.credentialLabel}")
        }
        val translationOptions = translation.map { UiOption(it.id, it.name, "密钥：${it.credentialLabel}") }
        state = state.copy(live = state.live.copy(listeningServices = listeningOptions, translationServices = translationOptions),
            settings = state.settings.copy(listeningProfiles = listening, translationProfiles = translation,
                listeningServices = listeningOptions, translationServices = translationOptions))
    }

    /** 给每份服务回填独立平台选项，空绑定跟随默认凭据，选中状态从当前服务标识派生。 */
    private fun decorate(profile: ServiceProfileUiState, translation: Boolean): ServiceProfileUiState {
        val keys = savedCredentials.values.filter { it.id == profile.platformId }
        val activeCredential = profile.credentialId.ifEmpty { "default" }
        return profile.copy(credentialOptions = keys.map { UiOption(it.credentialId, it.credentialLabel) },
            credentialLabel = keys.firstOrNull { it.credentialId == activeCredential }?.credentialLabel.orEmpty(),
            selected = if (translation) state.live.translationServiceId == profile.id
                else state.live.listeningServiceId == if (profile.kind == "local") profile.id else "profile:${profile.id}")
    }

    /** 显隐按钮按凭据输入框限定范围，不能点到同平台另一把密钥的眼睛。 */
    private fun eye(itemId: String, description: String) = compose.onNode(
        hasContentDescription(description) and hasAnyAncestor(hasTestTag("platform-key-$itemId")))

    /** 直接检查视觉转换后的排版字符串，密码输入字段的敏感语义不代表当前是否显示原文。 */
    private fun assertRenderedKey(itemId: String, expected: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("platform-key-$itemId").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("密钥输入框应提供真实排版结果", action(results))
        }
        assertTrue("密钥输入框应有实际文字布局", results.isNotEmpty())
        results.forEach { result -> assertEquals("密钥显隐后的实际排版须符合预期", expected, result.layoutInput.text.text) }
    }

    /** 设置入口由正式根路由处理，不直接写入设置详情私有状态。 */
    private fun openSection(title: String) { click(hasText(title)); compose.waitForIdle() }

    /** 服务操作使用卡片稳定标识限定范围，避免多个同名按钮之间产生歧义。 */
    private fun cardAction(id: String, label: String) {
        click(hasText(label) and hasAnyAncestor(hasTestTag("service-profile-$id")))
    }

    /** 弹窗标题和平台密钥选择器必须可见，证明停留在内置服务的轻量选择弹窗。 */
    private fun assertDialog(name: String) {
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText("选择密钥") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("service-credential-name").assertTextEquals(name)
        compose.onNodeWithText("平台密钥").assertIsDisplayed()
    }

    /** 真实点击弹窗下拉锚点及其弹出菜单项，不用直接调用回调替代交互。 */
    private fun chooseCredential(label: String) {
        compose.onNode(hasAnyAncestor(isDialog()) and hasClickAction() and
            (hasText("平台密钥") or hasAnyDescendant(hasText("平台密钥")))).performClick()
        choosePopup(label)
    }

    /** 首页下拉使用稳定容器寻找实际可点击锚点，不要求标签文字自身带点击动作。 */
    private fun openServicePicker(tag: String) {
        reveal(hasTestTag(tag))
        compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction()).performClick()
    }

    /** 菜单选项只在弹出层内匹配，避免当前标签和菜单项重名。 */
    private fun choosePopup(label: String) {
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }

    /** 输入后关闭真实系统键盘并等待原生界面空闲，避免键盘退场影响滚动或截图。 */
    private fun hideKeyboardOnUiThread() {
        compose.runOnIdle { hideKeyboard() }
        compose.waitForIdle()
        awaitNativeIdle()
    }

    /** 等待应用消息与窗口事件都静止，Compose 空闲不能代替原生键盘及弹窗退场完成。 */
    private fun awaitNativeIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100L, 2_000L)
    }

    /** 点击前真实滚动到可见内容，所有用户动作均走正式 Compose 控件。 */
    private fun click(matcher: SemanticsMatcher) { reveal(matcher); compose.onNode(matcher).performClick() }

    /** 遍历当前页面的实际滚动列，兼容手机单列和平板两栏，不修改设备尺寸或字号。 */
    private fun reveal(matcher: SemanticsMatcher) {
        hideKeyboardOnUiThread()
        try { compose.onNode(matcher).assertIsDisplayed(); return } catch (_: AssertionError) { }
        val count = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        var failure: AssertionError? = null
        for (index in 0 until count) {
            try {
                compose.onAllNodes(hasScrollToIndexAction())[index].performScrollToNode(matcher)
                compose.onNode(matcher).performScrollTo().assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw failure ?: AssertionError("当前窗口找不到多密钥界面内容：$matcher")
    }

    /** 保存当前设备完整窗口，文件名包含实际窗口尺寸，供主代理分别在手机和平板验收。 */
    private fun screenshot(label: String) {
        hideKeyboardOnUiThread()
        compose.waitForIdle()
        Thread.sleep(350L)
        awaitNativeIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("多密钥界面验收")) {
            "无法取得多密钥界面验收输出目录"
        }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建多密钥截图目录：$directory" }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法截取当前完整设备窗口" }
        try {
            val output = File(directory, "$label-${bitmap.width}x${bitmap.height}.png")
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存多密钥界面截图" } }
            android.util.Log.i("多密钥界面验收", "实际窗口截图已保存：${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
}
