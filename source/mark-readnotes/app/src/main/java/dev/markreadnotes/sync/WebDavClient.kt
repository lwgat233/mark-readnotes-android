package dev.markreadnotes.sync

import dev.markreadnotes.Logs
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端：只用到 MKCOL / PROPFIND / GET / PUT / DELETE + Basic 认证。
 *
 * 参考 waikr/KardLeaf（Apache-2.0）：它的 `data/sync/WebDavCloudSyncManager.kt` 用的就是
 * 这一组方法（MKCOL 建目录 → PROPFIND 列清单 → PUT/GET 传文件 → DELETE 删文件）与 OkHttp；
 * 本文件按本项目的“一个标签一个文件、相对路径即远端路径”的结构重写，没有整段照搬。
 *
 * 为什么不自己拼 socket：https、chunked、重定向这些坑不值得重踩一遍。
 * 为什么不用系统自带的 HttpURLConnection：Android 的实现只允许 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
 * PROPFIND 会直接抛 ProtocolException。
 */
class WebDavClient(private val base: String, private val user: String, private val pass: String, private val timeoutSec: Long = 20) {

    data class Entry(val path: String, val size: Long, val mtime: Long, val etag: String, val dir: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val md = "text/markdown; charset=utf-8".toMediaType()

    /** 配置的 base 路径（例如 /dav 或 /remote.php/dav/files/me）：解析 PROPFIND 的 href 时按它截 */
    private val basePath: String = runCatching { java.net.URI(base.trimEnd('/') + "/").path ?: "" }.getOrDefault("")

    private fun url(path: String): String {
        val clean = path.trim('/')
        val enc = clean.split("/").filter { it.isNotBlank() }.joinToString("/") { enc(it) }
        return base.trimEnd('/') + "/" + enc
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun build(path: String, method: String, body: okhttp3.RequestBody? = null, depth: String? = null): Request {
        val b = Request.Builder().url(url(path))
        if (user.isNotBlank()) b.header("Authorization", Credentials.basic(user, pass))
        if (depth != null) b.header("Depth", depth)
        return b.method(method, body).build()
    }

    /** 连一下并列出根目录：用来做「测试连接」 */
    fun test(): Pair<Boolean, String> {
        return try {
            val res = http.newCall(build("", "PROPFIND", "".toRequestBody(md), "0")).execute()
            res.use {
                when {
                    it.code in 200..299 -> true to "能连上（${it.code}）"
                    it.code == 401 || it.code == 403 -> false to "连上了但认证没过（${it.code}）"
                    it.code == 404 -> false to "地址不对：服务器上没有这个路径（404）"
                    else -> false to "服务器回了 ${it.code}"
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: e.toString())
        }
    }

    /** 列一个目录（Depth:1）。404 = 远端还没有这个目录（第一次同步必然如此）→ 当空；其它错误抛出去 */
    fun list(path: String): List<Entry> {
        val res = http.newCall(build(path, "PROPFIND", "".toRequestBody(md), "1")).execute()
        res.use {
            val text = it.body?.string() ?: ""
            if (it.code == 404) {
                Logs.i("dav.list missing dir=$path（远端还没有这个目录）")
                return emptyList()
            }
            if (it.code !in 200..299) throw IllegalStateException("PROPFIND 失败：${it.code}")
            return parseMultiStatus(text, path)
        }
    }

    fun get(path: String): ByteArray {
        val res = http.newCall(build(path, "GET")).execute()
        res.use {
            if (it.code !in 200..299) throw IllegalStateException("GET $path 失败：${it.code}")
            return it.body?.bytes() ?: ByteArray(0)
        }
    }

    fun put(path: String, bytes: ByteArray) {
        val res = http.newCall(build(path, "PUT", bytes.toRequestBody(md))).execute()
        res.use {
            if (it.code !in 200..299) throw IllegalStateException("PUT $path 失败：${it.code}")
        }
    }

    fun mkcol(path: String): Boolean {
        val res = http.newCall(build(path, "MKCOL")).execute()
        res.use {
            val ok = it.code in 200..299 || it.code == 405   // 405 = 已经存在
            if (!ok && it.code != 409) throw IllegalStateException("MKCOL $path 失败：${it.code}")
            return ok
        }
    }

    fun delete(path: String): Boolean {
        val res = http.newCall(build(path, "DELETE")).execute()
        res.use { return it.code in 200..299 || it.code == 404 }
    }

    /** 逐级把远端目录建出来（一层层 MKCOL），返回是否有 409（父目录没建好）这种情况 */
    fun mkdirs(relDir: String) {
        val parts = relDir.trim('/').split("/").filter { it.isNotBlank() }
        var cur = ""
        for (p in parts) {
            cur = if (cur.isBlank()) p else "$cur/$p"
            runCatching { mkcol(cur) }.onFailure { Logs.e("dav.mkcol failed path=$cur err=${it.message}") }
        }
    }

    /** 解析 207 Multi-Status：只用正则取 href / 长度 / 时间 / etag / 是否目录（够用且不怕命名空间前缀写法不同） */
    private fun parseMultiStatus(xml: String, listedPath: String): List<Entry> {
        val out = ArrayList<Entry>()
        val self = listedPath.trim('/')
        val blocks = Regex("<[^>]*response[^>]*>([\\s\\S]*?)</[^>]*response>", RegexOption.IGNORE_CASE).findAll(xml)
        for (b in blocks) {
            val body = b.groupValues[1]
            val href = Regex("<[^>]*href[^>]*>([\\s\\S]*?)</[^>]*href>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim() ?: continue
            val raw = java.net.URLDecoder.decode(href, "UTF-8")
            // 按配置的 base 路径截（不能写死 /dav：Nextcloud 这类是 /remote.php/dav/files/xxx）
            val rel = raw.trimEnd('/').removePrefix(basePath.trimEnd('/')).trim('/')
            if (rel.isEmpty() || rel == self) continue                      // 目录自己不算
            val isDir = Regex("<[^>]*collection\\s*/?>", RegexOption.IGNORE_CASE).containsMatchIn(body)
            val size = Regex("<[^>]*getcontentlength[^>]*>(\\d+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val mtime = Regex("<[^>]*getlastmodified[^>]*>([^<]+)<", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim() ?: ""
            val etag = Regex("<[^>]*getetag[^>]*>([^<]*)<", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()?.trim('"') ?: ""
            out.add(Entry(rel, size, parseHttpDate(mtime), etag, isDir))
        }
        return out
    }

    /** HTTP 日期 → 毫秒（解析不了就 0，避免因为格式差异把同步判成“变了”） */
    private fun parseHttpDate(s: String): Long {
        if (s.isBlank()) return 0
        val fmts = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss 'GMT'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (f in fmts) {
            runCatching {
                val df = java.text.SimpleDateFormat(f, java.util.Locale.US)
                df.timeZone = java.util.TimeZone.getTimeZone("GMT")
                return df.parse(s)?.time ?: 0
            }
        }
        return 0
    }
}
