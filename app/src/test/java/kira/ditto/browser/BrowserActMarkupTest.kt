package kira.ditto.browser

import kira.ditto.ui.MarkdownBlock
import kira.ditto.ui.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserActMarkupTest {
    @Test
    fun parsesLabelAndOfficialUrl() {
        val marker = parseBrowserActMarker(
            "[[browser-act:帮我打开官网报名|https://www.nvidia.com/dgx-spark-hackathon]]",
        )!!
        assertEquals("帮我打开官网报名", marker.label)
        assertEquals("https://www.nvidia.com/dgx-spark-hackathon", marker.url)
    }

    @Test
    fun rejectsSerpUrl() {
        assertNull(
            parseBrowserActMarker("[[browser-act:打开|https://www.bing.com/search?q=nvidia]]"),
        )
        assertNull(
            parseBrowserActMarker("[[browser-act:打开|https://www.google.com/search?q=nvidia]]"),
        )
    }

    @Test
    fun rejectsIncompleteOrBlank() {
        assertNull(parseBrowserActMarker("[[browser-act:帮我打开官网报名|https://www.nvidia.com/x"))
        assertNull(parseBrowserActMarker("[[browser-act:|https://www.nvidia.com/x]]"))
        assertNull(parseBrowserActMarker("[[browser-act:报名|]]"))
        assertNull(parseBrowserActMarker("普通句子"))
    }

    @Test
    fun followUpUserTextIsLabelPlusUrl() {
        assertEquals(
            "帮我打开官网报名\nhttps://www.nvidia.com/dgx-spark-hackathon",
            browserActFollowUpUserText(
                "帮我打开官网报名",
                "https://www.nvidia.com/dgx-spark-hackathon",
            ),
        )
    }

    @Test
    fun firstTurnWithoutUrlIsNotOperateFollowUp() {
        val first =
            "帮我搜索第三届NVIDIA DGX Spark 黑客松的信息，并打开官网帮我报名"
        assertFalse(looksLikeBrowserOperateFollowUp(first))
        assertEquals("", browserActOperateUrlFromUserText(first))
    }

    @Test
    fun capsuleFollowUpIsOperate() {
        val followUp = "帮我打开官网报名\nhttps://www.nvidia.com/dgx-spark-hackathon"
        assertTrue(looksLikeBrowserOperateFollowUp(followUp))
        assertEquals(
            "https://www.nvidia.com/dgx-spark-hackathon",
            browserActOperateUrlFromUserText(followUp),
        )
        val parsed = parseBrowserActFollowUpFromUserText(followUp)!!
        assertEquals("帮我打开官网报名", parsed.label)
    }

    @Test
    fun markdownCreatesBrowserActBlockAndHidesInvalid() {
        val blocks = parseMarkdownBlocks(
            """
            比赛下周截止。

            [[browser-act:帮我打开官网报名|https://www.nvidia.com/dgx-spark-hackathon]]

            [[browser-act:打开|https://www.bing.com/search?q=nvidia]]
            """.trimIndent(),
        )
        val act = blocks.filterIsInstance<MarkdownBlock.BrowserAct>().single()
        assertEquals("帮我打开官网报名", act.label)
        assertEquals("https://www.nvidia.com/dgx-spark-hackathon", act.url)
        assertTrue(blocks.none { it is MarkdownBlock.Paragraph && it.text.text.contains("browser-act") })
    }

    @Test
    fun lookupThenOperateNeedsSearchAndSignupWithoutUrl() {
        val first = "帮我搜索第三届NVIDIA DGX Spark 黑客松的信息，并打开官网帮我报名"
        assertTrue(looksLikeBrowserLookupThenOperate(first))
        assertFalse(looksLikeBrowserLookupThenOperate("帮我打开官网报名\nhttps://scrm.nvidia.cn/lp/x"))
        assertFalse(looksLikeBrowserLookupThenOperate("西湖门票多少钱"))
    }

    @Test
    fun hostAppendsCapsuleFromOfficialUrl() {
        val user = "帮我搜索第三届NVIDIA DGX Spark 黑客松的信息，并打开官网帮我报名"
        val sealed = ensureBrowserActAnswer(
            markdown = "比赛在 9 月举办。",
            userText = user,
            officialUrl = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
        )
        assertTrue(sealed.contains("比赛在 9 月举办。"))
        assertTrue(
            sealed.contains(
                "[[browser-act:帮我打开官网报名|https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920]]",
            ),
        )
        val already = ensureBrowserActAnswer(
            markdown = sealed,
            userText = user,
            officialUrl = "https://www.nvidia.com/en-us/data-center/dgx/",
        )
        assertEquals(1, Regex("""\[\[browser-act:""").findAll(already).count())
        assertTrue(already.contains("scrm.nvidia.cn/lp/"))
    }

    @Test
    fun homepageAndBacktickCapsuleAreRejected() {
        assertEquals("", sanitizeBrowserActUrl("https://www.nvidia.cn/`"))
        assertEquals("", sanitizeBrowserActUrl("https://www.nvidia.cn/"))
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon",
            sanitizeBrowserActUrl("https://scrm.nvidia.cn/lp/dgx-spark-hackathon`"),
        )
        assertNull(
            parseBrowserActMarker("[[browser-act:帮我打开官网报名|https://www.nvidia.cn/`]]"),
        )
        assertTrue(isBrowserSiteHomeUrl("https://www.nvidia.cn/"))
        assertFalse(isBrowserSiteHomeUrl("https://scrm.nvidia.cn/lp/dgx-spark-hackathon"))
    }

    @Test
    fun hostReplacesHomepageCapsuleWithOfficialUrl() {
        val user = "帮我搜索第三届NVIDIA DGX Spark 黑客松的信息，并打开官网帮我报名"
        val sealed = ensureBrowserActAnswer(
            markdown = "比赛下周截止。\n\n[[browser-act:帮我打开官网报名|https://www.nvidia.cn/`]]",
            userText = user,
            officialUrl = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
        )
        assertTrue(
            sealed.contains(
                "[[browser-act:帮我打开官网报名|https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920]]",
            ),
        )
        assertFalse(sealed.contains("https://www.nvidia.cn/"))
        val upgraded = ensureBrowserActAnswer(
            markdown = "比赛下周截止。\n\n[[browser-act:帮我打开官网报名|https://www.nvidia.com/en-us/data-center/dgx/]]",
            userText = user,
            officialUrl = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
        )
        assertTrue(upgraded.contains("scrm.nvidia.cn/lp/"))
        assertFalse(upgraded.contains("data-center/dgx"))
    }

    @Test
    fun verifyContinueIsNotOperateOrLookupThenOperate() {
        assertTrue(looksLikeBrowserVerifyContinue(BrowserVerifyContinueUserText))
        assertFalse(looksLikeBrowserOperateFollowUp(BrowserVerifyContinueUserText))
        assertFalse(looksLikeBrowserLookupThenOperate(BrowserVerifyContinueUserText))
        assertEquals("", browserActOperateUrlFromUserText(BrowserVerifyContinueUserText))
        assertTrue(looksLikeBrowserPageResume(BrowserVerifyContinueUserText))
        assertTrue(looksLikeBrowserLoginContinue(BrowserLoginContinueUserText))
        assertTrue(looksLikeBrowserPageResume(BrowserLoginContinueUserText))
        assertFalse(looksLikeBrowserLookupThenOperate(BrowserLoginContinueUserText))
        assertFalse(looksLikeBrowserOperateFollowUp(BrowserLoginContinueUserText))
    }

    @Test
    fun hostAppendsLoginResumeCapsule() {
        val sealed = ensureBrowserResumeAnswer(
            markdown = "GUI_TASK_NEEDS_TEACHING: 请在页面里登录",
            userTakeoverReason = "login",
        )
        assertTrue(sealed.contains(BrowserResumeLoginMarker))
        val already = ensureBrowserResumeAnswer(sealed, userTakeoverReason = "login")
        assertEquals(1, Regex("""\[\[browser-resume:""").findAll(already).count())
        val challenge = ensureBrowserResumeAnswer(
            markdown = "请完成人机验证",
            userTakeoverReason = "challenge",
        )
        assertFalse(challenge.contains(BrowserResumePrefix))
        val fromText = ensureBrowserResumeAnswer(
            markdown = "GUI_TASK_NEEDS_TEACHING: please log in on the page",
            userTakeoverReason = "",
        )
        assertTrue(fromText.contains(BrowserResumeLoginMarker))
        val blocks = parseMarkdownBlocks(
            "请登录后继续。\n\n$BrowserResumeLoginMarker",
        )
        assertEquals(1, blocks.filterIsInstance<MarkdownBlock.BrowserResume>().size)
    }

    @Test
    fun pickOfficialUrlPrefersHackathonOverProductPage() {
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            pickOfficialBrowserActUrl(
                listOf(
                    "https://www.nvidia.com/en-us/data-center/dgx/" to "NVIDIA DGX",
                    "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920" to
                        "第三届 NVIDIA DGX Spark 黑客松",
                ),
            ),
        )
    }

    @Test
    fun emptyLookupAnswerStillGetsCapsule() {
        val sealed = ensureBrowserActAnswer(
            markdown = "",
            userText = "帮我搜索第三届NVIDIA DGX Spark 黑客松的信息，并打开官网帮我报名",
            officialUrl = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon",
        )
        assertTrue(sealed.contains("[[browser-act:帮我打开官网报名|https://scrm.nvidia.cn/lp/dgx-spark-hackathon]]"))
    }

    @Test
    fun hostReplacesHustleailabCapsuleWithNvidiaOfficialUrl() {
        val user = "帮我搜索第三届NVIDIA DGX Spark 黑客松的信息，并打开官网帮我报名"
        val scrm = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920"
        val hustle =
            "https://hustleailab.com/zero-downtime-hackathon/#:~:text=NVIDIA%20DGX%20Spark"
        assertEquals(
            "https://hustleailab.com/zero-downtime-hackathon/",
            sanitizeBrowserActUrl(hustle),
        )
        assertEquals(
            scrm,
            pickOfficialBrowserActUrl(
                listOf(
                    hustle to "Zero Downtime Hackathon: Win a \$5,000 NVIDIA DGX Spark",
                    scrm to "官方报名页",
                ),
                userText = user,
            ),
        )
        assertEquals(
            scrm,
            pickOfficialBrowserActUrl(
                listOf(
                    "https://www.sohu.com/a/1071951613_133839" to
                        "活动 | 第三届NVIDIA DGX Spark黑客松Agent Skills开发挑战赛",
                    "https://www.toutiao.com/article/7681559874408464939/" to
                        "报名启动！第三届 NVIDIA DGX Spark 黑客松",
                    scrm to "官方报名页",
                ),
                userText = user,
            ),
        )
        assertTrue(isBrowserReprintHost("m.sohu.com"))
        assertTrue(isBrowserReprintHost("www.toutiao.com"))
        val sealed = ensureBrowserActAnswer(
            markdown = """
                官方报名页：$scrm

                [[browser-act:帮我打开官网报名|$hustle]]
            """.trimIndent(),
            userText = user,
            officialUrl = scrm,
        )
        assertTrue(sealed.contains("[[browser-act:帮我打开官网报名|$scrm]]"))
        assertFalse(sealed.contains("hustleailab.com"))
        assertEquals(listOf(scrm), labeledOfficialSignupUrls("官方报名页：$scrm"))
    }
}
