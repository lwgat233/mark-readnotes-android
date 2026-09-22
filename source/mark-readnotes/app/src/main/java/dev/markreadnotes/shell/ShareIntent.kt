package dev.markreadnotes.shell

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.markreadnotes.Logs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导出给别人：把缓存里生成的 md 经 FileProvider 一次性授权交出去（不需要任何存储权限）。
 * 判据（验收读回来）：返回里的 uris/action/targets + 日志里同一行 + 前台变成系统选择器。
 */
object ShareIntent {
    fun share(ctx: Context, files: List<java.io.File>): JSONObject {
        if (files.isEmpty()) throw IllegalStateException("没有可分享的块")
        val uris = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = "text/markdown"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val targets = try {
            ctx.packageManager.queryIntentActivities(send, 0).map { it.activityInfo.packageName }.distinct()
        } catch (e: Exception) {
            emptyList<String>()
        }
        Logs.i("exp.share(uris=${uris.size},action=${send.action},target=${targets.joinToString(",")})")
        ctx.startActivity(Intent.createChooser(send, "分享随笔").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("uris", uris.size).put("action", send.action).put("targets", JSONArray(targets))
    }
}
