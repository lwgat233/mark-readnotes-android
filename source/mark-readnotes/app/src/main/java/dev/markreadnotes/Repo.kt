package dev.markreadnotes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 随笔的读写中枢。唯一权威源：正文在 md 文件里，索引在 SQLite 里。
 * 写入路径固定一条：改文件 → 立刻重建该文件的索引行。
 */
class Repo(private val ctx: Context) {

    private val db = Db(ctx)
    private val saf = Saf(ctx)

    data class Root(val treeUri: Uri, val rootDocId: String, val rootName: String)

    private data class BData(val heading: String, val tags: List<String>, val body: String) {
        val raw: String get() = Md.renderBlock(heading, tags, body)
    }

    fun root(): Root? {
        val s = db.source() ?: return null
        return Root(Uri.parse(s.treeUri), s.rootDocId, s.rootName)
    }

    // ---------------------- 目录授权 ----------------------

    /** 用户选定目录后：在其下建/复用 mark-readnotes 子目录并做一次初始枚举（此后不再自动扫目录） */
    @Synchronized
    fun attachTree(tree: Uri): JSONObject {
        val treeDocId = saf.treeDocId(tree)
        val pickedName = saf.stat(tree, treeDocId)?.name ?: "picked"
        val dir = saf.ensureDir(tree, treeDocId, "mark-readnotes")
            ?: throw IllegalStateException("无法在该目录下创建 mark-readnotes 子目录")
        val old = db.source()?.treeUri
        db.setSource(tree.toString(), dir.docId, pickedName, "saf")
        if (old != null && old != tree.toString()) {
            // 换目录：旧索引不再指向有效文件，整表重建（永久索引只针对当前来源）
            db.writableDatabase.execSQL("DELETE FROM block")
            db.writableDatabase.execSQL("DELETE FROM note_file")
        }
        Logs.i("src.attach picked=$pickedName rootDocId=${dir.docId}")
        val (files, blocks) = refresh(true)
        return JSONObject()
            .put("treeUri", tree.toString())
            .put("pickedName", pickedName)
            .put("rootDocId", dir.docId)
            .put("files", files)
            .put("blocks", blocks)
    }

    fun sourceJson(): JSONObject {
        val s = db.source() ?: return JSONObject().put("has", false)
        val (files, blocks, scan) = db.stats()
        return JSONObject()
            .put("has", true)
            .put("treeUri", s.treeUri)
            .put("pickedName", s.rootName)
            .put("rootLabel", "${s.rootName}/mark-readnotes")
            .put("exportRoot", db.metaGet("export_root_label") ?: "")
            .put("files", files)
            .put("blocks", blocks)
            .put("lastScanAt", scan)
    }

    // ---------------------- 扫描与索引 ----------------------

    /** 目录枚举：只有首次授权与用户主动刷新才走这里（懒扫描） */
    @Synchronized
    fun refresh(full: Boolean): Pair<Int, Int> {
        val root = root() ?: return 0 to 0
        val src = db.source() ?: return 0 to 0
        val t0 = System.currentTimeMillis()
        val kids = saf.children(root.treeUri, root.rootDocId)
            .filter { it.mime != "vnd.android.document/directory" && it.name.lowercase().endsWith(".md") }
        var changed = 0
        val seen = HashSet<String>()
        for (c in kids) {
            val uri = saf.docUri(root.treeUri, c.docId).toString()
            seen.add(uri)
            val row = db.fileByDocUri(uri)
            val stale = row == null || full || row.mtime != c.mtime || row.size != c.size
            val fid = db.upsertFile(src.id, uri, c.docId, c.name, c.name.removeSuffix(".md"), c.size, c.mtime)
            if (stale) {
                loadFile(db.fileById(fid)!!, root, c.mtime, c.size)
                changed++
            }
        }
        for (f in db.allFiles()) if (f.docUri !in seen) db.setFilePresent(f.id, 0)
        db.touchScan(System.currentTimeMillis())
        Logs.i("scan=walk(files=${kids.size},changed=$changed,ms=${System.currentTimeMillis() - t0})")
        return kids.size to db.stats().second
    }

