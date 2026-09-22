package dev.markreadnotes.export

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import dev.markreadnotes.BlockRow
import dev.markreadnotes.Logs
import dev.markreadnotes.Store
import dev.markreadnotes.Store.Root

/** 导出板块：目录层级＝标签顺序、排除标签、预览、写盘、分享准备。 */
class ExportRepo(private val store: Store) {
    private val ctx get() = store.ctx
    private val db get() = store.db
    private val saf get() = store.saf
    fun root(): Root? = store.root()
    private fun tagList(row: BlockRow): List<String> = store.tagList(row)

    // ---------------------- 导出（按标签） ----------------------

    data class ExpFile(val dirs: List<String>, val name: String, val blockId: Long, val heading: String, val tag: String, val raw: String)

    /** 目录段/文件名都取下原文（用户定：目录名就是标签，文件名就是标题），只挡掉路径非法字符 */
    private fun safeSeg(s: String, max: Int): String =
        s.trim().replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").take(max).ifBlank { "untitled" }

    fun tagStats(): JSONArray {
        val map = LinkedHashMap<String, Int>()
        for (b in db.allBlocks()) for (t in tagList(b)) map[t] = (map[t] ?: 0) + 1
        val arr = JSONArray()
        for ((t, n) in map.entries.sortedByDescending { it.value }) arr.put(JSONObject().put("tag", t).put("count", n))
        return arr
    }

    /** 规则：第1个标签=一级目录，第2个=二级目录（第3个及以后不参与建目录，标签仍留在文件里）；
     *  带任一排除标签的块整体不导出；文件名取一级标题，同级重名加 -2、-3 */
    fun exportFiles(exclude: List<String>): List<ExpFile> {
        val ex = exclude.map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.toSet()
        val out = ArrayList<ExpFile>()
        val used = HashMap<String, Int>()
        for (b in db.allBlocks()) {
            val tags = tagList(b)
            if (tags.any { it in ex }) continue
            val dirs = tags.take(2).map { safeSeg(it, 40) }
            val base = safeSeg(b.heading, 60)
            val key = dirs.joinToString("/") + "/" + base
            val n = (used[key] ?: 0) + 1
            used[key] = n
            out.add(ExpFile(dirs, if (n == 1) "$base.md" else "$base-$n.md", b.id, b.heading, b.tag, b.raw))
        }
        return out
    }

    @Synchronized
    fun exportPlan(exclude: List<String>): JSONObject {
        val files = exportFiles(exclude)
        val groups = LinkedHashMap<String, JSONArray>()
        for (f in files) {
            val d = if (f.dirs.isEmpty()) "." else f.dirs.joinToString("/")
            val arr = groups.getOrPut(d) { JSONArray() }
            arr.put(JSONObject().put("name", f.name).put("heading", f.heading).put("tag", f.tag).put("blockId", f.blockId))
        }
        val tree = JSONArray()
        for ((d, arr) in groups) tree.put(JSONObject().put("dir", d).put("count", arr.length()).put("files", arr))
        val total = db.allBlocks().size
        Logs.i("exp.plan(files=${files.size},blocks=$total,excluded=${total - files.size})")
        return JSONObject()
            .put("tree", tree).put("files", files.size).put("blocks", total)
            .put("excluded", total - files.size).put("dirs", groups.size)
            .put("exportRoot", db.metaGet("export_root_label") ?: "")
    }

    /** 用户选定导出目录后：在其中建/复用 mark-readnotes-export 并记住 */
    @Synchronized
    fun attachExportTree(tree: Uri): JSONObject {
        val picked = saf.stat(tree, saf.treeDocId(tree))?.name ?: "picked"
        val dir = saf.ensureDir(tree, saf.treeDocId(tree), "mark-readnotes-export")
            ?: throw IllegalStateException("无法在该目录里创建 mark-readnotes-export")
        val label = "$picked/mark-readnotes-export"
        db.metaPut("export_tree_uri", tree.toString())
        db.metaPut("export_root_doc_id", dir.docId)
        db.metaPut("export_root_label", label)
        Logs.i("exp.attach(picked=$picked,root=${dir.docId})")
        return JSONObject().put("treeUri", tree.toString()).put("pickedName", picked).put("rootLabel", label)
    }

    /** 真写盘：目录按标签逐级建，同名文件覆盖（改过的块再导一次就是更新） */
    @Synchronized
    fun writeExport(exclude: List<String>): JSONObject {
        val files = exportFiles(exclude)
        if (files.isEmpty()) throw IllegalStateException("没有可导出的块")
        val treeStr = db.metaGet("export_tree_uri") ?: throw IllegalStateException("还没选导出目录")
        val tree = Uri.parse(treeStr)
        val cachedRoot = db.metaGet("export_root_doc_id")
        val rootDocId: String = if (cachedRoot.isNullOrBlank() || saf.stat(tree, cachedRoot) == null) {
            val dir = saf.ensureDir(tree, saf.treeDocId(tree), "mark-readnotes-export")
                ?: throw IllegalStateException("无法在所选目录里创建 mark-readnotes-export")
            db.metaPut("export_root_doc_id", dir.docId)
            dir.docId
        } else cachedRoot
        val t0 = System.currentTimeMillis()
        val dirCache = HashMap<String, String>()
        var created = 0
        var updated = 0
        for (f in files) {
            var parentId = rootDocId
            val prefix = StringBuilder()
            for (seg in f.dirs) {
                prefix.append(if (prefix.isEmpty()) seg else "/$seg")
                val k = prefix.toString()
                parentId = dirCache[k] ?: run {
                    val d = saf.ensureDir(tree, parentId, seg) ?: throw IllegalStateException("建不了目录：$k")
                    dirCache[k] = d.docId
                    d.docId
                }
            }
            val existing = saf.children(tree, parentId).firstOrNull { it.name == f.name && it.mime != "vnd.android.document/directory" }
            if (existing != null) {
                saf.writeText(tree, existing.docId, f.raw)
                updated++
            } else {
                val made = saf.createMd(tree, parentId, f.name) ?: throw IllegalStateException("建不了文件：${f.name}")
                saf.writeText(tree, made.docId, f.raw)
                created++
            }
        }
        val label = db.metaGet("export_root_label") ?: "mark-readnotes-export"
        Logs.i("exp.write(dir=$label,files=${files.size},new=$created,over=$updated,dirs=${dirCache.size},ms=${System.currentTimeMillis() - t0})")
        return JSONObject().put("root", label).put("files", files.size).put("new", created)
            .put("overwritten", updated).put("dirs", dirCache.size)
    }

    /** 准备分享：先把文件生成到应用缓存（分享完由系统按一次性授权读取） */
    @Synchronized
    fun shareFiles(exclude: List<String>): List<java.io.File> {
        val files = exportFiles(exclude)
        if (files.isEmpty()) throw IllegalStateException("没有可分享的块")
        val base = java.io.File(ctx.cacheDir, "share")
        if (base.exists()) base.deleteRecursively()
        base.mkdirs()
        val out = ArrayList<java.io.File>()
        for (f in files) {
            val d = java.io.File(base, f.dirs.joinToString(java.io.File.separator))
            d.mkdirs()
            val file = java.io.File(d, f.name)
            file.writeText(f.raw, Charsets.UTF_8)
            out.add(file)
        }
        Logs.i("exp.prepare(files=${out.size},dir=${base.absolutePath})")
        return out
    }

}
