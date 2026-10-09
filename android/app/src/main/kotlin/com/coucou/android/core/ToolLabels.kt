package com.coucou.android.core

/**
 * What the agent is doing, in words a person reads. The desktop sends the last step as the agent
 * reported it, often a raw tool name ("ask_question", "Bash", "Read"). Known tools get a plain
 * sentence; an unknown one is only cleaned (no underscores, first letter capital); free text such
 * as "Running npm test" is left alone. Used by the Home card and the island, so both say the same.
 */
object ToolLabels {
    private val KNOWN = mapOf(
        "askuserquestion" to "Asking a question",
        "askquestion" to "Asking a question",
        "bash" to "Running a command",
        "shell" to "Running a command",
        "runshellcommand" to "Running a command",
        "execcommand" to "Running a command",
        "read" to "Reading files",
        "readfile" to "Reading files",
        "readmanyfiles" to "Reading files",
        "edit" to "Editing files",
        "multiedit" to "Editing files",
        "write" to "Editing files",
        "writefile" to "Editing files",
        "notebookedit" to "Editing files",
        "applypatch" to "Editing files",
        "grep" to "Searching files",
        "glob" to "Searching files",
        "ls" to "Looking at files",
        "websearch" to "Searching the web",
        "googlewebsearch" to "Searching the web",
        "webfetch" to "Reading a web page",
        "task" to "Running a sub-agent",
        "todowrite" to "Planning",
        "exitplanmode" to "Planning",
    )

    private const val SEP = " · "

    /** "ask_question" and "AskQuestion" both reduce to "askquestion". */
    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun looksLikeAName(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ':' }

    fun label(raw: String): String {
        val text = raw.trim().removeSuffix("·").trim()
        if (text.isEmpty()) return ""
        // "Bash · npm test": the tool is named, the rest is what it does.
        val at = text.indexOf(SEP)
        if (at > 0) {
            val head = text.substring(0, at).trim()
            val rest = text.substring(at + SEP.length).trim()
            KNOWN[key(head)]?.let { return if (rest.isEmpty()) it else "$it$SEP$rest" }
        }
        if (!looksLikeAName(text)) return text
        return KNOWN[key(text)] ?: clean(text)
    }

    private val SEPARATORS = Regex("[_\\-.:]+")
    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")

    /** "mcp__github__list_issues" to "Mcp github list issues"; "FooBar" to "Foo bar". */
    fun clean(name: String): String =
        name.replace(SEPARATORS, " ").replace(CAMEL, " ").trim().lowercase().replaceFirstChar { it.uppercaseChar() }
}
