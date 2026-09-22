package dev.markreadnotes.ops

import dev.markreadnotes.notes.NotesRepo
import org.json.JSONObject

/** blk.* —— 随笔本体的读写：取、存（含改标签搬家）、新建、删除 */
object BlockOps {
    fun handle(op: String, a: JSONObject, notes: NotesRepo): Any = when (op) {
        "blk.get" -> notes.getBlock(a.getLong("id"))
        "blk.save" -> notes.saveBlock(
            a.getLong("id"), a.optString("heading"),
            if (a.has("tags")) JsonArgs.tags(a, "tags") else JsonArgs.tags(a, "tag"),
            a.optString("body")
        )
        "blk.new" -> notes.newBlock(a.optString("tag"), a.optString("heading"), a.optString("body"))
        "blk.delete" -> notes.deleteBlock(a.getLong("id"))
        else -> throw IllegalArgumentException("未知 op：$op")
    }
}
