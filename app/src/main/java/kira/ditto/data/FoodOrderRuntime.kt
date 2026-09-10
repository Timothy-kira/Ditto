package kira.ditto.data

import android.content.Context
import android.location.Geocoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class FoodOrderRuntime(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val secrets = HostSecretStore(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val sessions = ConcurrentHashMap<String, McpSession>()

    fun handle(manifest: kira.ditto.upa.UpaManifest, name: String, arguments: JSONObject): JSONObject {
        if (!UpaPluginHostState.allows(manifest.id, "network")) {
            error("network permission is off for this plugin")
        }
        val payload = when (name) {
            "food.login_status" -> loginStatus(arguments.optString("brand"))
            "food.save_token" -> saveToken(arguments.optString("brand"), arguments.optString("token"))
            "food.logout" -> logout(arguments.optString("brand"))
            "food.search_stores" -> searchStores(arguments)
            "food.search_menu" -> searchMenu(arguments)
            "food.preview" -> preview(arguments)
            "food.order" -> createOrder(arguments)
            "food.order_status" -> orderStatus(arguments)
            "food.cancel" -> cancel(arguments)
            "food.coupons" -> coupons(arguments)
            "food.calendar" -> calendar()
            "food.nutrition" -> nutrition()
            "food.points" -> points()
            "food.addresses" -> addresses(arguments)
            "food.call" -> callNamed(
                brand = requireBrand(arguments.optString("brand")),
                tool = arguments.optString("tool").trim(),
                args = arguments.optJSONObject("arguments") ?: JSONObject(),
            )
            else -> error("Unknown food tool: $name")
        }
        return payload
    }

    fun loginStatus(brand: String = ""): JSONObject {
        val wanted = brand.trim().lowercase()
        val luckin = secrets.has(HostSecretStore.FoodLuckinToken)
        val mcd = secrets.has(HostSecretStore.FoodMcdToken)
        val target = when {
            wanted.contains("luckin") || wanted.contains("瑞幸") -> Brand.Luckin
            wanted.contains("mcd") || wanted.contains("麦当劳") || wanted.contains("mcdonald") -> Brand.Mcd
            luckin -> Brand.Luckin
            mcd -> Brand.Mcd
            else -> Brand.Luckin
        }
        val loggedIn = when (target) {
            Brand.Luckin -> luckin
            Brand.Mcd -> mcd
        }
        val payload = JSONObject()
            .put("loggedIn", loggedIn)
            .put("needLogin", !loggedIn)
            .put("luckinLoggedIn", luckin)
            .put("mcdLoggedIn", mcd)
            .put("brand", target.wire)
        return if (loggedIn) {
            val name = accountName(target)
            payload
                .put("_templateId", "food.account")
                .put("accountName", name)
                .put("title", "${target.label}已登录")
        } else {
            payload
                .put("_templateId", "food.login")
                .put("title", "登录${target.label}")
                .put("hint", "点按钮在浏览器登录，然后到插件页粘贴 Token。不要自己打开网页。")
                .put("loginLabel", "登录${target.label}")
        }
    }

    fun saveBrandToken(brand: String, token: String): JSONObject = saveToken(brand, token)

    fun isLoggedIn(brand: String): Boolean {
        val wanted = brand.trim().lowercase()
        return when {
            wanted.contains("luckin") || wanted.contains("瑞幸") ->
                secrets.has(HostSecretStore.FoodLuckinToken)
            wanted.contains("mcd") || wanted.contains("麦当劳") ->
                secrets.has(HostSecretStore.FoodMcdToken)
            else -> secrets.has(HostSecretStore.FoodLuckinToken) || secrets.has(HostSecretStore.FoodMcdToken)
        }
    }

    fun accountName(brand: String): String = accountName(
        runCatching { requireBrand(brand) }.getOrDefault(
            if (secrets.has(HostSecretStore.FoodLuckinToken)) Brand.Luckin else Brand.Mcd,
        ),
    )

    private fun saveToken(brand: String, token: String): JSONObject {
        val secret = token.trim().removePrefix("Bearer ").trim()
        require(secret.isNotBlank()) { "token required" }
        val target = requireBrand(brand)
        when (target) {
            Brand.Luckin -> secrets.put(HostSecretStore.FoodLuckinToken, secret)
            Brand.Mcd -> secrets.put(HostSecretStore.FoodMcdToken, secret)
        }
        dropSession(target)
        FoodOrderAuth.bump()
        return loginStatus(brand).put("saved", true)
    }

    fun clearToken(brand: String) {
        val target = requireBrand(brand)
        when (target) {
            Brand.Luckin -> {
                secrets.delete(HostSecretStore.FoodLuckinToken)
                secrets.delete(HostSecretStore.FoodLuckinName)
            }
            Brand.Mcd -> {
                secrets.delete(HostSecretStore.FoodMcdToken)
                secrets.delete(HostSecretStore.FoodMcdName)
            }
        }
        dropSession(target)
        FoodOrderAuth.bump()
    }

    fun logout(brand: String = ""): JSONObject {
        val wanted = brand.trim().lowercase()
        val targets = when {
            wanted.isBlank() || wanted == "all" || wanted == "both" || wanted == "全部" ->
                listOf(Brand.Luckin, Brand.Mcd)
            else -> listOf(requireBrand(brand))
        }
        targets.forEach { target ->
            when (target) {
                Brand.Luckin -> {
                    secrets.delete(HostSecretStore.FoodLuckinToken)
                    secrets.delete(HostSecretStore.FoodLuckinName)
                }
                Brand.Mcd -> {
                    secrets.delete(HostSecretStore.FoodMcdToken)
                    secrets.delete(HostSecretStore.FoodMcdName)
                }
            }
            dropSession(target)
        }
        FoodOrderAuth.bump()
        val statusBrand = when {
            targets.size == 1 -> targets.first().wire
            else -> brand
        }
        return loginStatus(statusBrand).put("loggedOut", true)
    }

    private fun searchStores(arguments: JSONObject): JSONObject {
        val brand = requireBrand(arguments.optString("brand"))
        ensureToken(brand)
        val gps = awaitDeviceCoordinates(appContext)
        val query = arguments.optString("query").trim()
        return when (brand) {
            Brand.Luckin -> {
                val args = JSONObject()
                    .put("longitude", gps?.longitude ?: arguments.optDouble("longitude"))
                    .put("latitude", gps?.latitude ?: arguments.optDouble("latitude"))
                if (query.isNotBlank()) args.put("deptName", query)
                flattenStores(brand, callMcp(brand, "queryShopList", args), "deptName", "address", "deptId")
            }
            Brand.Mcd -> {
                val mode = arguments.optString("mode").ifBlank { "pickup" }
                val beType = if (mode.contains("drive") || mode.contains("车道")) 5 else 1
                val city = arguments.optString("city").ifBlank { reverseCity(gps) }
                val keyword = query.ifBlank { reverseKeyword(gps).ifBlank { "附近" } }
                val args = JSONObject()
                    .put("searchType", 2)
                    .put("beType", beType)
                    .put("city", city.ifBlank { "北京市" })
                    .put("keyword", keyword)
                flattenStores(brand, callMcp(brand, "query-nearby-stores", args), "storeName", "address", "storeCode")
            }
        }
    }

    private fun searchMenu(arguments: JSONObject): JSONObject {
        val brand = requireBrand(arguments.optString("brand"))
        ensureToken(brand)
        val query = arguments.optString("query").trim()
        return when (brand) {
            Brand.Luckin -> {
                val deptId = firstArgument(arguments, "deptId", "storeId")
                if (deptId.isBlank()) {
                    return emptyMenu(brand, "请先搜索瑞幸门店，拿到门店 ID 后再查菜单。")
                }
                val args = JSONObject()
                    .put("deptId", deptId.toLongOrNull() ?: deptId)
                    .put("query", query.ifBlank { "推荐" })
                flattenMenu(brand, callMcp(brand, "searchProductForMcp", args))
            }
            Brand.Mcd -> {
                val storeCode = firstArgument(arguments, "storeCode", "storeId")
                val beCode = firstArgument(arguments, "beCode")
                if (storeCode.isBlank() || beCode.isBlank()) {
                    return emptyMenu(
                        brand,
                        "麦当劳查菜单必须带 storeCode 和 beCode。请先搜门店或查配送地址，不要空查。",
                    )
                }
                val args = JSONObject()
                    .put("storeCode", storeCode)
                    .put("beCode", beCode)
                    .put("orderType", arguments.optInt("orderType", 1))
                    .put("beType", arguments.optInt("beType", 1))
                flattenMenu(brand, callMcp(brand, "query-meals", args))
            }
        }
    }

    private fun emptyMenu(brand: Brand, hint: String): JSONObject =
        JSONObject()
            .put("_templateId", "food.products")
            .put("brand", brand.wire)
            .put("title", "${brand.label}菜单")
            .put("products", true)
            .put("empty", true)
            .put("count", "0 道")
            .put("hint", hint)
            .put("items", JSONArray())

    private fun preview(arguments: JSONObject): JSONObject {
        val brand = requireBrand(arguments.optString("brand"))
        ensureToken(brand)
        val raw = when (brand) {
            Brand.Luckin -> callMcp(brand, "previewOrder", arguments.without("brand"))
            Brand.Mcd -> callMcp(brand, "calculate-price", arguments.without("brand"))
        }
        return markPreview(brand, raw)
    }

    private fun createOrder(arguments: JSONObject): JSONObject {
        val brand = requireBrand(arguments.optString("brand"))
        ensureToken(brand)
        val gps = awaitDeviceCoordinates(appContext)
        val args = arguments.without("brand")
        if (brand == Brand.Luckin && gps != null) {
            if (!args.has("longitude")) args.put("longitude", gps.longitude)
            if (!args.has("latitude")) args.put("latitude", gps.latitude)
        }
        val raw = when (brand) {
            Brand.Luckin -> callMcp(brand, "createOrder", args)
            Brand.Mcd -> callMcp(brand, "create-order", args)
        }
        return markPay(brand, raw)
    }

    private fun orderStatus(arguments: JSONObject): JSONObject {
        val brand = requireBrand(arguments.optString("brand"))
        ensureToken(brand)
        val raw = when (brand) {
            Brand.Luckin -> callMcp(brand, "queryOrderDetailInfo", arguments.without("brand"))
            Brand.Mcd -> callMcp(brand, "query-order", arguments.without("brand"))
        }
        return markPickup(brand, raw)
    }

    private fun cancel(arguments: JSONObject): JSONObject {
        val brand = requireBrand(arguments.optString("brand"))
        ensureToken(brand)
        return when (brand) {
            Brand.Luckin -> slim(callMcp(brand, "cancelOrder", arguments.without("brand")))
            Brand.Mcd -> error("麦当劳 MCP 未提供取消订单工具")
        }.put("brand", brand.wire).put("_templateId", "")
    }

    private fun coupons(arguments: JSONObject): JSONObject {
        ensureToken(Brand.Mcd)
        val action = arguments.optString("action").ifBlank { "mine" }
        val raw = when (action) {
            "claim", "bind" -> callMcp(Brand.Mcd, "auto-bind-coupons", JSONObject())
            "available" -> callMcp(Brand.Mcd, "available-coupons", JSONObject())
            else -> callMcp(Brand.Mcd, "query-my-coupons", JSONObject())
        }
        return flattenNamed(Brand.Mcd, "coupons", raw, listOf("title", "couponId"))
    }

    private fun calendar(): JSONObject {
        ensureToken(Brand.Mcd)
        return flattenNamed(Brand.Mcd, "campaigns", callMcp(Brand.Mcd, "campaign-calendar", JSONObject()), listOf("title", "date"))
    }

    private fun nutrition(): JSONObject {
        ensureToken(Brand.Mcd)
        return JSONObject()
            .put("brand", Brand.Mcd.wire)
            .put("nutrition", true)
            .put("items", compactList(callMcp(Brand.Mcd, "list-nutrition-foods", JSONObject()), listOf("name", "title")))
    }

    private fun points(): JSONObject {
        ensureToken(Brand.Mcd)
        val raw = callMcp(Brand.Mcd, "query-my-account", JSONObject())
        rememberAccountName(Brand.Mcd, raw)
        return JSONObject()
            .put("brand", Brand.Mcd.wire)
            .put("points", true)
            .put("summary", firstString(raw, "points", "score", "balance", "memberName", "nickName"))
    }

    private fun addresses(arguments: JSONObject): JSONObject {
        ensureToken(Brand.Mcd)
        val action = arguments.optString("action").ifBlank { "list" }
        val raw = if (action == "create") {
            callMcp(Brand.Mcd, "delivery-create-address", arguments.without("brand", "action"))
        } else {
            callMcp(Brand.Mcd, "delivery-query-addresses", JSONObject())
        }
        return flattenNamed(Brand.Mcd, "addresses", raw, listOf("address", "name"))
    }

    private fun callNamed(brand: Brand, tool: String, args: JSONObject): JSONObject {
        require(tool.isNotBlank()) { "tool required" }
        ensureToken(brand)
        return slim(callMcp(brand, tool, args)).put("brand", brand.wire)
    }

    private fun ensureToken(brand: Brand) {
        val has = when (brand) {
            Brand.Luckin -> secrets.has(HostSecretStore.FoodLuckinToken)
            Brand.Mcd -> secrets.has(HostSecretStore.FoodMcdToken)
        }
        if (!has) {
            error("需要先登录${brand.label}。调用 food.login_status，让用户点对话里的登录按钮。不要打开网页，不要访问登录页。")
        }
    }

    private fun callMcp(brand: Brand, tool: String, arguments: JSONObject): JSONObject {
        val token = when (brand) {
            Brand.Luckin -> secrets.get(HostSecretStore.FoodLuckinToken)
            Brand.Mcd -> secrets.get(HostSecretStore.FoodMcdToken)
        }.orEmpty()
        if (token.isBlank()) error("missing token")
        val result = try {
            mcpSession(brand, token).call(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 2)
                    .put("method", "tools/call")
                    .put("params", JSONObject().put("name", tool).put("arguments", arguments)),
            )
        } catch (error: Throwable) {
            dropSession(brand)
            throw error
        }
        if (result.optJSONObject("error") != null) {
            val message = result.getJSONObject("error").optString("message").ifBlank { "MCP error" }
            if (message.contains("401") || message.contains("Unauthorized", true)) {
                dropSession(brand)
                error("Token 无效或已过期，请重新登录${brand.label}")
            }
            error(HostSecretStore.redact(message))
        }
        val body = result.optJSONObject("result") ?: result
        if (body.optBoolean("isError")) {
            val text = body.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
            error(HostSecretStore.redact(text.ifBlank { "tool failed" }))
        }
        return body.optJSONObject("structuredContent")
            ?: body.optJSONArray("content")?.optJSONObject(0)?.optString("text")
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: body
    }

    private fun mcpSession(brand: Brand, token: String): McpSession {
        val key = "${brand.wire}:${token.take(12)}"
        sessions[key]?.let { return it }
        val url = when (brand) {
            Brand.Luckin -> LuckinMcpUrl
            Brand.Mcd -> McdMcpUrl
        }
        val session = McpSession(http, url, token, McpProtocolVersion)
        session.initialize()
        sessions[key] = session
        return session
    }

    private fun dropSession(brand: Brand) {
        val prefix = "${brand.wire}:"
        sessions.keys.filter { it.startsWith(prefix) }.forEach { sessions.remove(it) }
    }

    private fun flattenStores(
        brand: Brand,
        raw: JSONObject,
        nameKey: String,
        addressKey: String,
        idKey: String,
    ): JSONObject {
        val list = extractList(raw)
        val items = JSONArray()
        val payload = JSONObject()
            .put("_templateId", "food.stores")
            .put("brand", brand.wire)
            .put("title", "${brand.label}门店")
            .put("stores", true)
            .put("count", "${list.length()} 家")
        for (index in 0 until minOf(list.length(), 5)) {
            val item = list.optJSONObject(index) ?: continue
            val id = item.optString(idKey).ifBlank { item.optString("storeCode").ifBlank { item.optString("deptId") } }
            val name = item.optString(nameKey).ifBlank { item.optString("name") }
            val address = item.optString(addressKey)
            items.put(
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("address", address)
                    .put("storeCode", item.optString("storeCode").ifBlank { id })
                    .put("beCode", item.optString("beCode").ifBlank { item.optString("beId") })
                    .put("deptId", item.opt("deptId")?.toString().orEmpty().ifBlank { id }),
            )
            if (index == 0) {
                payload.put("store0_id", id)
                payload.put("store0_name", name)
                payload.put("store0_meta", address)
                payload.put("store0_beCode", item.optString("beCode").ifBlank { item.optString("beId") })
            }
        }
        return payload.put("items", items)
    }

    private fun flattenMenu(brand: Brand, raw: JSONObject): JSONObject =
        FoodOrderMenuParser.flattenMenu(brand.label, brand.wire, raw)

    private fun firstArgument(arguments: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            if (!arguments.has(key) || arguments.isNull(key)) return@forEach
            val text = arguments.opt(key)?.toString().orEmpty().trim()
            if (text.isNotBlank() && text != "null") return text
        }
        return ""
    }

    private fun markPreview(brand: Brand, raw: JSONObject): JSONObject {
        val pay = raw.opt("discountPrice") ?: raw.opt("totalPay") ?: raw.opt("payAmount")
        return JSONObject()
            .put("_templateId", "food.preview")
            .put("brand", brand.wire)
            .put("preview", true)
            .put("payAmount", pay?.toString().orEmpty())
            .put("title", "订单预览")
    }

    private fun markPay(brand: Brand, raw: JSONObject): JSONObject {
        val payUrl = firstString(raw, "payOrderUrl", "payUrl", "paymentUrl", "payLink")
        val payQr = firstString(raw, "payOrderQrCodeUrl", "payQr", "qrCode", "qrUrl").ifBlank {
            if (payUrl.isNotBlank()) qrImageUrl(payUrl) else ""
        }
        return JSONObject()
            .put("_templateId", "food.pay")
            .put("brand", brand.wire)
            .put("payUrl", payUrl)
            .put("payQr", payQr)
            .put("orderId", firstString(raw, "orderId", "orderNo", "id"))
            .put("payAmount", firstString(raw, "discountPrice", "payAmount", "totalPay"))
            .put("title", "请支付")
    }

    private fun markPickup(brand: Brand, raw: JSONObject): JSONObject {
        val pickup = firstString(raw, "pickupCode", "takeCode", "mealCode", "code")
        return JSONObject()
            .put("_templateId", "food.pickup")
            .put("brand", brand.wire)
            .put("pickupCode", pickup)
            .put("title", if (pickup.isNotBlank()) "取餐码 $pickup" else "订单状态")
            .put("status", firstString(raw, "orderStatus", "status"))
    }

    private fun flattenNamed(brand: Brand, key: String, raw: JSONObject, fields: List<String>): JSONObject {
        val payload = JSONObject().put("brand", brand.wire).put(key, true).put("title", brand.label)
        payload.put("items", compactList(raw, fields))
        return payload
    }

    private fun compactList(raw: JSONObject, fields: List<String>): JSONArray {
        val list = extractList(raw)
        val items = JSONArray()
        for (index in 0 until minOf(list.length(), 8)) {
            val item = list.optJSONObject(index) ?: continue
            val row = JSONObject()
            fields.forEach { field ->
                item.optString(field).takeIf { it.isNotBlank() }?.let { row.put(field, it) }
            }
            if (row.length() > 0) items.put(row)
        }
        return items
    }

    private fun slim(raw: JSONObject): JSONObject {
        val text = raw.toString()
        if (text.length <= 4_000) return raw
        return JSONObject()
            .put("ok", true)
            .put("truncated", true)
            .put("preview", HostSecretStore.redact(text.take(2_000)))
    }

    private fun accountName(brand: Brand): String {
        val cached = when (brand) {
            Brand.Luckin -> secrets.get(HostSecretStore.FoodLuckinName)
            Brand.Mcd -> secrets.get(HostSecretStore.FoodMcdName)
        }.orEmpty()
        return cached.ifBlank { "${brand.label}账号" }
    }

    private fun rememberAccountName(brand: Brand, raw: JSONObject) {
        val name = extractDisplayName(raw)
        if (name.isBlank()) return
        when (brand) {
            Brand.Luckin -> secrets.put(HostSecretStore.FoodLuckinName, name)
            Brand.Mcd -> secrets.put(HostSecretStore.FoodMcdName, name)
        }
    }

    private fun extractDisplayName(raw: JSONObject): String {
        val keys = listOf(
            "nickName", "nickname", "userName", "username", "memberName",
            "accountName", "name", "phone", "mobile", "phoneNumber",
        )
        keys.forEach { key ->
            firstString(raw, key).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun extractList(raw: JSONObject): JSONArray {
        raw.optJSONArray("data")?.let { return it }
        raw.optJSONObject("data")?.optJSONArray("list")?.let { return it }
        raw.optJSONObject("data")?.optJSONArray("addresses")?.let { return it }
        raw.optJSONArray("list")?.let { return it }
        val data = raw.opt("data")
        if (data is JSONArray) return data
        return JSONArray()
    }

    private fun firstString(raw: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = raw.optString(key)
            if (value.isNotBlank()) return value
            raw.optJSONObject("data")?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun reverseCity(gps: DeviceCoordinates?): String {
        if (gps == null || !Geocoder.isPresent()) return ""
        return runCatching {
            val results = Geocoder(appContext, Locale.CHINA).getFromLocation(gps.latitude, gps.longitude, 1)
            results?.firstOrNull()?.let { it.adminArea.orEmpty().ifBlank { it.locality } }.orEmpty()
        }.getOrDefault("")
    }

    private fun reverseKeyword(gps: DeviceCoordinates?): String {
        if (gps == null || !Geocoder.isPresent()) return ""
        return runCatching {
            val results = Geocoder(appContext, Locale.CHINA).getFromLocation(gps.latitude, gps.longitude, 1)
            val addr = results?.firstOrNull() ?: return ""
            addr.subLocality.orEmpty().ifBlank { addr.thoroughfare }.orEmpty().ifBlank { addr.featureName }
        }.getOrDefault("")
    }

    private fun requireBrand(raw: String): Brand {
        val value = raw.trim().lowercase()
        return when {
            value.contains("luckin") || value.contains("瑞幸") || value.contains("coffee") -> Brand.Luckin
            value.contains("mcd") || value.contains("麦当劳") || value.contains("mcdonald") -> Brand.Mcd
            else -> error("brand 必须是 luckin 或 mcd")
        }
    }

    private fun JSONObject.without(vararg keys: String): JSONObject {
        val copy = JSONObject(toString())
        keys.forEach { copy.remove(it) }
        return copy
    }

    private enum class Brand(val wire: String, val label: String) {
        Luckin("luckin", "瑞幸"),
        Mcd("mcd", "麦当劳"),
    }

    private class McpSession(
        private val http: OkHttpClient,
        private val url: String,
        private val token: String,
        private val protocol: String,
    ) {
        private var sessionId: String = ""

        fun initialize() {
            val params = JSONObject()
                .put("protocolVersion", protocol)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Ditto").put("version", "0.1"))
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put("params", params)
            call(body)
            postNotification("notifications/initialized")
        }

        fun call(message: JSONObject): JSONObject {
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("MCP-Protocol-Version", protocol)
                .addHeader("Authorization", "Bearer $token")
                .apply { if (sessionId.isNotBlank()) addHeader("Mcp-Session-Id", sessionId) }
                .post(message.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                response.header("Mcp-Session-Id")?.trim()?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401) error("Unauthorized")
                    error("HTTP ${response.code}")
                }
                val contentType = response.header("Content-Type").orEmpty()
                return parseMcpBody(text, contentType)
            }
        }

        private fun postNotification(method: String) {
            runCatching {
                call(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("method", method)
                        .put("params", JSONObject()),
                )
            }
        }

        private fun parseMcpBody(text: String, contentType: String): JSONObject {
            val payload = if (contentType.contains("event-stream", ignoreCase = true)) {
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.isNotBlank() && it != "[DONE]" }
                    .orEmpty()
            } else {
                text.trim()
            }
            if (payload.isBlank()) return JSONObject()
            return runCatching { JSONObject(payload) }.getOrElse {
                JSONObject().put("result", JSONObject().put("text", payload.take(4000)))
            }
        }
    }

    companion object {
        const val LuckinMcpUrl = "https://gwmcp.lkcoffee.com/order/user/mcp"
        const val McdMcpUrl = "https://mcp.mcd.cn"
        const val LuckinLoginUrl = "https://open.lkcoffee.com/mcp"
        const val McdLoginUrl = "https://open.mcd.cn/mcp"
        private const val McpProtocolVersion = "2025-06-18"

        fun loginUrlForBrand(brand: String): String {
            val wanted = brand.trim().lowercase()
            return when {
                wanted.contains("mcd") || wanted.contains("麦当劳") || wanted.contains("mcdonald") -> McdLoginUrl
                else -> LuckinLoginUrl
            }
        }

        fun qrImageUrl(target: String): String {
            val encoded = java.net.URLEncoder.encode(target, Charsets.UTF_8.name())
            return "https://api.qrserver.com/v1/create-qr-code/?size=280x280&data=$encoded"
        }
    }
}

object FoodOrderAuth {
    private val revisionState = MutableStateFlow(0)
    val revision: StateFlow<Int> = revisionState

    fun bump() {
        revisionState.value += 1
    }
}
