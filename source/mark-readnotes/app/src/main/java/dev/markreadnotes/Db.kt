package dev.markreadnotes

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SourceRow(val id: Long, val treeUri: String, val rootDocId: String, val rootName: String, val kind: String, val lastScanAt: Long)
data class FileRow(val id: Long, val sourceId: Long, val docUri: String, val docId: String, val name: String, val tag: String, val size: Long, val mtime: Long, val present: Int)
data class BlockRow(
    val id: Long, val fileId: Long, val docUri: String, val heading: String, val tag: String,
    val tags: String, val body: String, val raw: String, val charCount: Int, val index: Int, val updatedAt: Long
)

/** SQLite：索引与“最近编辑”顺序的权威源（正文的权威源始终是磁盘上的 md） */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE source(id INTEGER PRIMARY KEY, tree_uri TEXT UNIQUE, root_doc_id TEXT,
               root_name TEXT, kind TEXT, added_at INTEGER, last_scan_at INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE note_file(id INTEGER PRIMARY KEY, source_id INTEGER, doc_uri TEXT UNIQUE, doc_id TEXT,
               name TEXT, tag TEXT, size INTEGER, mtime INTEGER, last_scan_at INTEGER, present INTEGER DEFAULT 1)"""
        )
        db.execSQL(
            """CREATE TABLE block(id INTEGER PRIMARY KEY, file_id INTEGER, doc_uri TEXT, heading TEXT, tag TEXT,
               tags TEXT, body TEXT, raw TEXT, char_count INTEGER, block_index INTEGER, updated_at INTEGER,
               file_mtime INTEGER, file_size INTEGER, present INTEGER DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX idx_block_updated ON block(updated_at DESC)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 尚无迁移；将来按 expand → migrate → contract 走
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

    fun upsertFile(sourceId: Long, docUri: String, docId: String, name: String, tag: String, size: Long, mtime: Long): Long {
        val existing = fileByDocUri(docUri)
        val cv = ContentValues().apply {
            put("source_id", sourceId)
            put("doc_uri", docUri)
            put("doc_id", docId)
            put("name", name)
            put("tag", tag)
            put("size", size)
            put("mtime", mtime)
            put("last_scan_at", System.currentTimeMillis())
            put("present", 1)
        }
        return if (existing == null) writableDatabase.insert("note_file", null, cv)
        else {
            writableDatabase.update("note_file", cv, "id=?", arrayOf(existing.id.toString()))
            existing.id
        }
    }

    fun setFileMeta(fileId: Long, size: Long, mtime: Long, tag: String) {
        writableDatabase.execSQL("UPDATE note_file SET size=?, mtime=?, tag=?, last_scan_at=? WHERE id=?",
            arrayOf(size, mtime, tag, System.currentTimeMillis(), fileId))
    }

    fun setFilePresent(fileId: Long, present: Int) {
        writableDatabase.execSQL("UPDATE note_file SET present=? WHERE id=?", arrayOf(present, fileId))
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
        c.getInt(c.getColumnIndexOrThrow("present"))
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