    /** 单文件懒校验：打开某块之前才 stat 它所属的文件 */
    private fun lazyCheck(file: FileRow, root: Root): Boolean {
        val doc = saf.stat(root.treeUri, file.docId)
        if (doc == null) {
            db.setFilePresent(file.id, 0)
            Logs.i("scan=single(file=${file.name},state=offline)")
            return false
        }
        if (doc.mtime == file.mtime && doc.size == file.size) {
            Logs.i("scan=single(file=${file.name},state=unchanged)")
            return false
        }
        Logs.i("scan=single(file=${file.name},state=changed)")
        loadFile(file, root, doc.mtime, doc.size)
        return true
    }

    /** 解析一个文件并把块写进索引；已有块按 raw → heading → 序号依次认领，保住 id 与“最近编辑”顺序 */
    private fun loadFile(
        file: FileRow, root: Root, mtime: Long, size: Long,
        preferId: Long? = null, preferIndex: Int? = null, touchId: Long? = null
    ): Int {
        val text = try {
            saf.readText(root.treeUri, file.docId)
        } catch (e: Exception) {
            Logs.e("read failed file=${file.name} err=${e.message}")
            db.setFilePresent(file.id, 0)
            return -1
        }
        val blocks = Md.split(text)
        val existing = db.blocksOfFile(file.id)
        val used = HashSet<Long>()
        val prefer = if (preferId != null) db.blockById(preferId) else null
        val now = System.currentTimeMillis()
        val tagsLine = { t: List<String> -> t.joinToString(",") }
        for (b in blocks) {
            var m: BlockRow? = null
            if (prefer != null && prefer.id !in used && b.index == (preferIndex ?: -1)) m = prefer
            if (m == null) m = existing.firstOrNull { it.id !in used && it.raw.trim() == b.raw.trim() }
            if (m == null) m = existing.firstOrNull { it.id !in used && it.heading == b.heading }
            if (m == null) m = existing.firstOrNull { it.id !in used && it.index == b.index }
            if (m != null) {
                used.add(m.id)
                val up = if (m.id == touchId) now else m.updatedAt
                db.updateBlock(m.id, file.id, file.docUri, b.heading, b.tag, tagsLine(b.tags), b.body, b.raw, b.index, mtime, size, up)
            } else {
                db.insertBlock(file.id, file.docUri, b.heading, b.tag, tagsLine(b.tags), b.body, b.raw, b.index, mtime, size, now)
            }
        }
        for (e in existing) if (e.id !in used) db.setBlockPresent(e.id, 0)
        db.setFileMeta(file.id, size, mtime, blocks.firstOrNull()?.tag ?: file.tag)
        return blocks.size
    }

    /** 找/建某个标签对应的 md 文件 */
    private fun ensureFileForTag(root: Root, tag: String): FileRow {
        val src = db.source() ?: throw IllegalStateException("还没有选笔记目录")
        val name = Md.fileNameForTag(tag)
        val existing = db.fileByName(src.id, name)
        if (existing != null) {
            val doc = saf.stat(root.treeUri, existing.docId)
            if (doc != null) {
                if (doc.mtime != existing.mtime || doc.size != existing.size) loadFile(existing, root, doc.mtime, doc.size)
                return db.fileById(existing.id)!!
            }
            db.setFilePresent(existing.id, 0)
        }
        val created = saf.createMd(root.treeUri, root.rootDocId, name)
            ?: throw IllegalStateException("建不了文件：$name")
        val fid = db.upsertFile(src.id, saf.docUri(root.treeUri, created.docId).toString(), created.docId, name, tag, created.size, created.mtime)
        Logs.i("file.create name=$name tag=$tag")
        return db.fileById(fid)!!
    }

