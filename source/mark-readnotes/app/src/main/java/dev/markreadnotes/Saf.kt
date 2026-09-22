package dev.markreadnotes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** SAF（存储访问框架）读写：授权一次，长期使用；所有读写都走这里，别处不许碰 ContentResolver 的文件接口 */
class Saf(private val ctx: Context) {

    data class Doc(val docId: String, val name: String, val mime: String, val size: Long, val mtime: Long)

    fun treeDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    fun docIdOf(uri: Uri): String = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) {
        uri.lastPathSegment ?: ""
    }

    private val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    fun children(tree: Uri, parentDocId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Doc>()
        try {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4)))
                }
            }
        } catch (e: Exception) {
            Logs.e("saf.children failed doc=$parentDocId err=${e.message}")
        }
        return out
    }

    fun stat(tree: Uri, docId: String): Doc? = try {
        ctx.contentResolver.query(docUri(tree, docId), cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4)) else null
        }
    } catch (e: Exception) {
        null
    }

    /** 找/建同名子目录；目录名固定英文（用户要求：目录不要用中文） */
    fun ensureDir(tree: Uri, parentDocId: String, name: String): Doc? {
        children(tree, parentDocId).firstOrNull { it.name == name && it.mime == DocumentsContract.Document.MIME_TYPE_DIR }?.let { return it }
        return try {
            val created = DocumentsContract.createDocument(
                ctx.contentResolver, docUri(tree, parentDocId), DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: return null
            Logs.i("saf.mkdir name=$name")
            stat(tree, docIdOf(created))
        } catch (e: Exception) {
            Logs.e("saf.mkdir failed name=$name err=${e.message}")
            null
        }
    }

    /** 按相对路径找目录（只找不建）；"a/b" 逐级往下，任一级不存在就 null */
    fun findPath(tree: Uri, parentDocId: String, relDir: String): Doc? {
        val parts = splitRel(relDir) ?: return null
        if (parts.isEmpty()) return stat(tree, parentDocId)
        var cur = parentDocId
        for (p in parts) {
            val hit = children(tree, cur).firstOrNull { it.name == p && it.mime == DocumentsContract.Document.MIME_TYPE_DIR } ?: return null
            cur = hit.docId
        }
        return stat(tree, cur)
    }

    /** 按相对路径逐级找/建目录（一层层往下；拒绝 .. 与绝对路径） */
    fun ensurePath(tree: Uri, parentDocId: String, relDir: String): Doc? {
        val parts = splitRel(relDir) ?: return null
        var cur = stat(tree, parentDocId) ?: return null
        for (p in parts) {
            val hit = children(tree, cur.docId).firstOrNull { it.name == p && it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
            cur = hit ?: (ensureDir(tree, cur.docId, p) ?: return null)
        }
        return cur
    }

    /** 相对路径切分：去空段、挡住 .. 与反斜杠；非法返回 null */
    private fun splitRel(rel: String): List<String>? {
        val parts = rel.replace("\\", "/").split("/").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.any { it == ".." || it == "." }) {
            Logs.e("saf.path reject rel=${rel.take(80)}")
            return null
        }
        return parts
    }

    fun createMd(tree: Uri, parentDocId: String, name: String): Doc? = try {
        val created = DocumentsContract.createDocument(ctx.contentResolver, docUri(tree, parentDocId), "text/markdown", name)
        if (created == null) null else stat(tree, docIdOf(created))
    } catch (e: Exception) {
        Logs.e("saf.create failed name=$name err=${e.message}")
        null
    }

    fun readText(tree: Uri, docId: String): String {
        ctx.contentResolver.openInputStream(docUri(tree, docId))?.use { return it.readBytes().toString(Charsets.UTF_8) }
        throw IllegalStateException("读不到文件：$docId")
    }

    /** 整文件重写（SAF 没有 rename/原子替换，块级编辑统一用“读全部块 → 改 → 整文件写回”） */
    fun writeText(tree: Uri, docId: String, text: String) {
        ctx.contentResolver.openOutputStream(docUri(tree, docId), "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        } ?: throw IllegalStateException("写不进文件：$docId")
    }

    fun delete(tree: Uri, docId: String): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, docUri(tree, docId))
    } catch (e: Exception) {
        Logs.e("saf.delete failed doc=$docId err=${e.message}")
        false
    }
}
