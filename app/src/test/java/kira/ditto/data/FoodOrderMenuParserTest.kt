package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodOrderMenuParserTest {
    @Test
    fun mcdonaldMealsMapIsNotTreatedAsEmpty() {
        val raw = JSONObject(
            """
            {
              "success": true,
              "data": {
                "categories": [
                  { "name": "人气热卖", "meals": [{ "code": "920215" }] }
                ],
                "meals": {
                  "920215": { "name": "培根安格斯厚牛堡大套餐", "currentPrice": "55.5" },
                  "9900008169": { "name": "双层深海鳕鱼堡", "currentPrice": "25" },
                  "9900008139": { "name": "DC套餐测试", "currentPrice": "14" }
                }
              }
            }
            """.trimIndent(),
        )

        val products = FoodOrderMenuParser.collectProducts(raw)
        assertEquals(3, products.size)
        assertEquals(
            setOf("培根安格斯厚牛堡大套餐", "双层深海鳕鱼堡", "DC套餐测试"),
            products.map { it.name }.toSet(),
        )

        val payload = FoodOrderMenuParser.flattenMenu("麦当劳", "mcd", raw)
        assertEquals("3 道", payload.getString("count"))
        val cardNames = (0 until 3).map { payload.getString("product${it}_name") }.toSet()
        assertEquals(setOf("培根安格斯厚牛堡大套餐", "双层深海鳕鱼堡", "DC套餐测试"), cardNames)
        assertEquals(3, payload.getJSONArray("items").length())
    }

    @Test
    fun luckinSearchKeepsMoreThanTwoProductsOnTheCard() {
        val list = JSONArray()
        list.put(JSONObject().put("productId", 1).put("productName", "生椰拿铁").put("estimatePrice", 18))
        list.put(JSONObject().put("productId", 2).put("productName", "美式").put("estimatePrice", 12))
        list.put(JSONObject().put("productId", 3).put("productName", "香草拿铁").put("estimatePrice", 19))
        list.put(JSONObject().put("productId", 4).put("productName", "橙C美式").put("estimatePrice", 16))
        val raw = JSONObject().put("data", list)

        val payload = FoodOrderMenuParser.flattenMenu("瑞幸", "luckin", raw)
        assertEquals("4 道", payload.getString("count"))
        assertEquals("生椰拿铁", payload.getString("product0_name"))
        assertEquals("橙C美式", payload.getString("product3_name"))
        assertEquals(4, payload.getJSONArray("items").length())
    }

    @Test
    fun emptyOfficialPayloadKeepsAHintInsteadOfABlankCard() {
        val payload = FoodOrderMenuParser.flattenMenu(
            "麦当劳",
            "mcd",
            JSONObject().put("data", JSONObject()),
        )
        assertTrue(payload.optBoolean("empty"))
        assertTrue(payload.optString("hint").contains("storeCode"))
        assertEquals(0, payload.getJSONArray("items").length())
    }
}
