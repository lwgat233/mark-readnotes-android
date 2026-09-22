package dev.markreadnotes

import android.util.Log
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 环形日志缓冲：界面/接口/证据脚本都能读回来（验收要读“事实”，不是读“感觉”） */
object Logs {
    private const val TAG = "markreadnotes"
    private const val MAX = 400
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(level: String, msg: String) {
        buf.addLast("${fmt.format(Date())} $level $msg")
        while (buf.size > MAX) buf.removeFirst()
        Log.println(if (level == "E") Log.ERROR else Log.INFO, TAG, msg)
    }

    fun i(msg: String) = add("I", msg)
    fun e(msg: String) = add("E", msg)

    @Synchronized
    fun tail(n: Int): JSONArray {
        val arr = JSONArray()
        for (l in buf.toList().takeLast(n)) arr.put(l)
        return arr
    }
}
