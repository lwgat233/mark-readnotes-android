package dev.markreadnotes.ops

import dev.markreadnotes.BuildConfig
import dev.markreadnotes.Logs
import org.json.JSONObject

/** app.* —— 应用自身的信息与日志（界面自证“跑的是哪一版”靠这里） */
object AppOps {
    fun handle(op: String, a: JSONObject, buildTag: String): Any = when (op) {
        "app.info" -> JSONObject()
            .put("name", "随笔")
            .put("version", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("build", buildTag)

        "app.log" -> Logs.tail(a.optInt("n", 60))

        else -> throw IllegalArgumentException("未知 op：$op")
    }
}
