package dev.markreadnotes.ops

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.markreadnotes.Logs
import org.json.JSONObject

/** ui.* —— 交给系统的动作（现在只有“打开链接”；分享在 ExportOps 里） */
object UiOps {
    fun handle(op: String, a: JSONObject, ctx: Context): Any = when (op) {
        "ui.open" -> {
            openUrl(ctx, a.optString("url"))
            JSONObject().put("opened", true)
        }
        else -> throw IllegalArgumentException("未知 op：$op")
    }

    private fun openUrl(ctx: Context, url: String) {
        if (url.isBlank()) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Logs.i("ui.open url=${url.take(120)}")
        } catch (e: Exception) {
            Logs.e("ui.open failed url=${url.take(80)} err=${e.message}")
        }
    }
}
