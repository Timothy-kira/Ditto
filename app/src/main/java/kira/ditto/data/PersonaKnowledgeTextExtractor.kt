package kira.ditto.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup

private const val MaxKnowledgeBytes = 25L * 1024L * 1024L

@Volatile
internal var personaPdfTextExtractor: ((ByteArray) -> String)? = null

internal fun extractPersonaKnowledgeText(
    bytes: ByteArray,
    displayName: String,
    mimeType: String,
): String {
    if (bytes.size > MaxKnowledgeBytes) {
        error("File is too large. Keep knowledge uploads under 25 MB.")
    }
    val name = displayName.lowercase()
    val mime = mimeType.lowercase().substringBefore(';').trim()
    if (mime.startsWith("image/") || name.matches(Regex(".*\\.(png|jpe?g|gif|webp|heic|bmp)$"))) {
        return "Image: $displayName"
    }
    val kind = inferKnowledgeKind(bytes, name, mime)
    val extracted = when (kind) {
        KnowledgeKind.Pdf -> extractPdf(bytes)
        KnowledgeKind.Docx -> extractDocx(bytes)
        KnowledgeKind.Pptx -> extractPptx(bytes)
        KnowledgeKind.Xlsx -> extractXlsx(bytes)
        KnowledgeKind.Html -> extractHtml(bytes)
        KnowledgeKind.Rtf -> extractRtf(bytes)
        KnowledgeKind.LegacyOffice -> error(
            "Legacy .doc/.ppt/.xls isn't supported. Export as DOCX, PPTX, or PDF.",
        )
        KnowledgeKind.Text -> decodeUtf8(bytes)
        KnowledgeKind.Unknown -> {
            val asText = decodeUtf8(bytes)
            if (looksLikePrintableText(asText)) asText else ""
        }
    }.trim()
    return extracted.ifBlank {
        error("Couldn't extract text from $displayName")
    }
}

internal enum class KnowledgeKind {
    Pdf, Docx, Pptx, Xlsx, Html, Rtf, Text, LegacyOffice, Unknown
}

internal fun inferKnowledgeKind(
    bytes: ByteArray,
    displayName: String,
    mimeType: String,
): KnowledgeKind {
    val name = displayName.lowercase()
    val mime = mimeType.lowercase()
    when {
        name.endsWith(".pdf") || mime == "application/pdf" || startsWith(bytes, "%PDF") ->
            return KnowledgeKind.Pdf
        name.endsWith(".docx") || mime.contains("wordprocessingml") -> return KnowledgeKind.Docx
        name.endsWith(".pptx") || mime.contains("presentationml") -> return KnowledgeKind.Pptx
        name.endsWith(".xlsx") || mime.contains("spreadsheetml") -> return KnowledgeKind.Xlsx
        name.endsWith(".doc") || name.endsWith(".ppt") || name.endsWith(".xls") ||
            mime == "application/msword" ||
            mime == "application/vnd.ms-powerpoint" ||
            mime == "application/vnd.ms-excel" -> return KnowledgeKind.LegacyOffice
        name.endsWith(".html") || name.endsWith(".htm") || mime == "text/html" ->
            return KnowledgeKind.Html
        name.endsWith(".rtf") || mime == "application/rtf" || mime == "text/rtf" ||
            startsWith(bytes, "{\\rtf") -> return KnowledgeKind.Rtf
        name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt") ||
            name.endsWith(".csv") || name.endsWith(".json") || name.endsWith(".yml") ||
            name.endsWith(".yaml") || name.endsWith(".xml") || name.endsWith(".log") ||
            mime.startsWith("text/") || mime == "application/json" ||
            mime == "application/xml" -> return KnowledgeKind.Text
    }
    if (startsWith(bytes, "PK")) {
        val names = zipEntryNames(bytes)
        return when {
            names.any { it.startsWith("word/") } -> KnowledgeKind.Docx
            names.any { it.startsWith("ppt/") } -> KnowledgeKind.Pptx
            names.any { it.startsWith("xl/") } -> KnowledgeKind.Xlsx
            else -> KnowledgeKind.Unknown
        }
    }
    return KnowledgeKind.Unknown
}

private fun extractPdf(bytes: ByteArray): String {
    val fromBox = runCatching { personaPdfTextExtractor?.invoke(bytes).orEmpty() }.getOrDefault("").trim()
    if (fromBox.isNotBlank()) return fromBox
    return extractPdfLiteralText(bytes)
}

internal fun extractPdfLiteralText(bytes: ByteArray): String {
    val raw = String(bytes, Charsets.ISO_8859_1)
    val chunks = ArrayList<String>()
    Regex("""stream\r?\n([\s\S]*?)endstream""").findAll(raw).forEach { match ->
        val payload = match.groupValues[1].trimEnd('\r', '\n').toByteArray(Charsets.ISO_8859_1)
        val decoded = inflateOrRaw(payload)
        val text = extractPdfOperators(decoded)
        if (text.isNotBlank()) chunks += text
    }
    if (chunks.isEmpty()) {
        val text = extractPdfOperators(raw)
        if (text.isNotBlank()) chunks += text
    }
    return chunks.joinToString("\n").trim()
}

private fun inflateOrRaw(payload: ByteArray): String {
    val inflated = runCatching {
        val inflater = Inflater()
        inflater.setInput(payload)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count <= 0) break
            out.write(buffer, 0, count)
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()
    val bytes = if (inflated != null && inflated.isNotEmpty()) inflated else payload
    return String(bytes, Charsets.ISO_8859_1)
}

