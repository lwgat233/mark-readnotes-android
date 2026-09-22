package dev.markreadnotes.ops

import dev.markreadnotes.notes.NotesRepo
import org.json.JSONObject

/** src.* / idx.* —— 目录授权与索引（懒扫描规则住在这里） */
object SourceOps {
    fun handle(op: String, a: JSONObject, notes: NotesRepo, pickTree: () -> Unit, pickExport: () -> Unit): Any = when (op) {
        "src.pick", "src.pickExport" -> {
            if (op == "src.pick") pickTree() else pickExport()
            JSONObject().put("pending", true)
        }

        "src.mkdir" -> notes.mkdir(a.optString("folder"))

        "src.setTagFolder" -> notes.setTagFolder(a.optString("tag"), a.optString("folder"))

        "src.migrate" -> notes.migrateLegacy()

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
