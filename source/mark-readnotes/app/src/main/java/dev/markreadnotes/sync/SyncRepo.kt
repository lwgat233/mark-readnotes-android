package dev.markreadnotes.sync

import dev.markreadnotes.Logs
import dev.markreadnotes.Store
import dev.markreadnotes.notes.NotesRepo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * WebDAV 同步：基线 + 冲突三选。
 *
 * 参考 waikr/KardLeaf（Apache-2.0）的同步语义（`data/sync/` 下那几个文件）：
 *   基线记“上次同步时两边各什么样”（本地 size:hash、远端 size:mtime:etag），靠它判断谁动过；
 *   两边都动过 = 冲突，**不自动选一边**，交给用户三选（保留本地 / 保留远端 / 跳过），跳过的不写基线，下次还会报。
 * 本项目的差异：远端路径就是本地的相对路径（`随笔/xxx.md`），不做远端重命名映射。
 *
 * 规矩（照 docs/同步规格.md S1–S8）：
 *   - 只同步随笔目录里的 md（本地索引 present=1 的那些），不碰别的文件；
 *   - 上传：逐级 MKCOL 建目录 → PUT → 读回 stat 记基线；
 *   - 下载：GET → 写本地（没有就建）→ 更新索引 → 记基线；
 *   - 冲突：一个字也不动，列出来让用户定；
 *   - 凭证只存在应用私有库的 meta 里，不上传、不写日志（日志里只出现服务器地址与用户名以外的信息）。
 */
class SyncRepo(private val store: Store, private val notes: NotesRepo) {
    private val db get() = store.db
    private val saf get() = store.saf

    private fun cfgUrl() = db.metaGet("dav_url") ?: ""
    private fun cfgUser() = db.metaGet("dav_user") ?: ""
    private fun cfgPass() = db.metaGet("dav_pass") ?: ""

    private fun client(): WebDavClient {
        val url = cfgUrl()
        if (url.isBlank()) throw IllegalStateException("还没填 WebDAV 地址")
        return WebDavClient(url, cfgUser(), cfgPass())
    }

    fun configJson(): JSONObject = JSONObject()
        .put("url", cfgUrl())
        .put("user", cfgUser())
        .put("hasPass", cfgPass().isNotBlank())
        .put("syncedFiles", db.syncAll().size)

    /** 保存配置；pass 传空字符串表示“不改密码” */
    fun setConfig(url: String, user: String, pass: String): JSONObject {
        db.metaPut("dav_url", url.trim())
        db.metaPut("dav_user", user.trim())
        if (pass.isNotEmpty()) db.metaPut("dav_pass", pass)
        Logs.i("dav.config url=${url.trim()} user=${user.trim()} pass=${if (pass.isEmpty()) "keep" else "set"}")
        return configJson()
    }