private fun extractPdfOperators(source: String): String {
    val parts = ArrayList<String>()
    Regex("""\((?:\\.|[^\\)])*\)\s*Tj""").findAll(source).forEach { match ->
        val inner = match.value.trim().removeSuffix("Tj").trim().removePrefix("(").removeSuffix(")")
        unescapePdf(inner).trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    }
    Regex("""\[(.*?)]\s*TJ""", RegexOption.DOT_MATCHES_ALL).findAll(source).forEach { match ->
        val joined = Regex("""\((?:\\.|[^\\)])*\)""").findAll(match.groupValues[1]).joinToString("") { literal ->
            unescapePdf(literal.value.removePrefix("(").removeSuffix(")"))
        }
        joined.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    }
    return parts.joinToString("\n")
}

private fun unescapePdf(raw: String): String = buildString {
    var index = 0
    while (index < raw.length) {
        val ch = raw[index]
        if (ch != '\\' || index == raw.lastIndex) {
            append(ch)
            index += 1
            continue
        }
        val next = raw[index + 1]
        when (next) {
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'b' -> append('\b')
            'f' -> append('\u000c')
            '(', ')', '\\' -> append(next)
            in '0'..'7' -> {
                val octal = raw.substring(index + 1).take(3).takeWhile { it in '0'..'7' }
                append(octal.toInt(8).toChar())
                index += octal.length
                continue
            }
            else -> append(next)
        }
        index += 2
    }
}

internal fun extractDocx(bytes: ByteArray): String {
    val xml = zipText(bytes, "word/document.xml") ?: return ""
    return xml.split(Regex("</w:p>")).map { paragraph ->
        collectXmlTagText(paragraph, "w:t").joinToString("")
    }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
}

internal fun extractPptx(bytes: ByteArray): String {
    val slides = zipTexts(bytes) { name ->
        name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
    }.sortedBy { it.first }
    return slides.joinToString("\n\n") { (_, xml) ->
        collectXmlTagText(xml, "a:t").joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }.trim()
}

internal fun extractXlsx(bytes: ByteArray): String {
    val shared = zipText(bytes, "xl/sharedStrings.xml").orEmpty()
    val sharedStrings = collectXmlTagText(shared, "t")
    val sheets = zipTexts(bytes) { name ->
        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
    }.sortedBy { it.first }
    if (sheets.isEmpty() && sharedStrings.isNotEmpty()) {
        return sharedStrings.joinToString("\n")
    }
    return sheets.joinToString("\n\n") { (_, xml) ->
        val rows = xml.split(Regex("</row>")).mapNotNull { row ->
            val cells = Regex("""<c\b([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(row)
                .map { cell ->
                    val attrs = cell.groupValues[1]
                    val body = cell.groupValues[2]
                    val isShared = Regex("""\st="s"""").containsMatchIn(attrs)
                    val inline = collectXmlTagText(body, "t").joinToString("")
                    if (inline.isNotBlank()) {
                        inline
                    } else {
                        val index = Regex("""<v>(.*?)</v>""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                        if (isShared && index != null) sharedStrings.getOrNull(index).orEmpty() else ""
                    }
                }
                .filter { it.isNotBlank() }
                .toList()
            cells.takeIf { it.isNotEmpty() }?.joinToString("\t")
        }
        rows.joinToString("\n")
    }.trim()
}

internal fun extractHtml(bytes: ByteArray): String =
    Jsoup.parse(decodeUtf8(bytes)).text().trim()

internal fun extractRtf(bytes: ByteArray): String {
    val raw = decodeUtf8(bytes)
    return raw
        .replace(Regex("""\\'[0-9a-fA-F]{2}""")) { match ->
            match.value.removePrefix("\\'").toIntOrNull(16)?.toChar()?.toString() ?: ""
        }
        .replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), " ")
        .replace(Regex("[{}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun decodeUtf8(bytes: ByteArray): String {
    val text = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    } else {
        String(bytes, Charsets.UTF_8)
    }
    return text.replace("\u0000", "").trim()
}

private fun looksLikePrintableText(text: String): Boolean {
    if (text.isBlank()) return false
    val sample = text.take(4000)
    val printable = sample.count { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
    return printable >= sample.length * 0.85
}

private fun startsWith(bytes: ByteArray, ascii: String): Boolean {
    if (bytes.size < ascii.length) return false
    return ascii.indices.all { bytes[it] == ascii[it].code.toByte() }
}

private fun zipEntryNames(bytes: ByteArray): List<String> = buildList {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            add(entry.name)
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
}

private fun zipText(bytes: ByteArray, path: String): String? =
    zipTexts(bytes) { it == path }.firstOrNull()?.second

private fun zipTexts(bytes: ByteArray, accept: (String) -> Boolean): List<Pair<String, String>> = buildList {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && accept(entry.name)) {
                add(entry.name to zip.readBytes().toString(Charsets.UTF_8))
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
}

private fun collectXmlTagText(xml: String, tag: String): List<String> {
    val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    return regex.findAll(xml).map { decodeXmlEntities(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
}

private fun decodeXmlEntities(raw: String): String = raw
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace(Regex("&#(\\d+);")) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
    }
    .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
        match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
    }
