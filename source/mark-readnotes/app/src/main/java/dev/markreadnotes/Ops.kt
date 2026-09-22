package dev.markreadnotes

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    private val pickTree: () -> Unit
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

        else -> throw IllegalArgumentException("未知 op：$op")
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
