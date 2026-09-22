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

    /** 随笔板块自己的目录名（用户 2026-09-22：随笔单独一个目录，与别的板块隔离） */
    companion object {
        const val NODE = "随笔"
        const val DIR_MIME = "vnd.android.document/directory"
    }

    /** 随笔目录（授权根下面与别的板块平级的那一层）；找不到时按需要新建 */
    fun node(create: Boolean = true): Saf.Doc? {
        val r = root() ?: return null
        return saf.findPath(r.treeUri, r.rootDocId, NODE) ?: if (create) saf.ensurePath(r.treeUri, r.rootDocId, NODE) else null
    }

    /** 随笔目录在授权根下的相对路径（显示与判据都用这一份口径） */
    fun nodeLabel(): String = "${root()?.rootName ?: "?"}/mark-readnotes/$NODE"

    fun tagList(row: BlockRow): List<String> =
        row.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(row.tag) }
}
