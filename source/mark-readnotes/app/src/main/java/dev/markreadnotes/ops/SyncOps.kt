package dev.markreadnotes.ops

import dev.markreadnotes.sync.SyncRepo
import org.json.JSONObject

/** sync.* —— WebDAV 同步（配置 / 连接测试 / 计划 / 执行 / 冲突三选）。判据见 docs/同步规格.md */
object SyncOps {
    fun handle(op: String, a: JSONObject, sync: SyncRepo): Any = when (op) {
        "sync.get" -> sync.configJson()

        "sync.set" -> sync.setConfig(a.optString("url"), a.optString("user"), a.optString("pass"))

        "sync.test" -> sync.test()

        "sync.plan" -> sync.plan()

        "sync.run" -> sync.run()

        "sync.resolve" -> sync.resolve(a.optString("relPath"), a.optString("choice"))

        else -> throw IllegalArgumentException("未知 op：$op")
    }
}
