package kira.ditto.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserWebClientTest {
    @Test
    fun collectSearchFetchUrlsTakesDistinctHttpHits() {
        val result = JSONObject()
            .put("ok", true)
            .put(
                "queries",
                JSONArray().put(
                    JSONObject().put(
                        "hits",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("title", "A")
                                    .put("url", "https://a.example/one"),
                            )
                            .put(
                                JSONObject()
                                    .put("title", "A dup")
                                    .put("url", "https://a.example/one"),
                            )
                            .put(
                                JSONObject()
                                    .put("title", "B")
                                    .put("url", "https://b.example/two"),
                            )
                            .put(
                                JSONObject()
                                    .put("title", "skip")
                                    .put("url", "/relative"),
                            ),
                    ),
                ).put(
                    JSONObject().put(
                        "hits",
                        JSONArray().put(
                            JSONObject()
                                .put("title", "C")
                                .put("url", "https://c.example/three"),
                        ),
                    ),
                ),
            )
        assertEquals(
            listOf("https://a.example/one", "https://b.example/two"),
            collectSearchFetchUrls(result, limit = 2),
        )
        assertEquals(3, collectSearchFetchUrls(result, limit = 5).size)
    }

    @Test
    fun extractSearchHitsPrefersTitleAndCaptionOverDomainCite() {
        val html = """
            <html><body>
              <ol id="b_results">
                <li class="b_algo">
                  <h2><a href="https://travel.hangzhou.cn/west-lake">西湖景区</a></h2>
                  <div class="b_caption">
                    <cite>travel.hangzhou.cn</cite>
                    <p class="b_lineclamp">杭州西湖十景与门票说明</p>
                  </div>
                </li>
              </ol>
              <a href="https://chatgpt-chinese.com/">chatgpt-chinese.com</a>
              <a href="https://chatgpt-chinese.com/">chatgpt-chinese.com</a>
            </body></html>
        """.trimIndent()
        val hits = extractSearchHits(html, "https://www.bing.com/search?q=xihu", 8)
        assertEquals(1, hits.size)
        assertEquals("西湖景区", hits.single().getString("title"))
        assertEquals("杭州西湖十景与门票说明", hits.single().getString("snippet"))
        assertFalse(hits.single().getString("snippet").contains("travel.hangzhou.cn"))
    }
}
