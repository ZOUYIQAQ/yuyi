package io.github.korenkrita.simulsub.updates

import android.content.Intent
import android.net.Uri

/** 浏览器选择器避免GitHub应用接管下载链接，原始动作和地址仍完整交给浏览器。 */
internal fun updateBrowserIntent(url: String): Intent =
    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(url)
        removeCategory(Intent.CATEGORY_LAUNCHER)
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