    /** 整文件重写：md 文件里块的顺序就是库里的顺序；写完立刻重建该文件索引 */
    private fun rewrite(
        root: Root, file: FileRow, list: List<BData>,
        preferId: Long? = null, preferIndex: Int? = null, touchId: Long? = null
    ) {
        if (list.isEmpty()) {
            saf.delete(root.treeUri, file.docId)
            db.dropFile(file.id)
            Logs.i("file.drop name=${file.name}（块搬空）")
            return
        }
        val text = Md.renderFile(list.mapIndexed { i, b -> MdBlock(b.heading, b.tags, b.body, b.raw, i) })
        saf.writeText(root.treeUri, file.docId, text)
        val doc = saf.stat(root.treeUri, file.docId) ?: throw IllegalStateException("写完读不到：${file.name}")
        loadFile(db.fileById(file.id)!!, root, doc.mtime, doc.size, preferId, preferIndex, touchId)
    }

    private fun tagList(row: BlockRow): List<String> =
        row.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(row.tag) }

    // ---------------------- 读 ----------------------

    fun recentBlocks(limit: Int): JSONArray {
        val arr = JSONArray()
        for (b in db.recentBlocks(limit)) {
            val f = db.fileById(b.fileId)
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("heading", b.heading)
                    .put("tag", b.tag)
                    .put("tags", JSONArray(tagList(b)))
                    .put("preview", preview(b.body))
                    .put("charCount", b.charCount)
                    .put("file", f?.name ?: "?")
                    .put("offline", f != null && f.present == 0)
                    .put("updatedAt", b.updatedAt)
            )
        }
        return arr
    }

    fun filesJson(): JSONArray {
        val arr = JSONArray()
        for (f in db.allFiles()) {
            arr.put(
                JSONObject()
                    .put("id", f.id).put("name", f.name).put("tag", f.tag)
                    .put("blocks", db.blocksOfFile(f.id).size).put("present", f.present)
            )
        }
        return arr
    }

    @Synchronized
    fun getBlock(id: Long): JSONObject {
        val blk = db.blockById(id) ?: throw IllegalStateException("块不存在：$id")
        val file = db.fileById(blk.fileId) ?: throw IllegalStateException("文件行不存在：${blk.fileId}")
        val root = root()
        if (root != null) lazyCheck(file, root)
        val b = db.blockById(id) ?: blk
        val f = db.fileById(b.fileId)
        return JSONObject()
            .put("id", b.id).put("heading", b.heading).put("tag", b.tag)
            .put("tags", JSONArray(tagList(b))).put("body", b.body)
            .put("charCount", b.charCount).put("file", f?.name ?: "?").put("updatedAt", b.updatedAt)
    }

    // ---------------------- 写 ----------------------

    @Synchronized
    fun saveBlock(id: Long, heading: String, tags: List<String>, body: String): JSONObject {
        val root = root() ?: throw IllegalStateException("还没有选笔记目录")
        val blk = db.blockById(id) ?: throw IllegalStateException("块不存在：$id")
        val file = db.fileById(blk.fileId) ?: throw IllegalStateException("文件行不存在")
        lazyCheck(file, root)

        val effTags = tags.map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.ifEmpty { listOf(Md.DEFAULT_TAG) }
        val newTag = effTags.first()
        val newName = Md.fileNameForTag(newTag)
        val edited = BData(heading, effTags, body)
        val rows = db.blocksOfFile(file.id)
        var moved = false

        if (newName == file.name) {
            val list = rows.map { r -> if (r.id == id) edited else BData(r.heading, tagList(r), r.body) }
            val pos = rows.indexOfFirst { it.id == id }.coerceAtLeast(0)
            rewrite(root, file, list, preferId = id, preferIndex = pos, touchId = id)
        } else {
            moved = true
            // 先落到新文件（块的行会改到新文件），再收尾老文件，避免被当成“已删除”
            val target = ensureFileForTag(root, newTag)
            val trows = db.blocksOfFile(target.id)
            val tlist = trows.map { r -> BData(r.heading, tagList(r), r.body) } + edited
            rewrite(root, target, tlist, preferId = id, preferIndex = tlist.size - 1, touchId = id)
            val oldList = rows.filter { it.id != id }.map { r -> BData(r.heading, tagList(r), r.body) }
            rewrite(root, file, oldList)
            Logs.i("block.move id=$id ${file.name} -> ${target.name}")
        }
        val after = db.blockById(id)
        return JSONObject()
            .put("id", id)
            .put("moved", moved)
            .put("file", db.fileById(after?.fileId ?: file.id)?.name ?: "?")
            .put("tag", after?.tag ?: newTag)
    }

    @Synchronized
    fun newBlock(tag: String, heading: String, body: String): JSONObject {
        val root = root() ?: throw IllegalStateException("还没有选笔记目录")
        val tg = tag.trim().removePrefix("#").ifBlank { Md.DEFAULT_TAG }
        val file = ensureFileForTag(root, tg)
        val rows = db.blocksOfFile(file.id)
        val list = rows.map { r -> BData(r.heading, tagList(r), r.body) } + BData(heading, listOf(tg), body)
        rewrite(root, file, list)
        val created = db.blocksOfFile(file.id).lastOrNull { it.raw.trim() == Md.renderBlock(heading, listOf(tg), body).trim() }
        Logs.i("block.new tag=$tg file=${file.name} id=${created?.id}")
        return JSONObject()
            .put("id", created?.id ?: -1)
            .put("file", file.name)
            .put("tag", tg)
    }

    @Synchronized
    fun deleteBlock(id: Long): JSONObject {
        val root = root() ?: throw IllegalStateException("还没有选笔记目录")
        val blk = db.blockById(id) ?: throw IllegalStateException("块不存在：$id")
        val file = db.fileById(blk.fileId) ?: throw IllegalStateException("文件行不存在")
        lazyCheck(file, root)
        val rows = db.blocksOfFile(file.id).filter { it.id != id }
        rewrite(root, file, rows.map { r -> BData(r.heading, tagList(r), r.body) })
        db.deleteBlockRow(id)
        Logs.i("block.delete id=$id")
        return JSONObject().put("id", id).put("deleted", true)
    }

    /** 本地图片（md 里的相对路径）：按笔记根逐级解析，只允许留在根目录内部 */
    fun findImage(rel: String): Saf.Doc? {
        val root = root() ?: return null
        val clean = rel.trim().removePrefix("./").removePrefix("/")
        if (clean.isBlank() || clean.contains("..")) {
            Logs.e("img.reject rel=${rel.take(80)}")
            return null
        }
        var parentId = root.rootDocId
        val parts = clean.split("/")
        for ((i, seg) in parts.withIndex()) {
            val hit = saf.children(root.treeUri, parentId).firstOrNull { it.name == seg } ?: run {
                Logs.e("img.miss rel=$clean at=$seg")
                return null
            }
            if (i == parts.size - 1) {
                Logs.i("img.serve rel=$clean mime=${hit.mime} size=${hit.size}")
                return hit
            }
            parentId = hit.docId
        }
        return null
    }

    /** 取图片字节流（给 WebView 的虚拟源用） */
    fun openImage(docId: String): java.io.InputStream? {
        val root = root() ?: return null
        return try {
            ctx.contentResolver.openInputStream(saf.docUri(root.treeUri, docId))
        } catch (e: Exception) {
            Logs.e("img.open failed doc=$docId err=${e.message}")
            null
        }
    }

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

    fun preview(body: String): String {
        val t = body.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "[图片]")
            .replace(Regex("```[\\s\\S]*?```"), "[代码]")
            .replace(Regex("[#>*`_-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (t.length > 180) t.substring(0, 180) + "…" else t
    }
}
