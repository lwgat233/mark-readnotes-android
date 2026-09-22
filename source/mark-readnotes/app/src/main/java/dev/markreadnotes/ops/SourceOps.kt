package dev.markreadnotes.ops

import dev.markreadnotes.notes.NotesRepo
import org.json.JSONObject

/** src.* / idx.* —— 目录授权与索引（懒扫描规则住在这里） */
object SourceOps {
    fun handle(op: String, a: JSONObject, notes: NotesRepo, pickTree: () -> Unit, pickExport: () -> Unit): Any = when (op) {
        "src.pick" -> {
            pickTree()
            JSONObject().put("pending", true)
        }

        "src.pickExport" -> {
            pickExport()
            JSONObject().put("pending", true)
        }

        "src.get", "idx.stats" -> notes.sourceJson()

        "idx.refresh" -> {
            val (files, blocks) = notes.refresh(true)
            JSONObject().put("files", files).put("blocks", blocks)
        }

        "idx.blocks" -> notes.recentBlocks(a.optInt("limit", 40))

        "idx.files" -> notes.filesJson()

        else -> throw IllegalArgumentException("未知 op：$op")
    }
}
