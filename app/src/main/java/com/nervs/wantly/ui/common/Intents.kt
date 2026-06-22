package com.nervs.wantly.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Открывает ссылку во внешнем браузере; молча игнорирует невалидные/отсутствующие URL. */
fun openUrl(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
