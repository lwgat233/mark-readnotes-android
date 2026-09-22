package dev.markreadnotes

import android.content.Context
import android.net.Uri

/**
 * 随笔的公共底层：一份 SQLite 句柄、一份 SAF 句柄、笔记根的读法。
 * 上层按板块各自持有它：notes 包的 NotesRepo、export 包的 ExportRepo、ops 包的各操作、shell 包的壳与选择器。
 * 一份数据一个权威源：正文在 md 文件、索引在 SQLite，这里只是句柄的入口。
 */
class Store(val ctx: Context) {
    val db = Db(ctx)
    val saf = Saf(ctx)

    data class Root(val treeUri: Uri, val rootDocId: String, val rootName: String)

    fun root(): Root? {
        val s = db.source() ?: return null
        return Root(Uri.parse(s.treeUri), s.rootDocId, s.rootName)
    }

    fun tagList(row: BlockRow): List<String> =
        row.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(row.tag) }
}