    fun test(): JSONObject {
        val (okFlag, msg) = client().test()
        Logs.i("dav.test ok=$okFlag msg=$msg")
        return JSONObject().put("ok", okFlag).put("message", msg)
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Local(val relPath: String, val docId: String, val bytes: ByteArray, val hash: String)

    /** 本地这一侧：随笔目录里被判为在的那些文件 + 内容哈希 */
    private fun locals(): Map<String, Local> {
        val out = HashMap<String, Local>()
        for (f in db.allFiles()) {
            if (f.present != 1) continue
            if (!f.relPath.startsWith(Store.NODE + "/")) continue
            val doc = saf.stat(store.root()!!.treeUri, f.docId) ?: continue
            val bytes = runCatching { saf.readText(store.root()!!.treeUri, f.docId).toByteArray(Charsets.UTF_8) }.getOrNull() ?: continue
            out[f.relPath] = Local(f.relPath, f.docId, bytes, hash(bytes))
        }
        return out
    }

    /** 远端这一侧：逐个目录 PROPFIND（只往下走本地与基线出现过的目录，不做全树遍历） */
    private fun remotes(): Map<String, WebDavClient.Entry> {
        val out = HashMap<String, WebDavClient.Entry>()
        val dirs = HashSet<String>()
        dirs.add(Store.NODE)
        for (p in locals().keys + db.syncAll().map { it.relPath }) {
            val parent = p.substringBeforeLast('/', "")
            if (parent.isNotBlank()) {
                dirs.add(parent)
                val gp = parent.substringBeforeLast('/', "")
                if (gp.isNotBlank()) dirs.add(gp)
            }
        }
        val dav = client()
        for (d in dirs) {
            val list = runCatching { dav.list(d) }.getOrElse {
                Logs.e("dav.list failed dir=$d err=${it.message}")
                throw IllegalStateException("列远端目录失败（$d）：${it.message}")
            }
            for (e in list) {
                if (e.dir) continue
                if (!e.path.endsWith(".md")) continue
                out[e.path] = e
            }
        }
        return out
    }

    /**
     * 算计划：不写任何东西，只回答“每个文件该怎么办”。
     * action ∈ upload / download / keep / conflict
     */
    fun plan(): JSONObject {
        val local = locals()
        val remote = remotes()
        val base = db.syncAll().associateBy { it.relPath }
        val actions = ArrayList<JSONObject>()
        val conflicts = ArrayList<JSONObject>()
        for (rel in (local.keys + remote.keys).sorted()) {
            val l = local[rel]
            val r = remote[rel]
            val b = base[rel]
            val localChanged = when {
                l == null -> b != null                     // 基线里有、本地没了 → 本地删过
                b == null -> true                           // 从没同步过
                else -> b.localSize != l.bytes.size.toLong() || b.localHash != l.hash
            }
            val remoteChanged = when {
                r == null -> b != null
                b == null -> true
                else -> b.remoteSize != r.size || (b.remoteEtag.isNotBlank() && r.etag.isNotBlank() && b.remoteEtag != r.etag)
            }
            val act: String = when {
                l != null && r == null && (b == null || localChanged) -> "upload"
                l == null && r != null && (b == null || remoteChanged) -> "download"
                l != null && r != null && b == null -> "conflict"
                l != null && r != null && localChanged && remoteChanged -> "conflict"
                l != null && r != null && localChanged -> "upload"
                l != null && r != null && remoteChanged -> "download"
                l == null && r == null -> "keep"            // 两边都没了（正常删除）
                else -> "keep"
            }
            val item = JSONObject().put("relPath", rel).put("action", act)
                .put("localSize", l?.bytes?.size ?: -1).put("remoteSize", r?.size ?: -1)
                .put("baseline", b != null)
            if (act == "conflict") {
                conflicts.add(item.put("reason",
                    when {
                        l == null -> "本地已删除，远端还在"
                        r == null -> "远端已删除，本地还在"
                        b == null -> "两边都有但没同步过（无从判断谁新）"
                        else -> "两边都改过"
                    }))
            } else {
                actions.add(item)
            }
        }
        val out = JSONObject()
            .put("upload", actions.filter { it.getString("action") == "upload" }.size)
            .put("download", actions.filter { it.getString("action") == "download" }.size)
            .put("keep", actions.filter { it.getString("action") == "keep" }.size)
            .put("conflicts", conflicts.size)
            .put("actions", JSONArray(actions))
            .put("conflictList", JSONArray(conflicts))
        Logs.i("dav.plan upload=${out.getInt("upload")} download=${out.getInt("download")} keep=${out.getInt("keep")} conflicts=${out.getInt("conflicts")}")
        return out
    }

    /** 执行计划：冲突一律不动（留给三选）；逐个记日志，失败不吞 */
    fun run(): JSONObject {
        val p = plan()
        val done = JSONArray()
        val failed = JSONArray()
        val acts = p.optJSONArray("actions") ?: JSONArray()
        val local = locals()
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            val rel = a.getString("relPath")
            val act = a.getString("action")
            try {
                when (act) {
                    "upload" -> uploadOne(rel, local[rel] ?: throw IllegalStateException("本地读不到这个文件"))
                    "download" -> downloadOne(rel)
                    else -> { }
                }
                if (act != "keep") done.put(JSONObject().put("relPath", rel).put("action", act))
            } catch (e: Exception) {
                Logs.e("dav.run failed rel=$rel action=$act err=${e.message}")
                failed.put(JSONObject().put("relPath", rel).put("action", act).put("error", e.message ?: "?"))
            }
        }
        val out = JSONObject()
            .put("uploaded", done.let { d -> (0 until d.length()).count { d.getJSONObject(it).getString("action") == "upload" } })
            .put("downloaded", done.let { d -> (0 until d.length()).count { d.getJSONObject(it).getString("action") == "download" } })
            .put("conflicts", p.getInt("conflicts"))
            .put("conflictList", p.getJSONArray("conflictList"))
            .put("done", done)
            .put("failed", failed)
        Logs.i("dav.run uploaded=${out.getInt("uploaded")} downloaded=${out.getInt("downloaded")} conflicts=${out.getInt("conflicts")} failed=${failed.length()}")
        return out
    }

