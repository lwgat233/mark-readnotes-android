package dev.markreadnotes.shell

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.markreadnotes.Logs
import dev.markreadnotes.export.ExportRepo
import dev.markreadnotes.notes.NotesRepo
import org.json.JSONObject

/**
 * 目录授权：笔记根（REQ_TREE）与导出根（REQ_EXPORT_TREE）各一次系统目录选择器。
 * 规则：takePersistableUriPermission 拿到长期读+写；选完把结果推给页面并让它接着做完原来的动作。
 */
class SafPicker(
    private val activity: Activity,
    private val notes: NotesRepo,
    private val export: ExportRepo,
    private val push: (String, JSONObject) -> Unit
) {

    companion object {
        const val REQ_TREE = 1001
        const val REQ_EXPORT_TREE = 1002
    }

    fun pick(requestCode: Int) {
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
            activity.startActivityForResult(i, requestCode)
            Logs.i(if (requestCode == REQ_EXPORT_TREE) "exp.pick opened" else "src.pick opened")
        } catch (e: Exception) {
            Logs.e("pick failed req=$requestCode err=${e.message}")
        }
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_TREE && requestCode != REQ_EXPORT_TREE) return
        val isExport = requestCode == REQ_EXPORT_TREE
        val event = if (isExport) "exp.changed" else "src.changed"
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            push(event, JSONObject().put("cancelled", true))
            return
        }
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Logs.e("takePersistable failed err=${e.message}")
        }
        Thread {
            try {
                if (isExport) push(event, export.attachExportTree(uri)) else push(event, notes.attachTree(uri))
            } catch (e: Throwable) {
                Logs.e("attach failed req=$requestCode err=${e.message}")
                push(event, JSONObject().put("error", e.message ?: "授权失败"))
            }
        }.start()
    }
}
