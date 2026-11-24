package com.ht.ensemble

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

/** A project workspace. Paths are relative; sub-folders are fine, escaping the root is not. */
interface ProjectFiles {
    fun write(path: String, text: String)
    fun read(path: String): String?
    fun list(): List<String>

    companion object {
        /** Keep sub-folders, drop `..`, `.`, absolute roots and characters no filesystem likes. */
        fun clean(p: String): String = p.trim().replace('\\', '/').trimStart('/').split('/')
            .filter { it.isNotBlank() && it != ".." && it != "." }
            .joinToString("/") { it.replace(Regex("""[:*?"<>|]"""), "_") }.take(120)
    }
}

class MemoryFiles : ProjectFiles {
    private val m = Collections.synchronizedMap(LinkedHashMap<String, String>())
    override fun write(path: String, text: String) { m[ProjectFiles.clean(path)] = text }
    override fun read(path: String): String? = m[ProjectFiles.clean(path)]
    override fun list(): List<String> = synchronized(m) { m.keys.toList() }
}

/** A real directory. Hidden files are skipped in [list] (the checkpoint lives in `.company.json`). */
class DirFiles(private val root: File) : ProjectFiles {
    init { root.mkdirs() }
    private fun f(p: String) = File(root, ProjectFiles.clean(p))
    override fun write(path: String, text: String) { f(path).also { it.parentFile?.mkdirs() }.writeText(text) }
    override fun read(path: String): String? = f(path).takeIf { it.isFile }?.readText()
    override fun list(): List<String> = root.walkTopDown()
        .filter { it.isFile && it.relativeTo(root).path.split(File.separatorChar).none { s -> s.startsWith(".") } }
        .map { it.relativeTo(root).path.replace('\\', '/') }.toList()
}

/** Pull JSON out of chatty model replies: code fences, prose around it, braces inside strings. No regex. */
object JsonPick {
    fun obj(text: String): JSONObject? {
        var i = text.indexOf('{')
        while (i >= 0) {
            val end = close(text, i)
            if (end > i) runCatching { return JSONObject(text.substring(i, end + 1)) }
            i = text.indexOf('{', i + 1)
        }
        return null
    }

    private fun close(s: String, from: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (k in from until s.length) {
            val c = s[k]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return k }
            }
        }
        return -1
    }

    fun strings(a: JSONArray?): List<String> =
        a?.let { (0 until it.length()).mapNotNull { i -> it.optString(i).trim().takeIf { s -> s.isNotBlank() } } }.orEmpty()

    fun map(o: JSONObject?): Map<String, String> = o?.let { j -> j.keys().asSequence().associateWith { k -> j.optString(k) } }.orEmpty()
}

/**
 * Turn the fenced code blocks of a markdown deliverable into files. The name comes from the line
 * right above the block (`**src/app.py**`, `File: index.html`) or a first-line comment
 * (`// file: Main.kt`); otherwise from the language.
 */
object CodeFiles {
    private val FENCE = Regex("""```([\w+#.-]*)[^\n]*\n([\s\S]*?)```""")
    private val NAME = Regex("""([\w./-]+\.[A-Za-z0-9]{1,6})""")
    private val EXT = mapOf("python" to "py", "py" to "py", "kotlin" to "kt", "kt" to "kt", "java" to "java", "javascript" to "js", "js" to "js",
        "typescript" to "ts", "ts" to "ts", "html" to "html", "css" to "css", "json" to "json", "bash" to "sh", "sh" to "sh", "shell" to "sh",
        "yaml" to "yml", "yml" to "yml", "xml" to "xml", "sql" to "sql", "c" to "c", "cpp" to "cpp", "c++" to "cpp", "go" to "go",
        "rust" to "rs", "rs" to "rs", "swift" to "swift", "dart" to "dart", "toml" to "toml")
    private val DEFAULT = mapOf("html" to "index.html", "py" to "main.py", "js" to "app.js", "css" to "style.css", "kt" to "Main.kt", "java" to "Main.java", "sh" to "run.sh")

    fun extract(md: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val count = HashMap<String, Int>()
        for (m in FENCE.findAll(md)) {
            val lang = m.groupValues[1].lowercase()
            val code = m.groupValues[2].trimEnd() + "\n"
            if (code.lines().size < 3 || lang in setOf("text", "txt", "output", "console", "plaintext", "md", "markdown")) continue
            val before = md.substring(0, m.range.first).trimEnd().lines().lastOrNull().orEmpty().takeLast(120)
            val first = code.lineSequence().first()
            val hinted = NAME.findAll(before).lastOrNull()?.value?.takeIf { Regex("""(file|파일|`|\*\*|:|#)""", RegexOption.IGNORE_CASE).containsMatchIn(before) }
                ?: Regex("""^\s*(?://|#|<!--|--)\s*(?:file(?:name)?|파일)\s*[:：]\s*([\w./-]+)""", RegexOption.IGNORE_CASE).find(first)?.groupValues?.get(1)
            val ext = EXT[lang] ?: hinted?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: continue
            var name = ProjectFiles.clean(hinted ?: DEFAULT[ext] ?: "code.$ext")
            if (name in out) {
                val n = (count[name] ?: 1) + 1
                count[name] = n
                name = name.substringBeforeLast('.') + "_" + n + "." + name.substringAfterLast('.')
            }
            out[name] = code
        }
        return out.toList()
    }
}
