package dev.markreadnotes.notes

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import dev.markreadnotes.BlockRow
import dev.markreadnotes.FileRow
import dev.markreadnotes.Logs
import dev.markreadnotes.Md
import dev.markreadnotes.MdBlock
import dev.markreadnotes.Saf
import dev.markreadnotes.Store
import dev.markreadnotes.Store.Root

/** 随笔本体：块/文件的读写、搬家、扫描与索引。导出去 ExportRepo，界面在 ops/ 与 assets/ui。 */
class NotesRepo(private val store: Store) {
    private val ctx get() = store.ctx
    private val db get() = store.db
    private val saf get() = store.saf
    fun root(): Root? = store.root()
    private fun tagList(row: BlockRow): List<String> = store.tagList(row)

    /** 写回用的小结构：标题 + 标签 + 正文，raw 由它自己算 */
    private data class BData(val heading: String, val tags: List<String>, val body: String) {
        val raw: String get() = Md.renderBlock(heading, tags, body)
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
        val legacy = db.legacyFiles()
        return JSONObject()
            .put("has", true)
            .put("treeUri", s.treeUri)
            .put("pickedName", s.rootName)
            .put("rootLabel", "${s.rootName}/mark-readnotes")
            .put("nodeLabel", store.nodeLabel())
            .put("node", Store.NODE)
            .put("legacy", legacy.size)
            .put("legacyNames", JSONArray(legacy.map { it.name }))
            .put("tagFolders", JSONArray(db.allFolders().map { JSONObject().put("tag", it.first).put("folder", it.second) }))
            .put("exportRoot", db.metaGet("export_root_label") ?: "")
            .put("files", files)
            .put("blocks", blocks)
            .put("lastScanAt", scan)
    }

    // ---------------------- 扫描与索引 ----------------------

    /** 目录枚举：只有首次授权与用户主动刷新才走这里（懒扫描）；只覆盖随笔目录，不扫整个授权目录 */
    @Synchronized
    fun refresh(full: Boolean): Pair<Int, Int> {
        val root = root() ?: return 0 to 0
        val src = db.source() ?: return 0 to 0
        val node = store.node() ?: run { Logs.e("scan=node-missing"); return 0 to 0 }
        val t0 = System.currentTimeMillis()
        val seen = HashSet<String>()
        val changed = intArrayOf(0)
        // 1) 随笔目录（含子文件夹，往下两层）
        scanDir(root, src.id, node.docId, Store.NODE, full, seen, changed, 0)
        // 2) 兼容旧布局：还平铺在授权根下的 md（用户点「整理到随笔目录」之前仍能读）
        var legacy = 0
        for (c in saf.children(root.treeUri, root.rootDocId)) {
            if (c.mime == Store.DIR_MIME || !c.name.lowercase().endsWith(".md")) continue
            indexFile(root, src.id, c, "", "", full, seen, changed)
            legacy++
        }
        for (f in db.allFiles()) if (f.docUri !in seen) { db.setFilePresent(f.id, 0); db.setBlocksPresentOfFile(f.id, 0) }
        db.touchScan(System.currentTimeMillis())
        Logs.i("scan=walk(files=${seen.size},changed=${changed[0]},legacy=$legacy,ms=${System.currentTimeMillis() - t0})")
        return seen.size to db.stats().second
    }

    /** 一个目录下的 md（按相对路径收进索引）；子文件夹最多再往下两层 */
    private fun scanDir(root: Root, srcId: Long, dirDocId: String, relDir: String, full: Boolean, seen: MutableSet<String>, changed: IntArray, depth: Int) {
        for (c in saf.children(root.treeUri, dirDocId)) {
            if (c.mime == Store.DIR_MIME) {
                if (depth < 2) scanDir(root, srcId, c.docId, "$relDir/${c.name}", full, seen, changed, depth + 1)
                continue
            }
            if (!c.name.lowercase().endsWith(".md")) continue
            indexFile(root, srcId, c, relDir, folderOfRel(relDir), full, seen, changed)
        }
    }

    /** 把一个盘上的 md 收进索引（只有变了或强制刷新才真解析） */
    private fun indexFile(root: Root, srcId: Long, c: Saf.Doc, relDir: String, folder: String, full: Boolean, seen: MutableSet<String>, changed: IntArray) {
        val uri = saf.docUri(root.treeUri, c.docId).toString()
        seen.add(uri)
        val row = db.fileByDocUri(uri)
        val stale = row == null || full || row.mtime != c.mtime || row.size != c.size
        val rel = if (relDir.isBlank()) c.name else "$relDir/${c.name}"
        val fid = db.upsertFile(srcId, uri, c.docId, c.name, c.name.removeSuffix(".md"), c.size, c.mtime, rel, folder)
        if (stale) {
            loadFile(db.fileById(fid)!!, root, c.mtime, c.size)
            changed[0]++
        }
    }

