package dev.markreadnotes

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SourceRow(val id: Long, val treeUri: String, val rootDocId: String, val rootName: String, val kind: String, val lastScanAt: Long)
data class FileRow(
    val id: Long, val sourceId: Long, val docUri: String, val docId: String, val name: String, val tag: String,
    val size: Long, val mtime: Long, val present: Int, val relPath: String, val folder: String
)
data class BlockRow(
    val id: Long, val fileId: Long, val docUri: String, val heading: String, val tag: String,
    val tags: String, val body: String, val raw: String, val charCount: Int, val index: Int, val updatedAt: Long
)
/** 同步基线：上一次同步时两边各是什么样子（判断“谁动过”全靠它） */
data class SyncRow(
    val relPath: String, val localSize: Long, val localHash: String,
    val remoteSize: Long, val remoteMtime: Long, val remoteEtag: String, val syncedAt: Long
)

/** SQLite：索引与“最近编辑”顺序的权威源（正文的权威源始终是磁盘上的 md） */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE source(id INTEGER PRIMARY KEY, tree_uri TEXT UNIQUE, root_doc_id TEXT,
               root_name TEXT, kind TEXT, added_at INTEGER, last_scan_at INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE note_file(id INTEGER PRIMARY KEY, source_id INTEGER, doc_uri TEXT UNIQUE, doc_id TEXT,
               name TEXT, tag TEXT, size INTEGER, mtime INTEGER, last_scan_at INTEGER, present INTEGER DEFAULT 1,
               rel_path TEXT, folder TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE block(id INTEGER PRIMARY KEY, file_id INTEGER, doc_uri TEXT, heading TEXT, tag TEXT,
               tags TEXT, body TEXT, raw TEXT, char_count INTEGER, block_index INTEGER, updated_at INTEGER,
               file_mtime INTEGER, file_size INTEGER, present INTEGER DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX idx_block_updated ON block(updated_at DESC)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        // 标签 → 文件夹（文件夹是标签的一个属性；空 = 直接放随笔根下）
        db.execSQL("CREATE TABLE tag_folder(tag TEXT PRIMARY KEY, folder TEXT, updated_at INTEGER)")
        // WebDAV 同步基线（见 docs/同步规格.md）
        db.execSQL(
            """CREATE TABLE sync_state(rel_path TEXT PRIMARY KEY, local_size INTEGER, local_hash TEXT,
               remote_size INTEGER, remote_mtime INTEGER, remote_etag TEXT, synced_at INTEGER)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 → v2：随笔独立目录 + 文件夹（expand：先加列加表，旧数据原样保留）
        if (old < 2) {
            runCatching { db.execSQL("ALTER TABLE note_file ADD COLUMN rel_path TEXT") }
            runCatching { db.execSQL("ALTER TABLE note_file ADD COLUMN folder TEXT") }
            db.execSQL("CREATE TABLE IF NOT EXISTS tag_folder(tag TEXT PRIMARY KEY, folder TEXT, updated_at INTEGER)")
        }
        // v2 → v3：WebDAV 同步基线
        if (old < 3) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS sync_state(rel_path TEXT PRIMARY KEY, local_size INTEGER, local_hash TEXT,
                   remote_size INTEGER, remote_mtime INTEGER, remote_etag TEXT, synced_at INTEGER)"""
            )
        }
    }

    // ---------- source ----------

    fun setSource(treeUri: String, rootDocId: String, rootName: String, kind: String) {
        val cv = ContentValues().apply {
            put("tree_uri", treeUri)
            put("root_doc_id", rootDocId)
            put("root_name", rootName)
            put("kind", kind)
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("source", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("UPDATE source SET root_doc_id=?, root_name=? WHERE tree_uri=?", arrayOf(rootDocId, rootName, treeUri))
    }

    fun source(): SourceRow? = readableDatabase.rawQuery("SELECT id,tree_uri,root_doc_id,root_name,kind,last_scan_at FROM source LIMIT 1", null).use { c ->
        if (c.moveToFirst()) SourceRow(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getLong(5)) else null
    }

    fun touchScan(ts: Long) {
        writableDatabase.execSQL("UPDATE source SET last_scan_at=?", arrayOf(ts))
    }

    // ---------- note_file ----------

    fun fileByName(sourceId: Long, name: String): FileRow? =
        readableDatabase.rawQuery("SELECT * FROM note_file WHERE source_id=? AND name=?", arrayOf(sourceId.toString(), name)).use { c ->
            if (c.moveToFirst()) fileRow(c) else null
        }

    fun fileById(id: Long): FileRow? = readableDatabase.rawQuery("SELECT * FROM note_file WHERE id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) fileRow(c) else null
    }

    fun fileByDocUri(uri: String): FileRow? = readableDatabase.rawQuery("SELECT * FROM note_file WHERE doc_uri=?", arrayOf(uri)).use { c ->
        if (c.moveToFirst()) fileRow(c) else null
    }

    fun allFiles(): List<FileRow> = readableDatabase.rawQuery("SELECT * FROM note_file ORDER BY name", null).use { c ->
        val out = ArrayList<FileRow>()
        while (c.moveToNext()) out.add(fileRow(c))
        out
    }

    fun fileByRel(sourceId: Long, relPath: String): FileRow? =
        readableDatabase.rawQuery("SELECT * FROM note_file WHERE source_id=? AND rel_path=?", arrayOf(sourceId.toString(), relPath)).use { c ->
            if (c.moveToFirst()) fileRow(c) else null
        }

    /** 还留在授权根下、没搬进随笔目录的文件（迁移用） */
    fun legacyFiles(): List<FileRow> =
        readableDatabase.rawQuery("SELECT * FROM note_file WHERE present=1 AND (rel_path IS NULL OR rel_path NOT LIKE ?)", arrayOf("随笔/%")).use { c ->
            val out = ArrayList<FileRow>()
            while (c.moveToNext()) out.add(fileRow(c))
            out
        }

    fun upsertFile(sourceId: Long, docUri: String, docId: String, name: String, tag: String, size: Long, mtime: Long, relPath: String = "", folder: String = ""): Long {
        val existing = fileByDocUri(docUri)
        val cv = ContentValues().apply {
            put("source_id", sourceId)
            put("doc_uri", docUri)
            put("doc_id", docId)
            put("name", name)
            put("tag", tag)
            put("size", size)
            put("mtime", mtime)
            put("rel_path", relPath)
            put("folder", folder)
            put("last_scan_at", System.currentTimeMillis())
            put("present", 1)
        }
        return if (existing == null) writableDatabase.insert("note_file", null, cv)
        else {
            writableDatabase.update("note_file", cv, "id=?", arrayOf(existing.id.toString()))
            existing.id
        }
    }

    // ---------- tag_folder（标签 → 文件夹 的映射） ----------

    fun folderOf(tag: String): String =
        readableDatabase.rawQuery("SELECT folder FROM tag_folder WHERE tag=?", arrayOf(tag)).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }

    fun setFolder(tag: String, folder: String) {
        val cv = ContentValues().apply { put("tag", tag); put("folder", folder); put("updated_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("tag_folder", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allFolders(): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT tag,folder FROM tag_folder ORDER BY tag", null).use { c ->
            val out = ArrayList<Pair<String, String>>()
            while (c.moveToNext()) out.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
            out
        }

    fun setFileMeta(fileId: Long, size: Long, mtime: Long, tag: String) {
        writableDatabase.execSQL("UPDATE note_file SET size=?, mtime=?, tag=?, last_scan_at=? WHERE id=?",
            arrayOf(size, mtime, tag, System.currentTimeMillis(), fileId))
    }

    fun setFilePresent(fileId: Long, present: Int) {
        writableDatabase.execSQL("UPDATE note_file SET present=? WHERE id=?", arrayOf(present, fileId))
    }

    /** 文件被标为不在时，它名下的块也一起标掉（索引一致性：不留查不到归属的孤儿块） */
    fun setBlocksPresentOfFile(fileId: Long, present: Int) {
        writableDatabase.execSQL("UPDATE block SET present=? WHERE file_id=?", arrayOf(present, fileId))
    }

    /** 物理搬家后把块的行改指到新文件（保住块的 id 与“最近编辑”顺序，避免被当成新块重复入库） */
    fun repointBlocks(fromFileId: Long, toFileId: Long, docUri: String) {
        writableDatabase.execSQL("UPDATE block SET file_id=?, doc_uri=? WHERE file_id=?", arrayOf(toFileId, docUri, fromFileId))
    }

    fun dropFile(fileId: Long) {
        writableDatabase.delete("note_file", "id=?", arrayOf(fileId.toString()))
        writableDatabase.delete("block", "file_id=?", arrayOf(fileId.toString()))
    }

    private fun fileRow(c: Cursor) = FileRow(
        c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("source_id")),
        c.getString(c.getColumnIndexOrThrow("doc_uri")) ?: "", c.getString(c.getColumnIndexOrThrow("doc_id")) ?: "",
        c.getString(c.getColumnIndexOrThrow("name")) ?: "", c.getString(c.getColumnIndexOrThrow("tag")) ?: "",
        c.getLong(c.getColumnIndexOrThrow("size")), c.getLong(c.getColumnIndexOrThrow("mtime")),
        c.getInt(c.getColumnIndexOrThrow("present")),
        c.getString(c.getColumnIndex("rel_path")) ?: "", c.getString(c.getColumnIndex("folder")) ?: ""
    )

    // ---------- block ----------

    fun blocksOfFile(fileId: Long, onlyPresent: Boolean = true): List<BlockRow> {
        val sql = "SELECT * FROM block WHERE file_id=?" + (if (onlyPresent) " AND present=1" else "") + " ORDER BY block_index"
        return readableDatabase.rawQuery(sql, arrayOf(fileId.toString())).use { c ->
            val out = ArrayList<BlockRow>()
            while (c.moveToNext()) out.add(blockRow(c))
            out
        }
    }

    fun blockById(id: Long): BlockRow? = readableDatabase.rawQuery("SELECT * FROM block WHERE id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) blockRow(c) else null
    }

    fun recentBlocks(limit: Int): List<BlockRow> =
        readableDatabase.rawQuery("SELECT * FROM block WHERE present=1 ORDER BY updated_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            val out = ArrayList<BlockRow>()
            while (c.moveToNext()) out.add(blockRow(c))
            out
        }

    fun allBlocks(): List<BlockRow> = readableDatabase.rawQuery("SELECT * FROM block WHERE present=1 ORDER BY file_id, block_index", null).use { c ->
        val out = ArrayList<BlockRow>()
        while (c.moveToNext()) out.add(blockRow(c))
        out
    }

    // ---------- meta（导出目录等长期配置） ----------

    fun metaGet(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun metaPut(key: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    // ---------- sync_state（WebDAV 同步基线） ----------

    fun syncGet(relPath: String): SyncRow? =
        readableDatabase.rawQuery("SELECT * FROM sync_state WHERE rel_path=?", arrayOf(relPath)).use { c ->
            if (c.moveToFirst()) syncRow(c) else null
        }

    fun syncAll(): List<SyncRow> = readableDatabase.rawQuery("SELECT * FROM sync_state", null).use { c ->
        val out = ArrayList<SyncRow>()
        while (c.moveToNext()) out.add(syncRow(c))
        out
    }

    fun syncPut(relPath: String, localSize: Long, localHash: String, remoteSize: Long, remoteMtime: Long, remoteEtag: String) {
        val cv = ContentValues().apply {
            put("rel_path", relPath); put("local_size", localSize); put("local_hash", localHash)
            put("remote_size", remoteSize); put("remote_mtime", remoteMtime); put("remote_etag", remoteEtag)
            put("synced_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("sync_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun syncForget(relPath: String) = writableDatabase.delete("sync_state", "rel_path=?", arrayOf(relPath))

    private fun syncRow(c: Cursor) = SyncRow(
        c.getString(c.getColumnIndexOrThrow("rel_path")) ?: "",
        c.getLong(c.getColumnIndexOrThrow("local_size")), c.getString(c.getColumnIndexOrThrow("local_hash")) ?: "",
        c.getLong(c.getColumnIndexOrThrow("remote_size")), c.getLong(c.getColumnIndexOrThrow("remote_mtime")),
        c.getString(c.getColumnIndexOrThrow("remote_etag")) ?: "", c.getLong(c.getColumnIndexOrThrow("synced_at"))
    )

    fun insertBlock(fileId: Long, docUri: String, heading: String, tag: String, tags: String, body: String, raw: String, index: Int, fileMtime: Long, fileSize: Long, updatedAt: Long): Long {
        val cv = ContentValues().apply {
            put("file_id", fileId); put("doc_uri", docUri); put("heading", heading); put("tag", tag); put("tags", tags)
            put("body", body); put("raw", raw); put("char_count", body.length); put("block_index", index)
            put("updated_at", updatedAt); put("file_mtime", fileMtime); put("file_size", fileSize); put("present", 1)
        }
        return writableDatabase.insert("block", null, cv)
    }

    fun updateBlock(id: Long, fileId: Long, docUri: String, heading: String, tag: String, tags: String, body: String, raw: String, index: Int, fileMtime: Long, fileSize: Long, updatedAt: Long) {
        val cv = ContentValues().apply {
            put("file_id", fileId); put("doc_uri", docUri); put("heading", heading); put("tag", tag); put("tags", tags)
            put("body", body); put("raw", raw); put("char_count", body.length); put("block_index", index)
            put("updated_at", updatedAt); put("file_mtime", fileMtime); put("file_size", fileSize); put("present", 1)
        }
        writableDatabase.update("block", cv, "id=?", arrayOf(id.toString()))
    }

    fun setBlockPresent(id: Long, present: Int) {
        writableDatabase.execSQL("UPDATE block SET present=? WHERE id=?", arrayOf(present, id))
    }

    fun deleteBlockRow(id: Long) = writableDatabase.delete("block", "id=?", arrayOf(id.toString()))

    private fun blockRow(c: Cursor) = BlockRow(
        c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("file_id")),
        c.getString(c.getColumnIndexOrThrow("doc_uri")) ?: "", c.getString(c.getColumnIndexOrThrow("heading")) ?: "",
        c.getString(c.getColumnIndexOrThrow("tag")) ?: "", c.getString(c.getColumnIndexOrThrow("tags")) ?: "",
        c.getString(c.getColumnIndexOrThrow("body")) ?: "", c.getString(c.getColumnIndexOrThrow("raw")) ?: "",
        c.getInt(c.getColumnIndexOrThrow("char_count")), c.getInt(c.getColumnIndexOrThrow("block_index")),
        c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    fun stats(): Triple<Int, Int, Long> {
        val files = readableDatabase.rawQuery("SELECT COUNT(*) FROM note_file WHERE present=1", null).use { it.moveToFirst(); it.getInt(0) }
        val blocks = readableDatabase.rawQuery("SELECT COUNT(*) FROM block WHERE present=1", null).use { it.moveToFirst(); it.getInt(0) }
        val scan = source()?.lastScanAt ?: 0L
        return Triple(files, blocks, scan)
    }
}
