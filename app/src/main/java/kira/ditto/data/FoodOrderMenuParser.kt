package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

internal data class FoodMenuItem(
    val id: String,
    val name: String,
    val price: String,
)

internal object FoodOrderMenuParser {
    const val CardItemLimit = 8
    const val ModelItemLimit = 20

    fun collectProducts(raw: JSONObject): List<FoodMenuItem> {
        val fromMap = collectMealsMap(raw)
        if (fromMap.isNotEmpty()) return fromMap
        val fromArrays = collectProductArrays(raw)
        if (fromArrays.isNotEmpty()) return fromArrays
        return collectByWalking(raw)
    }

    fun flattenMenu(brandLabel: String, brandWire: String, raw: JSONObject): JSONObject {
        val products = collectProducts(raw)
        val items = JSONArray()
        val payload = JSONObject()
            .put("_templateId", "food.products")
            .put("brand", brandWire)
            .put("title", "${brandLabel}菜单")
            .put("products", true)
            .put("count", "${products.size} 道")
        if (products.isEmpty()) {
            payload.put("empty", true)
            payload.put("hint", "这家店菜单接口没有返回可展示的菜品。请先选好门店（麦当劳需要 storeCode 和 beCode），或换个关键词再搜。")
            return payload.put("items", items)
        }
        products.take(ModelItemLimit).forEach { product ->
            items.put(
                JSONObject()
                    .put("id", product.id)
                    .put("name", product.name)
                    .put("price", product.price),
            )
        }
        products.take(CardItemLimit).forEachIndexed { index, product ->
            payload.put("product${index}_id", product.id)
            payload.put("product${index}_name", product.name)
            payload.put("product${index}_meta", product.price)
        }
        return payload.put("items", items)
    }

    private fun collectMealsMap(raw: JSONObject): List<FoodMenuItem> {
        val meals = raw.optJSONObject("data")?.optJSONObject("meals")
            ?: raw.optJSONObject("meals")
            ?: return emptyList()
        if (meals.length() == 0) return emptyList()
        val keys = meals.keys()
        val items = mutableListOf<FoodMenuItem>()
        while (keys.hasNext()) {
            val code = keys.next()
            val meal = meals.optJSONObject(code) ?: continue
            val name = meal.optString("name")
                .ifBlank { meal.optString("productName") }
                .ifBlank { meal.optString("comboName") }
            if (name.isBlank()) continue
            items += FoodMenuItem(
                id = code,
                name = name,
                price = meal.opt("currentPrice")?.toString()
                    ?.ifBlank { null }
                    ?: meal.opt("price")?.toString().orEmpty(),
            )
        }
        return items
    }

    private fun collectProductArrays(raw: JSONObject): List<FoodMenuItem> {
        val arrays = listOfNotNull(
            raw.optJSONArray("data"),
            raw.optJSONObject("data")?.optJSONArray("list"),
            raw.optJSONObject("data")?.optJSONArray("productList"),
            raw.optJSONObject("data")?.optJSONArray("products"),
            raw.optJSONObject("data")?.optJSONArray("items"),
            raw.optJSONArray("list"),
            raw.optJSONArray("productList"),
            raw.optJSONArray("products"),
        )
        val items = mutableListOf<FoodMenuItem>()
        val seen = mutableSetOf<String>()
        arrays.forEach { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseProduct(item)?.let { product ->
                    if (seen.add("${product.id}|${product.name}")) items += product
                }
            }
        }
        return items
    }

    private fun collectByWalking(raw: JSONObject): List<FoodMenuItem> {
        val items = mutableListOf<FoodMenuItem>()
        val seen = mutableSetOf<String>()
        fun walk(value: Any?, depth: Int) {
            if (items.size >= ModelItemLimit || depth > 6) return
            when (value) {
                is JSONObject -> {
                    parseProduct(value)?.let { product ->
                        if (seen.add("${product.id}|${product.name}")) items += product
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()), depth + 1)
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) walk(value.opt(index), depth + 1)
                }
            }
        }
        walk(raw, 0)
        return items
    }

    private fun parseProduct(item: JSONObject): FoodMenuItem? {
        val name = item.optString("productName")
            .ifBlank { item.optString("name") }
            .ifBlank { item.optString("comboName") }
            .ifBlank { item.optString("itemName") }
        if (name.isBlank()) return null
        val hasProductShape = item.has("productId") ||
            item.has("productName") ||
            item.has("skuCode") ||
            item.has("estimatePrice") ||
            item.has("currentPrice") ||
            item.has("comboName")
        if (!hasProductShape) return null
        val id = item.opt("productId")?.toString().orEmpty()
            .ifBlank { item.optString("id") }
            .ifBlank { item.optString("code") }
            .ifBlank { item.optString("skuCode") }
        val price = item.opt("estimatePrice")?.toString()
            ?.ifBlank { null }
            ?: item.opt("currentPrice")?.toString()?.ifBlank { null }
            ?: item.opt("initialPrice")?.toString()?.ifBlank { null }
            ?: item.opt("price")?.toString().orEmpty()
        return FoodMenuItem(id = id, name = name, price = price)
    }
}
