package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImagePlacementTest {
    @Test
    fun injectPlacesEachKindAfterItsParagraph() {
        val markdown = """
            大雁塔是唐代建筑。

            钟楼在西安市中心。
        """.trimIndent()
        val injected = injectBrowserImages(
            markdown,
            listOf(
                BrowserInlineImage("https://cdn.example/dayan.jpg", "西安大雁塔"),
                BrowserInlineImage("https://cdn.example/bell.jpg", "钟楼夜景"),
            ),
        )
        val dayanIndex = injected.indexOf("![西安大雁塔](https://cdn.example/dayan.jpg)")
        val bellIndex = injected.indexOf("![钟楼夜景](https://cdn.example/bell.jpg)")
        val dayanText = injected.indexOf("大雁塔是唐代建筑")
        val bellText = injected.indexOf("钟楼在西安市中心")
        assertTrue(dayanIndex > dayanText)
        assertTrue(bellIndex > bellText)
        assertTrue(dayanIndex < bellText)
        assertTrue(bellIndex > dayanIndex)
    }

    @Test
    fun diversifyKeepsMultipleTopicsInsteadOfOneKind() {
        val images = (1..7).map { BrowserInlineImage("https://cdn.example/bell-$it.jpg", "钟楼") } +
            listOf(
                BrowserInlineImage("https://cdn.example/dayan-1.jpg", "大雁塔"),
                BrowserInlineImage("https://cdn.example/dayan-2.jpg", "西安大雁塔"),
            )
        val mixed = diversifyInlineImages(images, perTopic = 4, maxTotal = 16)
        val alts = mixed.map { it.alt }
        assertTrue(alts.any { it.contains("钟楼") })
        assertTrue(alts.any { it.contains("大雁塔") })
        assertEquals(6, mixed.size)
    }

    @Test
    fun relocatesTrailingDumpOntoMatchingParagraphs() {
        val markdown = """
            西安有大雁塔和钟楼。

            大雁塔很高。

            钟楼很热闹。

            ![大雁塔](https://cdn.example/dayan.jpg)
            ![钟楼](https://cdn.example/bell.jpg)
        """.trimIndent()
        val injected = injectBrowserImages(markdown, emptyList())
        assertTrue(injected.indexOf("![大雁塔]") < injected.indexOf("钟楼很热闹"))
        assertTrue(injected.indexOf("![钟楼]") > injected.indexOf("钟楼很热闹"))
        assertFalse(injected.trim().endsWith("![大雁塔](https://cdn.example/dayan.jpg)\n![钟楼](https://cdn.example/bell.jpg)"))
    }

    @Test
    fun streamingHoldDoesNotParkUnmatchedAtBottom() {
        val markdown = "西安是十三朝古都。"
        val injected = injectBrowserImages(
            markdown = markdown,
            images = listOf(BrowserInlineImage("https://cdn.example/dayan.jpg", "大雁塔")),
            requireTopicMatch = true,
            holdUnmatched = true,
        )
        assertFalse(injected.contains("![大雁塔]"))
        val later = injectBrowserImages(
            markdown = """
                西安是十三朝古都。

                大雁塔是唐代建筑。
            """.trimIndent(),
            images = listOf(BrowserInlineImage("https://cdn.example/dayan.jpg", "大雁塔")),
            requireTopicMatch = true,
            holdUnmatched = true,
        )
        assertTrue(later.indexOf("![大雁塔]") > later.indexOf("大雁塔是唐代建筑"))
    }

    @Test
    fun markdownImagesShareTopicForSameLandmark() {
        assertTrue(markdownImagesShareTopic("西安大雁塔夜景", "大雁塔"))
        assertFalse(markdownImagesShareTopic("大雁塔", "钟楼"))
    }

    @Test
    fun unmatchedLiveImagesDoNotAttachWhenTopicRequired() {
        val injected = injectBrowserImages(
            markdown = "今天天气很好。",
            images = listOf(BrowserInlineImage("https://cdn.example/dayan.jpg", "大雁塔")),
            requireTopicMatch = true,
        )
        assertFalse(injected.contains("!["))
        assertFalse(injectBrowserImages("这是没有照片的回答。", emptyList()).contains("!["))
    }

    @Test
    fun streamingDoesNotAttachToGrowingLastLine() {
        val images = listOf(BrowserInlineImage("https://cdn.example/dayan.jpg", "大雁塔"))
        val growing = injectBrowserImages(
            markdown = "大雁塔是唐代建筑。",
            images = images,
            requireTopicMatch = true,
            holdUnmatched = true,
            streaming = true,
        )
        assertFalse(growing.contains("!["))
        val sealed = injectBrowserImages(
            markdown = "大雁塔是唐代建筑。\n\n钟楼也很热闹。",
            images = images,
            requireTopicMatch = true,
            holdUnmatched = true,
            streaming = true,
        )
        assertTrue(sealed.indexOf("![大雁塔]") > sealed.indexOf("大雁塔是唐代建筑"))
        assertTrue(sealed.indexOf("![大雁塔]") < sealed.indexOf("钟楼也很热闹"))
        val done = injectBrowserImages(
            markdown = "大雁塔是唐代建筑。",
            images = images,
            requireTopicMatch = true,
            streaming = false,
        )
        assertTrue(done.contains("![大雁塔]"))
    }

    @Test
    fun attachTopicImagesOnlyAfterSealedMatchingHeading() {
        val bundles = listOf(
            TopicImageBundle(
                topicId = "上海美食",
                images = listOf(BrowserInlineImage("https://cdn.example/food.jpg", "小笼包", "上海美食")),
            ),
        )
        val streaming = attachTopicImages(
            markdown = "# 上海美食图鉴\n\n本帮菜讲究浓油赤酱。",
            bundles = bundles,
            streaming = true,
        )
        assertFalse(streaming.contains("!["))
        val sealed = attachTopicImages(
            markdown = "# 上海美食图鉴\n\n本帮菜讲究浓油赤酱。\n\n# 杭州西湖\n\n湖面很大。",
            bundles = bundles,
            streaming = true,
        )
        assertTrue(sealed.indexOf("![小笼包]") > sealed.indexOf("本帮菜讲究浓油赤酱"))
        assertTrue(sealed.indexOf("![小笼包]") < sealed.indexOf("# 杭州西湖"))
        assertFalse(sealed.contains("![小笼包]") && sealed.indexOf("![小笼包]") > sealed.indexOf("湖面很大"))
    }

    @Test
    fun attachTopicImagesDoesNotRewriteExistingCompleteImages() {
        val markdown = """
            # 上海美食

            小笼包很鲜。

            ![小笼包](https://cdn.example/xlb.jpg)
        """.trimIndent()
        val attached = attachTopicImages(
            markdown = markdown,
            bundles = listOf(
                TopicImageBundle(
                    topicId = "上海美食",
                    images = listOf(BrowserInlineImage("https://cdn.example/xlb.jpg", "小笼包", "上海美食")),
                ),
            ),
        )
        assertEquals(1, Regex("!\\[").findAll(attached).count())
        assertTrue(attached.contains("![小笼包](https://cdn.example/xlb.jpg)"))
    }
}
