package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapPlaceMarkupTest {
    @Test
    fun injectsMarkerAndBuildsCardWithoutDumpingPhotos() {
        val places = listOf(
            AmapPlace(
                id = "B001",
                name = "莱蒂尔",
                address = "某路",
                lng = 121.4,
                lat = 31.2,
                photos = listOf("https://img.example/a.jpg", "https://img.example/b.jpg"),
            ),
        )
        val answer = prepareAmapPlaceAnswer("附近可以去莱蒂尔吃饭。", places)
        assertTrue(answer.markdown.contains("[[amap:B001]]"))
        assertTrue(answer.markdown.contains("[[amap-cards:1|B001]]"))
        assertFalse(answer.markdown.contains("!["))
        assertEquals(listOf("莱蒂尔"), answer.cards.map { it.name })
        assertTrue(!answer.markdown.contains("莱蒂尔吃饭") || answer.markdown.contains("[[amap:B001]]吃饭"))
    }

    @Test
    fun convertsPlaceTableToCards() {
        val places = listOf(
            AmapPlace(
                id = "B001",
                name = "莱蒂尔",
                address = "某路12号",
                photos = listOf("https://img.example/a.jpg", "https://img.example/b.jpg"),
                rating = "4.8",
                cost = "¥25",
                dishes = "珍珠奶茶、芋泥波波",
                distance = "320米",
            ),
            AmapPlace(
                id = "B002",
                name = "喜茶",
                address = "银泰店",
                photos = listOf("https://img.example/c.jpg"),
                rating = "4.6",
            ),
        )
        val table = """
            附近奶茶可以去这些店：

            | 店名 | 评分 | 地址 |
            | --- | --- | --- |
            | 莱蒂尔 | 4.8 | 某路12号 |
            | 喜茶 | 4.6 | 银泰店 |
        """.trimIndent()
        val answer = prepareAmapPlaceAnswer(table, places)
        assertFalse(answer.markdown.contains("| 店名"))
        assertFalse(answer.markdown.contains("!["))
        assertTrue(answer.markdown.contains("附近奶茶可以去这些店"))
        assertTrue(answer.markdown.contains("[[amap-cards:1|B001,B002]]"))
        assertEquals(listOf("莱蒂尔", "喜茶"), answer.cards.map { it.name })
        val again = prepareAmapPlaceAnswer(answer.markdown, places)
        assertEquals(answer.markdown, again.markdown)
        assertEquals(answer.cards.map { it.id }, again.cards.map { it.id })
    }

    @Test
    fun keepsPlaceBulletsAndInsertsCardsAfterThem() {
        val places = listOf(
            AmapPlace(id = "B001", name = "莱蒂尔", rating = "4.8", dishes = "珍珠奶茶"),
        )
        val answer = prepareAmapPlaceAnswer("- 莱蒂尔就在附近", places)
        assertTrue(answer.markdown.contains("- 莱蒂尔就在附近"))
        assertTrue(answer.markdown.contains("[[amap-cards:1|B001]]"))
        assertEquals(listOf("莱蒂尔"), answer.cards.map { it.name })
        val weather = """
            | 城市 | 天气 |
            | --- | --- |
            | 合肥 | 晴 |
        """.trimIndent()
        assertEquals(weather, injectAmapPlaceMarkup(weather, places))
    }

    @Test
    fun clustersEightPlacesAsThreeThreeTwo() {
        val places = (1..8).map { index ->
            AmapPlace(id = "B00$index", name = "店铺${index}号")
        }
        val markdown = (1..8).joinToString("\n") { index -> "- 店铺${index}号 很近" }
        val answer = prepareAmapPlaceAnswer(markdown, places)
        val tokens = answer.markdown.lines().filter { it.startsWith("[[amap-cards:") }
        assertEquals(
            listOf(
                "[[amap-cards:1|B001,B002,B003]]",
                "[[amap-cards:4|B004,B005,B006]]",
                "[[amap-cards:7|B007,B008]]",
            ),
            tokens,
        )
        assertEquals(8, answer.cards.size)
        assertTrue(answer.markdown.contains("- 店铺1号 很近"))
        assertTrue(answer.markdown.contains("- 店铺8号 很近"))
        val lastBullet = answer.markdown.lines().indexOfLast { it.startsWith("- 店铺") }
        val firstToken = answer.markdown.lines().indexOfFirst { it.startsWith("[[amap-cards:") }
        assertTrue(lastBullet >= 0 && firstToken > lastBullet)
    }

    @Test
    fun keepsIntroSentencesAboveCardsAndDropsListMeta() {
        val places = listOf(
            AmapPlace(
                id = "B001",
                name = "莱蒂尔",
                rating = "4.8",
                cost = "¥25",
                distance = "320米",
            ),
            AmapPlace(
                id = "B002",
                name = "喜茶",
                rating = "4.6",
                distance = "480米",
            ),
        )
        val markdown = """
            附近可以去莱蒂尔和喜茶。环境都不错，适合下午坐一会。
            - 莱蒂尔
            评分 4.8
            人均 ¥25
            - 喜茶
            距离 480米
        """.trimIndent()
        val answer = prepareAmapPlaceAnswer(markdown, places)
        val introAt = answer.markdown.indexOf("环境都不错")
        val tokenAt = answer.markdown.indexOf("[[amap-cards:")
        val bulletAt = answer.markdown.indexOf("- 莱蒂尔")
        assertTrue(introAt >= 0 && introAt < tokenAt)
        assertTrue(bulletAt >= 0 && bulletAt < tokenAt)
        assertFalse(answer.markdown.contains("评分 4.8"))
        assertFalse(answer.markdown.contains("人均"))
        assertFalse(answer.markdown.contains("距离 480米"))
        assertEquals(listOf("莱蒂尔", "喜茶"), answer.cards.map { it.name })
    }

    @Test
    fun streamingDoesNotDumpUnmentionedOrIncompleteCards() {
        val places = (1..8).map { index ->
            AmapPlace(id = "B00$index", name = "店铺${index}号")
        }
        val empty = prepareAmapPlaceAnswer("周边的火锅店不少，按距离从近到远给你列一下：", places, streaming = true)
        assertFalse(empty.markdown.contains("[[amap-cards:"))
        assertTrue(empty.cards.isEmpty())
        val two = prepareAmapPlaceAnswer(
            """
            周边的火锅店不少：
            - 店铺1号 很近
            - 店铺2号 也不远
            """.trimIndent(),
            places,
            streaming = true,
        )
        assertFalse(two.markdown.contains("[[amap-cards:"))
        assertTrue(two.cards.isEmpty())
        val three = prepareAmapPlaceAnswer(
            """
            - 店铺1号
            - 店铺2号
            - 店铺3号
            """.trimIndent(),
            places,
            streaming = true,
        )
        assertEquals(listOf("[[amap-cards:1|B001,B002,B003]]"), three.markdown.lines().filter { it.startsWith("[[amap-cards:") })
        assertEquals(listOf("B001", "B002", "B003"), three.cards.map { it.id })
    }

    @Test
    fun cardsMarkerIsNotParsedAsPlaceId() {
        val token = "[[amap-cards:1|B001,B002]]"
        assertNull(parseAmapPlaceMarker(token, 0))
        val match = parseAmapPlaceCardsMarker(token)
        assertEquals(1, match?.startIndex)
        assertEquals(listOf("B001", "B002"), match?.ids)
    }

    @Test
    fun parsesMarkerAndUrl() {
        val text = "去[[amap:B001]]看看"
        val match = parseAmapPlaceMarker(text, text.indexOf("[["))
        assertEquals("B001", match?.id)
        assertEquals("aether-amap:B001", amapPlaceUrl("B001"))
        assertEquals("B001", parseAmapPlaceUrl("aether-amap:B001"))
        assertNull(parseAmapPlaceUrl("https://example.com"))
    }

    @Test
    fun collectsPlacesFromSearchToolsOnly() {
        val search = JSONObject()
            .put("pois", JSONArray().put(JSONObject().put("id", "B001").put("name", "莱蒂尔").put("location", "121.4,31.2")))
            .toString()
        val weather = JSONObject().put("weather", "晴").toString()
        val places = amapPlacesFromToolResults(
            listOf(
                "maps_text_search" to search,
                "maps_weather" to weather,
            ),
        )
        assertEquals(listOf("莱蒂尔"), places.map { it.name })
    }

    @Test
    fun foodPlacesAreDetectedFromTypeAndTypecode() {
        assertTrue(
            isAmapFoodPlace(
                AmapPlace(id = "B001", name = "莱蒂尔", type = "餐饮服务;冷饮店;奶茶店"),
            ),
        )
        assertTrue(
            isAmapFoodPlace(
                AmapPlace(id = "B002", name = "喜茶", typecode = "050302"),
            ),
        )
        assertFalse(
            isAmapFoodPlace(
                AmapPlace(id = "B003", name = "银泰城", type = "购物服务;商场", typecode = "060101"),
            ),
        )
        assertFalse(
            isAmapFoodPlace(
                AmapPlace(id = "B004", name = "黄山风景区", type = "风景名胜;风景名胜", typecode = "110101"),
            ),
        )
        assertEquals("「莱蒂尔」招牌菜有什么", amapPlaceAskDishesPrompt(AmapPlace(id = "B001", name = "莱蒂尔")))
        assertEquals("仔细介绍下「莱蒂尔」", amapPlaceAskIntroPrompt(AmapPlace(id = "B001", name = "莱蒂尔")))
        assertEquals("我要导航去「莱蒂尔」", amapPlaceNavigateInChatUserText(AmapPlace(id = "B001", name = "莱蒂尔")))
        assertEquals("我要导航去「莱蒂尔」", amapPlaceOpenInChatNaviPrompt(AmapPlace(id = "B001", name = "莱蒂尔")))
    }
}
