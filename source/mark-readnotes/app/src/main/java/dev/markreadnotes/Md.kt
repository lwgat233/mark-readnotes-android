package dev.markreadnotes

/** 一个块（= 一条随笔）。raw 是写回 md 的原文，body 是去掉归档栏之后的正文。 */
data class MdBlock(
    val heading: String,
    val tags: List<String>,
    val body: String,
    val raw: String,
    val index: Int
) {
    val tag: String get() = tags.firstOrNull() ?: Md.DEFAULT_TAG
}

/**
 * markdown 的块切分与写回。
 *
 * 规则（用户 2026-09-22 确认）：
 * - 一级标题（'# '，恰好一个井号）分块；
 * - 归档标签 = 一级标题后面第一行里的标签，一行可写多个，取其中**第一个**决定归属文件；
 *   判定不写死“紧邻标题那一行”，按块内按行顺序找第一个含标签的行（用户纠正过：第一个 tag 文本不是第一行）。
 */
object Md {
    const val DEFAULT_TAG = "unsorted"
    private val H1 = Regex("^#\\s+(.*)$")
    private val TAG_TOKEN = Regex("(?:^|\\s)#([^\\s#]+)")

    fun split(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val starts = ArrayList<Int>()
        lines.forEachIndexed { i, l -> if (H1.matches(l)) starts.add(i) }
        val out = ArrayList<MdBlock>()
        starts.forEachIndexed { k, s ->
            val end = if (k + 1 < starts.size) starts[k + 1] else lines.size
            val heading = H1.find(lines[s])!!.groupValues[1].trim()
            var tagLineIdx = -1
            var tags = emptyList<String>()
            for (i in s + 1 until end) {
                val hits = TAG_TOKEN.findAll(lines[i]).toList()
                if (hits.isNotEmpty()) {
                    tagLineIdx = i
                    tags = hits.map { it.groupValues[1] }.filter { it.isNotBlank() }
                    break
                }
            }
            val bodyLines = if (tagLineIdx >= 0) lines.subList(tagLineIdx + 1, end) else lines.subList(s + 1, end)
            val body = trimBlank(bodyLines).joinToString("\n")
            val effTags = if (tags.isEmpty()) listOf(DEFAULT_TAG) else tags
            out.add(MdBlock(heading, effTags, body, renderBlock(heading, effTags, body), k))
        }
        return out
    }

    fun renderBlock(heading: String, tags: List<String>, body: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(heading.trim()).append('\n')
        sb.append(tags.map { "#$it" }.joinToString(" ")).append('\n')
        if (body.isNotBlank()) sb.append(body.trim()).append('\n')
        return sb.toString()
    }

    fun renderFile(blocks: List<MdBlock>): String {
        if (blocks.isEmpty()) return ""
        return blocks.joinToString("\n") { it.raw.trimEnd() } + "\n"
    }

    /** 标签 → 文件名：目录与文件名一律 ASCII（用户要求） */
    fun fileNameForTag(tag: String): String {
        val ok = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        return if (ok.matches(tag)) "$tag.md" else "t-${sha1(tag).take(8)}.md"
    }

    private fun sha1(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun trimBlank(ls: List<String>): List<String> {
        var a = 0
        var b = ls.size
        while (a < b && ls[a].isBlank()) a++
        while (b > a && ls[b - 1].isBlank()) b--
        return ls.subList(a, b)
    }
}