    /** 冲突三选：local=用本地覆盖远端 / remote=用远端覆盖本地 / skip=先不动（不写基线，下次还会报） */
    fun resolve(relPath: String, choice: String): JSONObject {
        val rel = relPath.trim()
        if (rel.isBlank()) throw IllegalStateException("要解决哪个文件？")
        Logs.i("dav.resolve rel=$rel choice=$choice")
        when (choice) {
            "local" -> {
                val l = locals()[rel] ?: throw IllegalStateException("本地没有这个文件，选不了“保留本地”")
                uploadOne(rel, l)
            }
            "remote" -> downloadOne(rel)
            "skip" -> return JSONObject().put("relPath", rel).put("choice", "skip").put("pending", true)
            else -> throw IllegalStateException("不认识的选择：$choice")
        }
        return JSONObject().put("relPath", rel).put("choice", choice).put("done", true)
    }

    private fun uploadOne(rel: String, l: Local) {
        val dav = client()
        val parent = rel.substringBeforeLast('/', "")
        if (parent.isNotBlank()) dav.mkdirs(parent)
        dav.put(rel, l.bytes)
        val st = runCatching { dav.list(parent) }.getOrNull()?.firstOrNull { it.path == rel }
        db.syncPut(rel, l.bytes.size.toLong(), l.hash, st?.size ?: l.bytes.size.toLong(), st?.mtime ?: System.currentTimeMillis(), st?.etag ?: "")
        Logs.i("dav.upload rel=$rel bytes=${l.bytes.size} remoteEtag=${st?.etag ?: "-"}")
    }

    private fun downloadOne(rel: String) {
        val dav = client()
        val bytes = dav.get(rel)
        val root = store.root() ?: throw IllegalStateException("还没有选笔记目录")
        val src = db.source() ?: throw IllegalStateException("还没有选笔记目录")
        val row = db.allFiles().firstOrNull { it.relPath == rel && it.present == 1 }
        val docId: String
        if (row != null) {
            saf.writeText(root.treeUri, row.docId, String(bytes, Charsets.UTF_8))
            docId = row.docId
        } else {
            val parent = saf.ensurePath(root.treeUri, root.rootDocId, rel.substringBeforeLast('/', ""))
                ?: throw IllegalStateException("建不了目录：${rel.substringBeforeLast('/', "")}")
            val created = saf.createMd(root.treeUri, parent.docId, rel.substringAfterLast('/'))
                ?: throw IllegalStateException("建不了文件：$rel")
            saf.writeText(root.treeUri, created.docId, String(bytes, Charsets.UTF_8))
            docId = created.docId
        }
        val doc = saf.stat(root.treeUri, docId) ?: throw IllegalStateException("写完读不到：$rel")
        val name = rel.substringAfterLast('/')
        val folder = rel.removePrefix(Store.NODE + "/").substringBeforeLast('/', "")
        val fid = db.upsertFile(src.id, saf.docUri(root.treeUri, docId).toString(), docId, name, name.removeSuffix(".md"), doc.size, doc.mtime, rel, folder)
        notes.reloadFile(fid)
        val st = runCatching { dav.list(rel.substringBeforeLast('/', "")) }.getOrNull()?.firstOrNull { it.path == rel }
        db.syncPut(rel, bytes.size.toLong(), hash(bytes), st?.size ?: bytes.size.toLong(), st?.mtime ?: System.currentTimeMillis(), st?.etag ?: "")
        Logs.i("dav.download rel=$rel bytes=${bytes.size} new=${row == null}")
    }
}
