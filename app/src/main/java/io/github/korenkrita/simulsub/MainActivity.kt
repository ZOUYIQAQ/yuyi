package io.github.korenkrita.simulsub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.korenkrita.simulsub.models.ModelRepository
import io.github.korenkrita.simulsub.ui.PermissionUiState
import io.github.korenkrita.simulsub.ui.SimulSubApp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** 安卓入口只处理系统能力，页面由 Compose 渲染，业务状态保留在 ViewModel。 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController
    private lateinit var splashTheme: SplashTheme
    private var continueAfterAudioPermission = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        if (continueAfterAudioPermission && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            continueAfterAudioPermission = false
            beginCapture()
        } else continueAfterAudioPermission = false
    }
    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPermissions() }
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            runCatching { CaptionService.start(this, result.resultCode, result.data!!) }
                .onSuccess { moveTaskToBack(true) }
                .onFailure { controller.message("系统未能启动字幕服务：${it.message}", true) }
        } else controller.message("录制播放音频的授权已取消")
    }

    /** 创建页面，使用系统边缘到边缘布局，窗口变化时由 Compose 自动重排。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        splashTheme = SplashTheme(this)
        splashTheme.prepare()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = ViewModelProvider(this)[AppController::class.java]
        splashTheme.synchronize(SplashTheme.Appearance.from(controller.state.value.settings))
        observeSplashTheme()
        controller.restoreLauncherIcon()
        refreshPermissions()
        setContent {
            val state = controller.state.collectAsStateWithLifecycle().value
            SimulSubApp(state, controller.actions(::beginCapture, ::requestPermission, ::shareRecord, ::openUrl, ::openUpdateUrl))
        }
    }

    /** 从系统权限页返回时重新读取实际状态。 */
    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) { controller.restoreLauncherIcon(); refreshPermissions(); controller.onAppStarted() }
        if (::splashTheme.isInitialized && ::controller.isInitialized) {
            splashTheme.synchronize(SplashTheme.Appearance.from(controller.state.value.settings))
        }
    }

    /** 只观察既有主题输入，设置变化后立即更新下次启动背景，离开页面后停止收集。 */
    private fun observeSplashTheme() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.map { SplashTheme.Appearance.from(it.settings) }.distinctUntilChanged()
                    .collect { splashTheme.synchronize(it) }
            }
        }
    }

    /** 读取必要授权，通知权限为可选，拒绝后仍可手动使用字幕。 */
    private fun refreshPermissions() {
        controller.setPermissions(listOf(
            PermissionUiState("audio", "录制播放音频", "仅识别应用播放的媒体声音；系统会在开始时再次询问捕获范围。", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            PermissionUiState("overlay", "显示悬浮字幕", "允许字幕显示在播放器、课程或直播应用上方。", Settings.canDrawOverlays(this)),
            PermissionUiState("notifications", "下载与运行通知", "显示模型下载进度和字幕服务控制。", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, false),
        ))
    }

    /** 用户点击后申请权限；已授权的音频和通知打开应用详情，悬浮字幕始终打开专项设置。 */
    private fun requestPermission(id: String) {
        val permission = when (id) {
            "audio" -> Manifest.permission.RECORD_AUDIO
            "notifications" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
            else -> null
        }
        if (id == "overlay") {
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (id != "audio" && id != "notifications") return
        val requests = getSharedPreferences("permission_requests", MODE_PRIVATE)
        val granted = permission == null || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val permanentlyDenied = permission != null && requests.getBoolean(id, false) && !shouldShowRequestPermissionRationale(permission)
        if (granted || permanentlyDenied) {
            continueAfterAudioPermission = false
            overlayLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else if (permission != null) {
            requests.edit().putBoolean(id, true).apply()
            permissionLauncher.launch(arrayOf(permission))
        }
    }

    /** 模型和翻译配置完整后申请本次会话的播放捕获授权。 */
    private fun beginCapture() {
        if (CaptionService.running) return
        if (ModelRepository.get(this).selectedPaths() == null) { controller.message("请先下载并启用识别模型", true); return }
        if (!Prefs(this).byokReady) { controller.message("请先保存翻译服务地址和模型 ID", true); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            continueAfterAudioPermission = true
            requestPermission("audio")
            return
        }
        if (!Settings.canDrawOverlays(this)) { requestPermission("overlay"); return }
        captureLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    /** 仅分享用户选择的日志，使用限定路径的 FileProvider 授权。 */
    private fun shareRecord(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.logs", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/x-ndjson")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享会话记录"))
        }.onFailure { controller.message("分享失败：${it.message}", true) }
    }

    /** 由页面明确点击后打开可信项目或模型来源链接。 */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { controller.message("没有可打开链接的应用", true) }
    }
    /** 更新明确交给浏览器打开，失败时只显示提示，不调用应用内安装器。 */
    private fun openUpdateUrl(url: String) {
        runCatching { startActivity(io.github.korenkrita.simulsub.updates.updateBrowserIntent(url)) }
            .onFailure { controller.message("没有可用的浏览器，请先安装或启用浏览器", true) }
    }

}
