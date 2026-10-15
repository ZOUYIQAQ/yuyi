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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.korenkrita.simulsub.models.ModelRepository
import io.github.korenkrita.simulsub.ui.PermissionUiState
import io.github.korenkrita.simulsub.ui.SimulSubApp
import java.io.File

/** 安卓入口只处理系统能力，页面由 Compose 渲染，业务状态保留在 ViewModel。 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = ViewModelProvider(this)[AppController::class.java]
        refreshPermissions()
        setContent {
            val state = controller.state.collectAsStateWithLifecycle().value
            SimulSubApp(state, controller.actions(::beginCapture, ::requestPermission, ::shareRecord, ::openUrl))
        }
    }

    /** 从系统权限页返回时重新读取实际状态。 */
    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) refreshPermissions()
    }

    /** 读取必要授权，通知权限为可选，拒绝后仍可手动使用字幕。 */
    private fun refreshPermissions() {
        controller.setPermissions(listOf(
            PermissionUiState("audio", "录制播放音频", "仅识别应用播放的媒体声音；系统会在开始时再次询问捕获范围。", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            PermissionUiState("overlay", "显示悬浮字幕", "允许字幕显示在播放器、课程或直播应用上方。", Settings.canDrawOverlays(this)),
            PermissionUiState("notifications", "下载与运行通知", "显示模型下载进度和字幕服务控制。", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, false),
        ))
    }

    /** 所有系统授权均由明确的用户操作发起。 */
    private fun requestPermission(id: String) {
        when (id) {
            "audio" -> permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            "overlay" -> overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            "notifications" -> if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    /** 模型和翻译配置完整后申请本次会话的播放捕获授权。 */
    private fun beginCapture() {
        if (CaptionService.running) return
        if (ModelRepository.get(this).selectedPaths() == null) { controller.message("请先下载并启用识别模型", true); return }
        if (!Prefs(this).byokReady) { controller.message("请先保存翻译服务地址和模型 ID", true); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            continueAfterAudioPermission = true
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
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
}
