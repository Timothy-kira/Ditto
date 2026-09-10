package kira.ditto.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun parseMarkdownTreatsIncompleteImageTailAsParagraph() {
        val blocks = parseMarkdownBlocks("![本\n帮菜是上海的代表。")
        assertEquals(1, blocks.size)
        assertEquals("Paragraph", blocks.single()?.javaClass?.simpleName)
        assertTrue(blocks.none { block ->
            val name = block?.javaClass?.simpleName
            name == "Image" || name == "ImageGroup"
        })
    }

    @Test(timeout = 1_000L)
    fun parseMarkdownTreatsIncompleteTableLineAsParagraph() {
        val blocks = parseMarkdownBlocks("| Effort | Share |")

        assertEquals(1, blocks.size)
        assertEquals("Paragraph", blocks.single()?.javaClass?.simpleName)
    }

    @Test(timeout = 1_000L)
    fun parseMarkdownKeepsPipeTextInsideParagraphUntilTableIsComplete() {
        val blocks = parseMarkdownBlocks(
            """
            The stream may contain a partial table next.
            | Effort | Share |
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        assertEquals("Paragraph", blocks.single()?.javaClass?.simpleName)
    }

    @Test(timeout = 1_000L)
    fun parseMarkdownStillParsesCompleteTables() {
        val blocks = parseMarkdownBlocks(
            """
            | Effort | Share |
            |--------|-------|
            | high   | 80%   |
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        assertEquals("Table", blocks.single()?.javaClass?.simpleName)
    }

    @Test
    fun markdownTableColumnWidthsFitTwoColumnTablesIntoViewport() {
        val widths = markdownTableColumnWidths(columnCount = 2, viewportWidth = 320.dp)

        assertEquals(2, widths.size)
        assertEquals(320.dp, widths.reduce { total, width -> total + width })
        assertTrue(widths[1] > widths[0])
    }

    @Test
    fun markdownTableColumnWidthsKeepManyColumnTablesScrollable() {
        val widths = markdownTableColumnWidths(columnCount = 5, viewportWidth = 320.dp)

        assertEquals(5, widths.size)
        assertTrue(widths.reduce { total, width -> total + width } > 320.dp)
    }

    @Test
    fun parseMarkdownImageSupportsOptionalTitleAndAngleWrappedUrls() {
        val image = parseMarkdownImage(
            "![Example](<https://example.com/assets/diagram(v2).svg> \"A title\")"
        )

        requireNotNull(image)
        assertEquals("Example", image.altText)
        assertEquals("https://example.com/assets/diagram(v2).svg", image.url)
    }

    @Test
    fun parseMarkdownImageSequenceSupportsLinkedAndAdjacentImages() {
        val images = parseMarkdownImageSequence(
            "[![Build](https://example.com/build.svg)](https://example.com/actions) " +
                "![Coverage](https://example.com/coverage.svg)"
        )

        requireNotNull(images)
        assertEquals(2, images.size)
        assertEquals("Build", images[0].altText)
        assertEquals("https://example.com/build.svg", images[0].url)
        assertEquals("Coverage", images[1].altText)
    }

    @Test
    fun parseMarkdownRecognizesHtmlConverterListsRulesAndSetextHeadings() {
        val blocks = parseMarkdownBlocks(
            """
            Package README
            ==============

            * First feature
            * Second feature

            *** ** * ** ***
            """.trimIndent(),
        )

        assertEquals(
            listOf("Heading", "UnorderedList", "Rule"),
            blocks.map { it?.javaClass?.simpleName },
        )
    }

    @Test
    fun parseMarkdownCreatesImageBlocksForLinkedBadgeRows() {
        val blocks = parseMarkdownBlocks(
            "[![Build](https://example.com/build.svg)](https://example.com/actions) " +
                "[![Coverage](https://example.com/coverage.svg)](https://example.com/coverage)"
        )

        assertEquals(listOf("ImageGroup"), blocks.map { it?.javaClass?.simpleName })
    }

    @Test
    fun parseMarkdownGroupsConsecutiveSameTopicImages() {
        val blocks = parseMarkdownBlocks(
            """
            ![大雁塔](https://example.com/dayan-1.jpg)
            ![西安大雁塔夜景](https://example.com/dayan-2.jpg)
            ![钟楼](https://example.com/bell.jpg)
            """.trimIndent(),
        )
        assertEquals(listOf("ImageGroup", "Image"), blocks.map { it?.javaClass?.simpleName })
        val group = blocks[0] as MarkdownBlock.ImageGroup
        assertEquals(2, group.images.size)
        val bell = blocks[1] as MarkdownBlock.Image
        assertEquals("钟楼", bell.image.altText)
    }

    @Test
    fun parseMarkdownKeepsDifferentTopicsSeparateAcrossBlankLines() {
        val blocks = parseMarkdownBlocks(
            """
            ![大雁塔](https://example.com/a.jpg)

            ![钟楼](https://example.com/b.jpg)
            """.trimIndent(),
        )
        assertEquals(listOf("Image", "Image"), blocks.map { it?.javaClass?.simpleName })
    }

    @Test
    fun parseMarkdownGroupsPureImageListItems() {
        val blocks = parseMarkdownBlocks(
            """
            - ![one](https://example.com/a.jpg)
            - ![two](https://example.com/b.jpg)
            """.trimIndent(),
        )
        assertEquals(listOf("ImageGroup"), blocks.map { it?.javaClass?.simpleName })
        val group = blocks.single() as MarkdownBlock.ImageGroup
        assertEquals(2, group.images.size)
    }

    @Test
    fun coalesceAdjacentMarkdownImageBlocksMergesNeighbors() {
        val merged = coalesceAdjacentMarkdownImageBlocks(
            listOf(
                MarkdownBlock.Image(MarkdownImageSpec("a", "https://example.com/a.jpg")),
                MarkdownBlock.Image(MarkdownImageSpec("b", "https://example.com/b.jpg")),
            ),
        )
        assertEquals(1, merged.size)
        val group = merged.single() as MarkdownBlock.ImageGroup
        assertEquals(listOf("a", "b"), group.images.map { it.altText })
    }

    @Test
    fun scrollPlaceholderSkipsHeavyTextUntilComposed() {
        assertTrue(
            markdownUsesScrollPlaceholder(
                streaming = false,
                scrolling = true,
                source = "西湖很大，湖面宽阔。",
                alreadyComposed = false,
            ),
        )
        assertFalse(
            markdownUsesScrollPlaceholder(
                streaming = false,
                scrolling = true,
                source = "西湖[[1]]\n\n![a](https://example.com/a.jpg)",
                alreadyComposed = false,
            ),
        )
        assertFalse(
            markdownUsesScrollPlaceholder(
                streaming = false,
                scrolling = true,
                source = "already shown",
                alreadyComposed = true,
            ),
        )
        assertFalse(
            markdownUsesScrollPlaceholder(
                streaming = true,
                scrolling = true,
                source = "streaming",
                alreadyComposed = false,
            ),
        )
    }

    @Test
    fun normalizeMarkdownImageUrlDecodesCommonHtmlEscapes() {
        assertEquals(
            "https://example.com/image.png?foo=1&bar=2",
            normalizeMarkdownImageUrl("https://example.com/image.png?foo=1&amp;bar=2"),
        )
    }

    @Test
    fun extractMarkdownLinkDestinationStopsBeforeOptionalTitle() {
        assertEquals(
            "https://example.com/a(b)c.png",
            extractMarkdownLinkDestination("https://example.com/a(b)c.png 'preview'"),
        )
    }

    @Test
    fun inferMarkdownImageMimeTypeDetectsSvgFromBytes() {
        val mimeType = inferMarkdownImageMimeType(
            reportedMimeType = null,
            rawUrl = "https://example.com/logo",
            bytes = "<svg viewBox=\"0 0 10 10\"></svg>".toByteArray(),
        )

        assertEquals("image/svg+xml", mimeType)
    }

    @Test
    fun inferMarkdownImageMimeTypeNormalizesSvgMimeVariants() {
        val mimeType = inferMarkdownImageMimeType(
            reportedMimeType = "image/svg",
            rawUrl = "/tmp/preview",
            bytes = ByteArray(0),
        )

        assertEquals("image/svg+xml", mimeType)
    }

    @Test
    fun sanitizeInlineMarkdownSvgRemovesScriptAndJavascriptHandlers() {
        val sanitized = sanitizeInlineMarkdownSvg(
            """
            <?xml version="1.0"?>
            <svg onclick="alert(1)" viewBox="0 0 10 10">
                <script>alert(1)</script>
                <a href="javascript:alert(1)"><rect width="10" height="10" /></a>
            </svg>
            """.trimIndent(),
        )

        assertTrue(sanitized.startsWith("<svg"))
        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun buildMarkdownInlineSvgHtmlEmbedsSvgWithoutPreviewImageAlt() {
        val html = buildMarkdownInlineSvgHtml("<svg viewBox=\"0 0 10 10\"></svg>")

        assertTrue(html.contains("<svg viewBox=\"0 0 10 10\"></svg>"))
        assertFalse(html.contains("alt=\"preview\""))
    }

    @Test
    fun buildMarkdownBadgeGroupHtmlUsesCompactNaturalImageSizing() {
        val html = buildMarkdownBadgeGroupHtml(
            listOf(
                MarkdownImageSpec("Build", "https://example.com/build.svg"),
                MarkdownImageSpec("Coverage", "https://example.com/coverage.svg"),
            )
        )

        assertTrue(html.contains("class=\"badge-row\""))
        assertTrue(html.contains("max-height: 32px"))
        assertFalse(html.contains("min-height: 136px"))
    }

    @Test
    fun parseMarkdownImageRejectsBlankDestinations() {
        assertNull(parseMarkdownImage("![Example]()"))
    }

    @Test
    fun parseMarkdownImageReadsMediaLayoutAttributes() {
        val image = parseMarkdownImage(
            "![Example](https://example.com/image.png){width=75% height=280 scroll=true show-all=false fit=cover}"
        )

        requireNotNull(image)
        assertEquals(MarkdownMediaWidth.Fraction(0.75f), image.layout.width)
        assertEquals(280, image.layout.heightDp)
        assertTrue(image.layout.scroll)
        assertFalse(image.layout.showAll)
        assertEquals(MarkdownMediaFit.Cover, image.layout.fit)
    }

    @Test
    fun parseMarkdownCodeFenceHeaderReadsMermaidAttributes() {
        val header = parseMarkdownCodeFenceHeader("```mermaid {height=420 show-all=true width=640}")
        val layout = parseMarkdownMediaLayout(
            attributes = header.attributes,
            defaults = MarkdownMediaLayout(maxHeightDp = 640),
        )

        assertEquals("mermaid", header.language)
        assertEquals(MarkdownMediaWidth.DpValue(640), layout.width)
        assertEquals(420, layout.heightDp)
        assertTrue(layout.showAll)
        assertFalse(layout.scroll)
        assertNull(layout.maxHeightDp)
    }

    private fun parseMarkdownBlocks(markdown: String): List<*> {
        val method = Class.forName("kira.ditto.ui.MarkdownRendererKt")
            .getDeclaredMethod("parseMarkdown", String::class.java)
        method.isAccessible = true
        return method.invoke(null, markdown) as List<*>
    }
}