    /** 相对路径里的文件夹部分："随笔" → ""；"随笔/网页随口" → "网页随笔" */
    private fun folderOfRel(relDir: String): String = relDir.removePrefix(Store.NODE).trim('/')

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

    /** 找/建某个标签对应的 md 文件（位置由「标签 → 文件夹」映射决定；都放随笔目录里） */
    private fun ensureFileForTag(root: Root, tag: String): FileRow {
        val src = db.source() ?: throw IllegalStateException("还没有选笔记目录")
        val name = Md.fileNameForTag(tag)
        val folder = db.folderOf(tag).trim('/')
        val relDir = if (folder.isBlank()) Store.NODE else "${Store.NODE}/$folder"
        val rel = "$relDir/$name"
        val existing = db.fileByRel(src.id, rel)
        if (existing != null) {
            val doc = saf.stat(root.treeUri, existing.docId)
            if (doc != null) {
                if (doc.mtime != existing.mtime || doc.size != existing.size) loadFile(existing, root, doc.mtime, doc.size)
                return db.fileById(existing.id)!!
            }
            db.setFilePresent(existing.id, 0)
        }
        // 旧布局兼容：同名文件还平铺在授权根下就先用着（用户点「整理」才搬）
        db.fileByName(src.id, name)?.let { legacyRow ->
            val doc = saf.stat(root.treeUri, legacyRow.docId)
            if (doc != null && legacyRow.relPath == legacyRow.name) {
                Logs.i("file.legacy name=$name（还在授权根下，未迁移）")
                return db.fileById(legacyRow.id)!!
            }
        }
        val parent = saf.ensurePath(root.treeUri, root.rootDocId, relDir)
            ?: throw IllegalStateException("建不了目录：$relDir")
        val created = saf.createMd(root.treeUri, parent.docId, name)
            ?: throw IllegalStateException("建不了文件：$rel")
        val fid = db.upsertFile(src.id, saf.docUri(root.treeUri, created.docId).toString(), created.docId, name, tag, created.size, created.mtime, rel, folder)
        Logs.i("file.create rel=$rel tag=$tag")
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
                    .put("relPath", f?.relPath ?: "")
                    .put("folder", f?.folder ?: "")
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
                    .put("relPath", f.relPath).put("folder", f.folder)
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
            .put("charCount", b.charCount).put("file", f?.name ?: "?")
            .put("relPath", f?.relPath ?: "").put("folder", f?.folder ?: "")
            .put("updatedAt", b.updatedAt)
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
        val edited = BData(heading, effTags, body)
        val rows = db.blocksOfFile(file.id)
        var moved = false

        // 目标文件由「第一个标签 + 标签→文件夹映射」决定；同一个文件就原地改，换了文件才搬家
        val target = ensureFileForTag(root, newTag)
        if (target.id == file.id) {
            val list = rows.map { r -> if (r.id == id) edited else BData(r.heading, tagList(r), r.body) }
            val pos = rows.indexOfFirst { it.id == id }.coerceAtLeast(0)
            rewrite(root, file, list, preferId = id, preferIndex = pos, touchId = id)
        } else {
            moved = true
            // 先落到新文件（块的行会改到新文件），再收尾老文件，避免被当成“已删除”
            val trows = db.blocksOfFile(target.id)
            val tlist = trows.map { r -> BData(r.heading, tagList(r), r.body) } + edited
            rewrite(root, target, tlist, preferId = id, preferIndex = tlist.size - 1, touchId = id)
            val oldList = rows.filter { it.id != id }.map { r -> BData(r.heading, tagList(r), r.body) }
            rewrite(root, file, oldList)
            Logs.i("block.move id=$id ${file.relPath} -> ${target.relPath}")
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

    /** 本地图片（md 里的相对路径）：先按随笔目录解析（页面给的路径已带上所在文件夹），再退回授权根（旧布局） */
    fun findImage(rel: String): Saf.Doc? {
        val root = root() ?: return null
        val clean = rel.trim().removePrefix("./").removePrefix("/")
        if (clean.isBlank() || clean.contains("..")) {
            Logs.e("img.reject rel=${rel.take(80)}")
            return null
        }
        val node = store.node(false)
        if (node != null) resolveFrom(root, node.docId, clean, "随笔")?.let { return it }
        return resolveFrom(root, root.rootDocId, clean, "根")
    }

    /** 从某个目录逐级解析相对路径，只允许留在该目录内部 */
    private fun resolveFrom(root: Root, startDocId: String, clean: String, tag: String): Saf.Doc? {
        var parentId = startDocId
        val parts = clean.split("/")
        for ((i, seg) in parts.withIndex()) {
            val hit = saf.children(root.treeUri, parentId).firstOrNull { it.name == seg } ?: run {
                Logs.i("img.miss rel=$clean at=$seg from=$tag")
                return null
            }
            if (i == parts.size - 1) {
                Logs.i("img.serve rel=$clean from=$tag mime=${hit.mime} size=${hit.size}")
                return hit
            }
            parentId = hit.docId
        }
        return null
    }

    // ---------------------- 文件夹（标签 → 文件夹的映射） ----------------------

    /** 建文件夹（一层层往下）；只允许建在随笔目录里 */
    @Synchronized
    fun mkdir(relDir: String): JSONObject {
        val root = root() ?: throw IllegalStateException("还没有选笔记目录")
        val clean = relDir.trim().trim('/')
        if (clean.isBlank() || clean.contains("..")) throw IllegalStateException("文件夹名不合法")
        val full = "${Store.NODE}/$clean"
        val doc = saf.ensurePath(root.treeUri, root.rootDocId, full) ?: throw IllegalStateException("建不了目录：$full")
        Logs.i("node.mkdir rel=$full")
        return JSONObject().put("folder", clean).put("rel", full).put("docId", doc.docId)
    }

    /** 把某个标签指到某个文件夹（只改索引映射；空 = 随笔根下）。
     *  已有文件要跟着走 —— 否则同一个标签的内容会分裂成两个文件（旧的那个留在原地，新块进新文件）。 */
    @Synchronized
    fun setTagFolder(tag: String, folder: String): JSONObject {
        val tg = tag.trim().removePrefix("#").ifBlank { throw IllegalStateException("标签不能为空") }
        val clean = folder.trim().trim('/')
        if (clean.isNotBlank()) {
            val root = root() ?: throw IllegalStateException("还没有选笔记目录")
            saf.findPath(root.treeUri, root.rootDocId, "${Store.NODE}/$clean")
                ?: throw IllegalStateException("文件夹不存在：$clean（先用「新建文件夹」建一个）")
        }
        db.setFolder(tg, clean)
        Logs.i("tag.folder tag=$tg folder=${clean.ifBlank { "(随笔根)" }}")
        val res = JSONObject().put("tag", tg).put("folder", clean)
        return try {
            res.put("move", moveTagFile(tg, clean))
        } catch (e: Exception) {
            Logs.e("tag.folder move failed tag=$tg err=${e.message}")
            res.put("moveError", e.message ?: "搬家失败")
        }
    }

    /** 把某个标签现有的文件搬到它该在的文件夹里：复制 → 校验 → 删旧 → 索引改指。
     *  目标位置已有同名文件就**按块合并**（同一个标签就是一个文件），合并前后块数必须对得上。 */
    @Synchronized
    fun moveTagFile(tag: String, folder: String): JSONObject {
        val root = root() ?: throw IllegalStateException("还没有选笔记目录")
        val src = db.source() ?: throw IllegalStateException("还没有选笔记目录")
        val name = Md.fileNameForTag(tag)
        val relDir = if (folder.isBlank()) Store.NODE else "${Store.NODE}/$folder"
        val targetRel = "$relDir/$name"
        val from = db.allFiles().firstOrNull { it.present == 1 && it.name == name && it.relPath != targetRel }
            ?: return JSONObject().put("skipped", "没有需要搬的文件")
        val textFrom = saf.readText(root.treeUri, from.docId)
        val parent = saf.ensurePath(root.treeUri, root.rootDocId, relDir) ?: throw IllegalStateException("建不了目录：$relDir")
        val hit = saf.children(root.treeUri, parent.docId).firstOrNull { it.name == name }
        val blocksFrom = Md.split(textFrom)
        val mergedText = if (hit != null) Md.renderFile(Md.split(saf.readText(root.treeUri, hit.docId)) + blocksFrom) else textFrom
        val dst = hit ?: (saf.createMd(root.treeUri, parent.docId, name) ?: throw IllegalStateException("建不了文件：$targetRel"))
        ctx.contentResolver.openOutputStream(saf.docUri(root.treeUri, dst.docId), "wt")?.use {
            it.write(mergedText.toByteArray(Charsets.UTF_8)); it.flush()
        } ?: throw IllegalStateException("写不进目标文件：$targetRel")
        val back = saf.readText(root.treeUri, dst.docId)
        if (Md.split(back).size != Md.split(mergedText).size) throw IllegalStateException("目标文件块数对不上，已停下（旧文件没删）")
        if (!saf.delete(root.treeUri, from.docId)) throw IllegalStateException("删不掉旧文件：${from.relPath}")
        val doc = saf.stat(root.treeUri, dst.docId) ?: throw IllegalStateException("搬完读不到目标文件")
        val fid = db.upsertFile(src.id, saf.docUri(root.treeUri, dst.docId).toString(), dst.docId, name, tag, doc.size, doc.mtime, targetRel, folder)
        db.repointBlocks(from.id, fid, saf.docUri(root.treeUri, dst.docId).toString())
        db.dropFile(from.id)
        loadFile(db.fileById(fid)!!, root, doc.mtime, doc.size)   // 重建该文件的块（按 raw 认领，不重复）
        Logs.i("tag.folder move file=$name ${from.relPath} -> $targetRel blocks=${blocksFrom.size} merged=${hit != null}")
        return JSONObject()
            .put("moved", true)
            .put("from", from.relPath)
            .put("to", targetRel)
            .put("blocks", blocksFrom.size)
            .put("merged", hit != null)
    }

    /** 把还平铺在授权根下的 md 搬进随笔目录（建新→写→校验字节→删旧；任何一步失败就停） */
    @Synchronized
    fun migrateLegacy(): JSONObject {
        val root = root() ?: throw IllegalStateException("还没有选笔记目录")
        val src = db.source() ?: throw IllegalStateException("还没有选笔记目录")
        val node = store.node() ?: throw IllegalStateException("建不了随笔目录")
        val moved = ArrayList<String>()
        val failed = ArrayList<String>()
        for (f in db.legacyFiles()) {
            try {
                val bytes = saf.readText(root.treeUri, f.docId).toByteArray(Charsets.UTF_8)
                // 随笔目录里已有同名文件：内容一样就只收尾旧文件；不一样就停下报清楚（绝不覆盖数据）
                val clash = saf.children(root.treeUri, node.docId).firstOrNull { it.name == f.name }
                if (clash != null) {
                    val there = saf.readText(root.treeUri, clash.docId).toByteArray(Charsets.UTF_8)
                    if (!there.contentEquals(bytes)) {
                        failed.add("${f.name}：随笔目录里已有同名但内容不同的文件，没动")
                        Logs.e("node.migrate file=${f.name} clash-different")
                        break
                    }
                    if (!saf.delete(root.treeUri, f.docId)) throw IllegalStateException("删不掉旧文件")
                    db.setFilePresent(f.id, 0)
                    db.setBlocksPresentOfFile(f.id, 0)
                    moved.add("${f.name}(同名同内容，只删旧)")
                    Logs.i("node.migrate file=${f.name} same-content ok")
                    continue
                }
                val created = saf.createMd(root.treeUri, node.docId, f.name) ?: throw IllegalStateException("建不了新文件")
                val out = ctx.contentResolver.openOutputStream(saf.docUri(root.treeUri, created.docId), "wt")
                    ?: throw IllegalStateException("写不进新文件")
                out.use { it.write(bytes); it.flush() }
                val back = saf.stat(root.treeUri, created.docId) ?: throw IllegalStateException("写完读不到")
                if (back.size != bytes.size.toLong()) throw IllegalStateException("字节数不一致（${back.size} vs ${bytes.size}）")
                if (!saf.delete(root.treeUri, f.docId)) throw IllegalStateException("删不掉旧文件")
                // 块的行改指到新文件（保住 id 与“最近编辑”顺序），再收掉旧文件行：绝不让块被重复入库
                val fid = db.upsertFile(src.id, saf.docUri(root.treeUri, created.docId).toString(), created.docId, f.name, f.tag, back.size, back.mtime, "${Store.NODE}/${f.name}", "")
                db.repointBlocks(f.id, fid, saf.docUri(root.treeUri, created.docId).toString())
                db.dropFile(f.id)
                moved.add(f.name)
                Logs.i("node.migrate file=${f.name} bytes=${bytes.size} ok")
            } catch (e: Exception) {
                failed.add("${f.name}：${e.message}")
                Logs.e("node.migrate file=${f.name} failed err=${e.message}")
                break   // 任何一步失败就停下，不半截继续
            }
        }
        refresh(true)
        return JSONObject().put("moved", JSONArray(moved)).put("failed", JSONArray(failed)).put("left", db.legacyFiles().size)
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

    fun preview(body: String): String {
        val t = body.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "[图片]")
            .replace(Regex("```[\\s\\S]*?```"), "[代码]")
            .replace(Regex("[#>*`_-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (t.length > 180) t.substring(0, 180) + "…" else t
    }
}
