package dev.markreadnotes.ops

import org.json.JSONArray
import org.json.JSONObject

/** args 里的标签：既收数组也收空格/逗号分隔的字符串（界面两种都在用） */
object JsonArgs {
    fun tags(a: JSONObject, key: String): List<String> {
        val arr: JSONArray? = a.optJSONArray(key)
        if (arr != null) {
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            return out
        }
        return a.optString(key).split(" ", ",").filter { it.isNotBlank() }
    }
}
