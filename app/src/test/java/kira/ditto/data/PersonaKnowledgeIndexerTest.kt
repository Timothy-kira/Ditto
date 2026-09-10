package kira.ditto.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaKnowledgeIndexerTest {
    @Test
    fun chunkTextSplitsLongParagraphsAndKeepsShortOnesTogether() {
        val chunks = chunkPersonaKnowledgeText(
            "alpha\n\n" + "b".repeat(50) + "\n\n" + "c".repeat(1200),
            maxChars = 100,
        )
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.first().startsWith("alpha"))
        assertTrue(chunks.first().contains("b"))
        assertTrue(chunks.any { it.startsWith("c") && it.length <= 100 })
        assertTrue(formatKnowledgeCitations(emptyList()).isEmpty())
        val cited = formatKnowledgeCitations(
            listOf(
                PersonaKnowledgeSlice(
                    id = "1",
                    sourceId = "s",
                    sourceName = "notes.md",
                    text = "hello world",
                    embedding = floatArrayOf(1f),
                    index = 0,
                ),
            ),
        )
        assertFalse(cited.contains("notes.md"))
        assertTrue(cited.contains("[[1]]"))
        assertTrue(cited.contains("hello world"))
        assertTrue(cited.contains("Never mention file names"))
        assertEquals(
            listOf(0),
            kira.ditto.browser.AgentIndex.rankTexts(
                "hello world",
                listOf("hello world from notes", "unrelated weather report"),
            ).take(1),
        )
        assertEquals(
            listOf(KnowledgeCitation(index = 1, sourceName = "notes.md", text = "hello world")),
            knowledgeCitationsFromSlices(
                listOf(
                    PersonaKnowledgeSlice(
                        id = "1",
                        sourceId = "s",
                        sourceName = "notes.md",
                        text = "hello world",
                        embedding = floatArrayOf(1f),
                        index = 0,
                    ),
                ),
            ),
        )
    }

    @Test
    fun embeddingUrlUsesCompatibleEmbeddingsPath() {
        assertEquals(
            "https://api.example.com/v1/embeddings",
            embeddingRequestUrl("https://api.example.com/v1"),
        )
        assertEquals(
            "https://api.example.com/v1/embeddings",
            embeddingRequestUrl("https://api.example.com/v1/embeddings"),
        )
        assertEquals(
            "https://api.example.com/v1/embeddings",
            embeddingRequestUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun embeddingBearerTokenReadsApiKeyThenOauthThenEnv() {
        assertEquals("sk-live", embeddingBearerToken(apiKey = "sk-live"))
        assertEquals(
            "oauth-token",
            embeddingBearerToken(
                apiKey = "",
                oauthCredentialJson = """{"access_token":"oauth-token"}""",
            ),
        )
        assertEquals(
            "env-key",
            embeddingBearerToken(
                apiKey = "",
                environment = listOf("OPENAI_API_KEY" to "env-key"),
            ),
        )
        assertEquals(
            "Missing Authorization header",
            embeddingErrorMessage("""{"error":{"message":"Missing Authorization header"}}"""),
        )
        assertEquals(
            "from-header",
            embeddingBearerToken(
                apiKey = "",
                customHeaders = listOf(LlmCustomHeader("Authorization", "Bearer from-header")),
            ),
        )
        val request = okhttp3.Request.Builder().url("https://example.com/v1/embeddings")
        applyEmbeddingAuthHeaders(request, "sk-test")
        val built = request.build()
        assertEquals("Bearer sk-test", built.header("Authorization"))
        assertEquals("Bearer sk-test", built.header("Authentication"))
        assertEquals("sk-test", built.header("api-key"))
        assertEquals("sk-test", built.header("x-api-key"))
    }

    @Test
    fun extractsMarkdownAndPlainText() {
        val markdown = extractPersonaKnowledgeText(
            "# Title\n\nA **persona** note.".toByteArray(),
            "notes.md",
            "text/markdown",
        )
        assertTrue(markdown.contains("persona"))
        val txt = extractPersonaKnowledgeText(
            "plain knowledge".toByteArray(),
            "notes.txt",
            "text/plain",
        )
        assertEquals("plain knowledge", txt)
    }

    @Test
    fun extractsDocxParagraphs() {
        val bytes = zipOf(
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Hello from docx</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Second &amp; paragraph</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
        )
        val text = extractDocx(bytes)
        assertTrue(text.contains("Hello from docx"))
        assertTrue(text.contains("Second & paragraph"))
        assertEquals(KnowledgeKind.Docx, inferKnowledgeKind(bytes, "brief.docx", "application/octet-stream"))
    }

    @Test
    fun extractsPptxSlideText() {
        val bytes = zipOf(
            "ppt/slides/slide1.xml" to """
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:sp><p:txBody><a:p><a:r><a:t>Deck title</a:t></a:r></a:p></p:txBody></p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
            """.trimIndent(),
        )
        val text = extractPptx(bytes)
        assertTrue(text.contains("Deck title"))
        assertEquals(KnowledgeKind.Pptx, inferKnowledgeKind(bytes, "talk.pptx", ""))
    }

    @Test
    fun extractsUncompressedPdfLiterals() {
        val pdf = uncompressedPdf("Hello PDF")
        val text = extractPdfLiteralText(pdf)
        assertTrue(text.contains("Hello PDF"))
        assertEquals(KnowledgeKind.Pdf, inferKnowledgeKind(pdf, "paper.pdf", "application/pdf"))
    }

    @Test
    fun extractsHtmlWithJsoup() {
        val html = "<html><body><h1>Guide</h1><p>Keep the <b>persona</b>.</p></body></html>"
        val text = extractHtml(html.toByteArray())
        assertTrue(text.contains("Guide"))
        assertTrue(text.contains("persona"))
    }

    @Test
    fun parseEmbeddingUsageReadsApiAndEstimatesWhenMissing() {
        val api = parseEmbeddingUsage(
            org.json.JSONObject("""{"usage":{"prompt_tokens":40,"total_tokens":40}}"""),
            inputChars = 100,
        )
        assertEquals("api", api.second)
        assertEquals(40L, api.first?.inputTokens)
        assertEquals(40L, api.first?.totalTokens)

        val estimated = parseEmbeddingUsage(org.json.JSONObject("""{"data":[]}"""), inputChars = 8)
        assertEquals("estimated", estimated.second)
        assertEquals(2L, estimated.first?.inputTokens)
    }
}

private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun uncompressedPdf(text: String): ByteArray {
    val stream = "BT /F1 12 Tf 72 720 Td ($text) Tj ET"
    val objects = listOf(
        "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
        "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
        "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
        "4 0 obj\n<< /Length ${stream.length} >>\nstream\n$stream\nendstream\nendobj\n",
        "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
    )
    val builder = StringBuilder("%PDF-1.1\n")
    val offsets = ArrayList<Int>()
    objects.forEach { obj ->
        offsets += builder.length
        builder.append(obj)
    }
    val xref = builder.length
    builder.append("xref\n0 6\n0000000000 65535 f \n")
    offsets.forEach { offset ->
        builder.append(offset.toString().padStart(10, '0'))
        builder.append(" 00000 n \n")
    }
    builder.append("trailer\n<< /Root 1 0 R /Size 6 >>\nstartxref\n")
    builder.append(xref)
    builder.append("\n%%EOF\n")
    return builder.toString().toByteArray(Charsets.ISO_8859_1)
}
