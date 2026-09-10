package kira.ditto.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserTabLeaseTest {
    @Before
    fun reset() {
        BrowserTabLease.clear()
    }

    @Test
    fun secondActOnSameTabIsPreempted() {
        assertTrue(BrowserTabLease.tryAcquire("tab-1", "agent-a"))
        assertFalse(BrowserTabLease.tryAcquire("tab-1", "agent-b"))
        BrowserTabLease.release("agent-a")
        assertTrue(BrowserTabLease.tryAcquire("tab-1", "agent-b"))
    }

    @Test
    fun observeToolsNeverNeedLease() {
        assertFalse(BrowserTabLease.isActTool("page_snapshot"))
        assertFalse(BrowserTabLease.isActTool("page_read"))
        assertFalse(BrowserTabLease.isActTool("page_grep"))
        assertFalse(BrowserTabLease.isActTool("page_wait"))
        assertFalse(BrowserTabLease.isActTool("page_inspect"))
        assertFalse(BrowserTabLease.isActTool("page_screenshot"))
        assertTrue(BrowserTabLease.isActTool("page_click"))
        assertTrue(BrowserTabLease.isActTool("tabs_navigate"))
        assertTrue(BrowserTabLease.isActTool("page_fill"))
        assertTrue(BrowserTabLease.isActTool("page_js"))
        assertTrue(BrowserTabLease.isActTool("search_images"))
    }

    @Test
    fun differentTabsDoNotPreemptEachOther() {
        assertTrue(BrowserTabLease.tryAcquire("tab-a", "本帮菜"))
        assertTrue(BrowserTabLease.tryAcquire("tab-b", "小吃"))
        assertFalse(BrowserTabLease.tryAcquire("tab-a", "小吃"))
    }

    @Test
    fun blankTabDoesNotBlock() {
        assertTrue(BrowserTabLease.tryAcquire("", "agent-a"))
        assertTrue(BrowserTabLease.tryAcquire("", "agent-b"))
    }
}
