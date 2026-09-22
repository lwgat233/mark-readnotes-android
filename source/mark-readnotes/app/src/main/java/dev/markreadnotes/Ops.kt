package dev.markreadnotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * op 层：一件事一个 op，界面与（后续的）外部控制口都走这一套，保证“界面上验过的＝接口也验过的”。
 * 请求 {"id":"r1","op":"idx.blocks","args":{...}}
 * 回包 {"id":"r1","ok":true,"data":...} / {"id":"r1","ok":false,"error":"..."}
 */
class Ops(
    private val ctx: Context,
    private val repo: Repo,
    private val buildTag: String,
    private val pickTree: () -> Unit,
    private val pickExport: () -> Unit
) {

    @Synchronized
    fun dispatch(raw: String): String {
        var id = ""
        return try {
            val req = JSONObject(raw)
            id = req.optString("id")
            val op = req.optString("op")
            val args = req.optJSONObject("args") ?: JSONObject()
            val data = route(op, args)
            Logs.i("op=$op")
            JSONObject().put("id", id).put("ok", true).put("data", data).toString()
        } catch (e: Throwable) {
            Logs.e("op=$id failed err=${e.message}")
            JSONObject().put("id", id).put("ok", false).put("error", e.message ?: e.toString()).toString()
        }
    }

    private fun route(op: String, a: JSONObject): Any = when (op) {
        "app.info" -> JSONObject()
            .put("name", "随笔").put("version", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE).put("build", buildTag)

        "app.log" -> Logs.tail(a.optInt("n", 60))

        "src.pick" -> {
            pickTree()
            JSONObject().put("pending", true)
        }

        "src.get" -> repo.sourceJson()
        "idx.stats" -> repo.sourceJson()

        "idx.refresh" -> {
            val (files, blocks) = repo.refresh(true)
            JSONObject().put("files", files).put("blocks", blocks)
        }

        "idx.blocks" -> repo.recentBlocks(a.optInt("limit", 40))
        "idx.files" -> repo.filesJson()

        "blk.get" -> repo.getBlock(a.getLong("id"))
        "blk.save" -> repo.saveBlock(a.getLong("id"), a.optString("heading"), tagsOf(a), a.optString("body"))
        "blk.new" -> repo.newBlock(a.optString("tag"), a.optString("heading"), a.optString("body"))
        "blk.delete" -> repo.deleteBlock(a.getLong("id"))

        "ui.open" -> {
            openUrl(a.optString("url"))
            JSONObject().put("opened", true)
        }

        // ---------- 导出（按标签） ----------
        "exp.tags" -> repo.tagStats()
        "exp.plan" -> repo.exportPlan(tagsFrom(a, "exclude"))
        "exp.writeDir" -> repo.writeExport(tagsFrom(a, "exclude"))
        "exp.share" -> share(repo.shareFiles(tagsFrom(a, "exclude")))
        "src.pickExport" -> {
            pickExport()
            JSONObject().put("pending", true)
        }

        else -> throw IllegalArgumentException("未知 op：$op")
    }

    /** 交给系统分享（FileProvider 一次性授权；没装能接收的应用时如实回报空目标） */
    private fun share(files: List<java.io.File>): JSONObject {
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
            emptyList()
        }
        Logs.i("exp.share(uris=${uris.size},action=${send.action},target=${targets.joinToString(",")})")
        ctx.startActivity(Intent.createChooser(send, "分享随笔").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("uris", uris.size).put("action", send.action).put("targets", JSONArray(targets))
    }

    private fun tagsFrom(a: JSONObject, key: String): List<String> {
        val arr: JSONArray? = a.optJSONArray(key)
        if (arr != null) {
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            return out
        }
        return a.optString(key).split(" ", ",").filter { it.isNotBlank() }
    }

    private fun tagsOf(a: JSONObject): List<String> {
        val arr: JSONArray? = a.optJSONArray("tags")
        if (arr != null) {
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            return out
        }
        return a.optString("tag").split(" ", ",").filter { it.isNotBlank() }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Logs.i("ui.open url=${url.take(120)}")
        } catch (e: Exception) {
            Logs.e("ui.open failed url=${url.take(80)} err=${e.message}")
        }
    }
}
