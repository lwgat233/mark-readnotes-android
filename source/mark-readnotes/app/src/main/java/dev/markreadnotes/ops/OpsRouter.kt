package dev.markreadnotes.ops

import android.content.Context
import dev.markreadnotes.Logs
import dev.markreadnotes.notes.NotesRepo
import dev.markreadnotes.export.ExportRepo
import dev.markreadnotes.sync.SyncRepo
import org.json.JSONObject

/**
 * op 层入口：一件事一个 op，界面与（后续的）外部控制口都走这一套 ——
 * 这样“界面上验过的＝接口也验过的”。这里只做分发，一个板块一个文件：
 *   app.*            AppOps
 *   src.* / idx.*     SourceOps（授权与索引）
 *   blk.*             BlockOps（块读写＝随笔本体）
 *   exp.*             ExportOps（导出）
 *   ui.*              UiOps（交给系统的动作）
 *
 * 请求 {"id":"r1","op":"idx.blocks","args":{...}}
 * 回包 {"id":"r1","ok":true,"data":...} / {"id":"r1","ok":false,"error":"..."}
 */
class OpsRouter(
    private val ctx: Context,
    private val notes: NotesRepo,
    private val export: ExportRepo,
    private val sync: SyncRepo,
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

    private fun route(op: String, args: JSONObject): Any = when (op.substringBefore('.')) {
        "app" -> AppOps.handle(op, args, buildTag)
        "src", "idx" -> SourceOps.handle(op, args, notes, pickTree, pickExport)
        "blk" -> BlockOps.handle(op, args, notes)
        "exp" -> ExportOps.handle(op, args, export, ctx)
        "sync" -> SyncOps.handle(op, args, sync)
        "ui" -> UiOps.handle(op, args, ctx)
        else -> throw IllegalArgumentException("未知 op：$op")
    }
}
