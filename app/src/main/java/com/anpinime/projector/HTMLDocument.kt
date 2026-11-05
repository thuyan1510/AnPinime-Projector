package com.anpinime.projector

import org.jsoup.parser.Parser

object HTMLDocument {
    data class Link(
        val href: String,
        val text: String,
        val rawAttributes: String,
        val ancestorTags: List<String> = emptyList(),
        val ancestorClasses: List<String> = emptyList()
    )

    private val trackedContainerTags = setOf(
        "nav", "header", "footer", "form", "div", "section", "article", "aside", "ul", "ol", "li", "main"
    )

    private val tokenAttrRegex = Regex(
        """<(/?)([a-zA-Z][a-zA-Z0-9]*)\b([^>]*?)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val classIdAttrRegex = Regex(
        """\b(?:class|id)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
        RegexOption.IGNORE_CASE
    )
    private val hrefAttrRegex = Regex(
        """\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
        RegexOption.IGNORE_CASE
    )
    private val titleAttrRegex = Regex(
        """\btitle\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE
    )
    private val titleTagRegex = Regex(
        """<title[^>]*>(.*?)</title>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val metaTagRegex = Regex(
        """<meta\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val metaContentRegex = Regex(
        """\bcontent\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE
    )
    private val stripScriptStyleRegex = Regex(
        """<(script|style)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val stripTagsRegex = Regex("""<[^>]+>""", RegexOption.DOT_MATCHES_ALL)
    private val whitespaceRegex = Regex("""\s+""")

    private data class StackEntry(val tag: String, val classesAndIds: String)
    private data class OpenAnchor(
        val href: String,
        val attrs: String,
        val contentStart: Int,
        val ancestorTags: List<String>,
        val ancestorClasses: List<String>
    )

    fun links(html: String): List<Link> {
        val results = ArrayList<Link>()
        val stack = ArrayList<StackEntry>()
        var currentAnchor: OpenAnchor? = null

        for (match in tokenAttrRegex.findAll(html)) {
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2].lowercase()
            val attrs = match.groupValues[3]

            if (tagName == "a") {
                if (!isClosing) {
                    val hrefMatch = hrefAttrRegex.find(attrs)
                    val hrefValue = hrefMatch?.let {
                        it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                    }?.trim()
                    if (!hrefValue.isNullOrEmpty()) {
                        val contentStart = match.range.last + 1
                        val aTags = stack.map { it.tag }
                        val aClasses = stack.mapNotNull { it.classesAndIds.ifEmpty { null } }
                        currentAnchor = OpenAnchor(
                            href = decodeEntities(hrefValue),
                            attrs = attrs,
                            contentStart = contentStart,
                            ancestorTags = aTags,
                            ancestorClasses = aClasses
                        )
                    }
                } else if (currentAnchor != null) {
                    val anchor = currentAnchor!!
                    currentAnchor = null
                    val rawInner = if (anchor.contentStart <= match.range.first) {
                        html.substring(anchor.contentStart, match.range.first)
                    } else {
                        ""
                    }
                    var label = textContent(rawInner)
                    if (label.isEmpty()) {
                        val titleMatch = titleAttrRegex.find(anchor.attrs)
                        val titleVal = titleMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                        if (!titleVal.isNullOrEmpty()) {
                            label = textContent(titleVal)
                        }
                    }
                    results.add(
                        Link(
                            href = anchor.href,
                            text = label,
                            rawAttributes = anchor.attrs,
                            ancestorTags = anchor.ancestorTags,
                            ancestorClasses = anchor.ancestorClasses
                        )
                    )
                }
                continue
            }

            if (!trackedContainerTags.contains(tagName)) continue

            if (isClosing) {
                val idx = stack.indexOfLast { it.tag == tagName }
                if (idx >= 0) {
                    stack.subList(idx, stack.size).clear()
                }
            } else {
                val selfClosing = attrs.trimEnd().endsWith("/")
                if (!selfClosing) {
                    val parts = ArrayList<String>()
                    for (cm in classIdAttrRegex.findAll(attrs)) {
                        val v = cm.groupValues[1].ifEmpty { cm.groupValues[2].ifEmpty { cm.groupValues[3] } }.trim()
                        if (v.isNotEmpty()) parts.add(v.lowercase())
                    }
                    stack.add(StackEntry(tagName, parts.joinToString(" ")))
                }
            }
        }
        return results
    }

    fun title(html: String): String? {
        val m = titleTagRegex.find(html) ?: return null
        val text = textContent(m.groupValues[1])
        return text.ifEmpty { null }
    }

    fun metaContent(html: String, keys: List<String>): String? {
        for (tagMatch in metaTagRegex.findAll(html)) {
            val tag = tagMatch.value
            val matchesKey = keys.any { key ->
                val escaped = Regex.escape(key)
                Regex("""\b(?:property|name)\s*=\s*["']$escaped["']""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(tag)
            }
            if (!matchesKey) continue
            val cm = metaContentRegex.find(tag) ?: continue
            val raw = cm.groupValues[1].ifEmpty { cm.groupValues[2] }
            val text = textContent(raw)
            if (text.isNotEmpty()) return text
        }
        return null
    }

    fun textContent(htmlFragment: String): String {
        if (htmlFragment.isEmpty()) return ""
        val withoutScripts = stripScriptStyleRegex.replace(htmlFragment, " ")
        val withoutTags = stripTagsRegex.replace(withoutScripts, " ")
        val decoded = decodeEntities(withoutTags)
        return whitespaceRegex.replace(decoded, " ").trim()
    }

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return try {
            Parser.unescapeEntities(text, false)
        } catch (_: Throwable) {
            text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
        }
    }
}
