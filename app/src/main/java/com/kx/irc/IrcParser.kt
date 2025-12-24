package com.kx.irc

data class IrcLine(
    val raw: String,
    val tags: Map<String, String?>,
    val prefix: String?,
    val command: String,
    val params: List<String>,
    val trailing: String?
)

fun parseIrcLine(line: String): IrcLine {
    var working = line
    var tags: Map<String, String?> = emptyMap()
    if (working.startsWith("@")) {
        val end = working.indexOf(' ')
        if (end > 0) {
            tags = parseTags(working.substring(1, end))
            working = working.substring(end + 1)
        }
    }
    var prefix: String? = null
    if (working.startsWith(":")) {
        val end = working.indexOf(' ')
        if (end > 0) {
            prefix = working.substring(1, end)
            working = working.substring(end + 1)
        }
    }

    val trailingIndex = working.indexOf(" :")
    val trailing = if (trailingIndex >= 0) {
        val value = working.substring(trailingIndex + 2)
        working = working.substring(0, trailingIndex)
        value
    } else {
        null
    }

    val parts = working.split(' ').filter { it.isNotBlank() }
    val command = parts.firstOrNull() ?: ""
    val params = if (parts.size > 1) parts.drop(1) else emptyList()

    return IrcLine(
        raw = line,
        tags = tags,
        prefix = prefix,
        command = command,
        params = params,
        trailing = trailing
    )
}

fun parseNick(prefix: String?): String {
    if (prefix == null) return ""
    val end = prefix.indexOf('!')
    return if (end > 0) prefix.substring(0, end) else prefix
}

private fun parseTags(tagSection: String): Map<String, String?> {
    if (tagSection.isBlank()) return emptyMap()
    return tagSection.split(';').mapNotNull { tag ->
        if (tag.isBlank()) return@mapNotNull null
        val parts = tag.split('=', limit = 2)
        val key = parts[0]
        val value = if (parts.size > 1) parts[1] else null
        key to value
    }.toMap()
}
