package dev.markreadnotes

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * WebView 壳。前端不放 file://（不是安全源、拿不到 crypto 等 API），
 * 用 shouldInterceptRequest 合成 https://appassets.androidplatform.net 源，自己映射 assets。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_TREE = 1001
        private const val ORIGIN = "appassets.androidplatform.net"
        private const val BUILD_TAG = "0.1.0+r4"
    }

    private lateinit var web: WebView
    private lateinit var repo: Repo
    private lateinit var ops: Ops

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo(this)
        ops = Ops(this, repo, BUILD_TAG) { runOnUiThread { pickTree() } }

        // 冷启动只读 SQLite，不枚举目录（懒扫描）
        Logs.i("app.start build=$BUILD_TAG scan=skipped(reason=startup)")
        Logs.i("idx.state=" + repo.sourceJson().toString())

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0f1115"))
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Logs.i("js ${m.message()} @${m.lineNumber()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != ORIGIN) return null
                val path = (u.path ?: "").removePrefix("/")
                if (path.isBlank()) return null
                // 笔记里的本地图片：/file/<相对路径>，按笔记根解析后流式返回（Uri.path 已是解码后的）
                if (path.startsWith("file/")) return serveImage(path.removePrefix("file/"))
                return try {
                    WebResourceResponse(mimeOf(path), null, 200, "OK", mapOf("Cache-Control" to "no-store"), assets.open(path))
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream("404 $path".toByteArray()))
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == ORIGIN) return false
                ops.dispatch(JSONObject().put("id", "nav").put("op", "ui.open").put("args", JSONObject().put("url", req.url.toString())).toString())
                return true
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "mrbridge", setOf("http://$ORIGIN")) { _, message, _, _, reply ->
                val raw = message.data ?: return@addWebMessageListener
                Thread {
                    val out = ops.dispatch(raw)
                    runOnUiThread { runCatching { reply.postMessage(out) } }
                }.start()
            }
        } else {
            Logs.e("bridge=unavailable（本机 WebView 不支持 WEB_MESSAGE_LISTENER）")
        }

        // 壳子走 http 虚拟源（本地拦截，不出网）：笔记里的 http 图片若挂在 https 页面上，会被浏览器
        // 按“明文图片”直接拦掉（MIXED_CONTENT_COMPATIBILITY_MODE 也拦，实测日志：
        // "requested an insecure image … This request has been blocked"），而阅读随笔的图源大量是 http。
        // 改成 http 源后不再有混合内容问题；代价是页面不是安全源（本项目不用 crypto.subtle 这类能力）。
        web.loadUrl("http://$ORIGIN/ui/index.html")
    }

    private fun mimeOf(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".html") -> "text/html"
            p.endsWith(".js") -> "application/javascript"
            p.endsWith(".css") -> "text/css"
            p.endsWith(".json") -> "application/json"
            p.endsWith(".svg") -> "image/svg+xml"
            p.endsWith(".png") -> "image/png"
            p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
            p.endsWith(".gif") -> "image/gif"
            p.endsWith(".webp") -> "image/webp"
            p.endsWith(".bmp") -> "image/bmp"
            p.endsWith(".heic") -> "image/heic"
            p.endsWith(".woff2") -> "font/woff2"
            p.endsWith(".woff") -> "font/woff"
            p.endsWith(".md") -> "text/markdown"
            else -> "text/plain"
        }
    }

    /** 笔记里的本地图片：从笔记根解析 → 流式返回；找不到就 404（页面里表现为图裂，不静默当成功） */
    private fun serveImage(rel: String): WebResourceResponse {
        val notFound = {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(),
                ByteArrayInputStream("image not found: $rel".toByteArray()))
        }
        val doc = try { repo.findImage(rel) } catch (e: Throwable) { null } ?: return notFound()
        val stream = repo.openImage(doc.docId) ?: return notFound()
        return WebResourceResponse(doc.mime.ifBlank { mimeOf(rel) }, null, 200, "OK", mapOf("Cache-Control" to "max-age=30"), stream)
    }

    /** 后端 → 前端事件（目录授权是异步的，结束时推给页面） */
    private fun push(type: String, data: JSONObject) {
        val payload = JSONObject().put("type", type).put("data", data).toString()
        runOnUiThread {
            web.evaluateJavascript("window.mrpush && window.mrpush(${JSONObject.quote(payload)})", null)
        }
    }

    private fun pickTree() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            runCatching {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/root/primary"))
            }
        }
        try {
            startActivityForResult(i, REQ_TREE)
            Logs.i("src.pick opened")
        } catch (e: Exception) {
            Logs.e("src.pick failed err=${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            push("src.changed", JSONObject().put("cancelled", true))
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Logs.e("takePersistable failed err=${e.message}")
        }
        Thread {
            try {
                val r = repo.attachTree(uri)
                push("src.changed", r)
            } catch (e: Throwable) {
                Logs.e("src.attach failed err=${e.message}")
                push("src.changed", JSONObject().put("error", e.message ?: "授权失败"))
            }
        }.start()
    }

    /** 返回键：先让页面决定（关白板/关小窗），页面说 0 才交回系统 */
    override fun onBackPressed() {
        Logs.i("back.enter")
        web.evaluateJavascript("window.mrBack ? (window.mrBack() ? '1' : '0') : '0'") { v ->
            val handled = v != null && v.trim('"') == "1"
            Logs.i("back.js=$v handled=$handled finished=$isFinishing")
            if (!handled && !isFinishing) super.onBackPressed()
        }
    }
}
