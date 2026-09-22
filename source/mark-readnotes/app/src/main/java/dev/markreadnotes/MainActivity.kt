package dev.markreadnotes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.markreadnotes.export.ExportRepo
import dev.markreadnotes.notes.NotesRepo
import dev.markreadnotes.ops.OpsRouter
import dev.markreadnotes.shell.SafPicker
import dev.markreadnotes.shell.WebShell

/**
 * 只有一个 Activity：装配各个板块（Store → notes/export → op 层 → WebView 壳）。
 * 界面全在前端 assets 里；这里只做装配与系统回调转发。
 */
class MainActivity : Activity() {

    companion object {
        private const val BUILD_TAG = "0.1.0+r11"
    }

    private lateinit var store: Store
    private lateinit var notes: NotesRepo
    private lateinit var export: ExportRepo
    private lateinit var shell: WebShell
    private lateinit var picker: SafPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        notes = NotesRepo(store)
        export = ExportRepo(store)
        picker = SafPicker(this, notes, export) { type, data -> shell.push(type, data) }
        val router = OpsRouter(
            this, notes, export, BUILD_TAG,
            { runOnUiThread { picker.pick(SafPicker.REQ_TREE) } },
            { runOnUiThread { picker.pick(SafPicker.REQ_EXPORT_TREE) } }
        )

        // 冷启动只读 SQLite，不枚举目录（懒扫描）
        Logs.i("app.start build=$BUILD_TAG scan=skipped(reason=startup)")
        Logs.i("idx.state=" + notes.sourceJson().toString())

        shell = WebShell(this, router, notes)
        setContentView(shell.attach())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        picker.onResult(requestCode, resultCode, data)
    }

    /** 返回键先问页面（关白板/关小窗），页面说没处理才退出 */
    override fun onBackPressed() {
        shell.handleBack()
    }
}
