package dev.markreadnotes.ops

import android.content.Context
import dev.markreadnotes.export.ExportRepo
import dev.markreadnotes.shell.ShareIntent
import org.json.JSONObject

/** exp.* —— 导出板块：算规则、写盘、交给系统分享 */
object ExportOps {
    fun handle(op: String, a: JSONObject, export: ExportRepo, ctx: Context): Any = when (op) {
        "exp.tags" -> export.tagStats()
        "exp.plan" -> export.exportPlan(JsonArgs.tags(a, "exclude"))
        "exp.writeDir" -> export.writeExport(JsonArgs.tags(a, "exclude"))
        "exp.share" -> ShareIntent.share(ctx, export.shareFiles(JsonArgs.tags(a, "exclude")))
        else -> throw IllegalArgumentException("未知 op：$op")
    }
}
