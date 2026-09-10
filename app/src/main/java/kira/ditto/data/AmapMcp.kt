package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in Amap MCP on the loopback gateway.
 * Tool names match the official Amap Maps MCP. Auth is a Web 服务 Key.
 */
internal object AmapMcp {
    const val PluginId = "aether-amap"
    const val ServerName = "amap"

    val ToolNames: List<String> = listOf(
        "maps_geo",
        "maps_regeocode",
        "maps_ip_location",
        "maps_weather",
        "maps_text_search",
        "maps_around_search",
        "maps_search_detail",
        "maps_direction_driving",
        "maps_direction_walking",
        "maps_direction_bicycling",
        "maps_bicycling",
        "maps_direction_transit_integrated",
        "maps_distance",
        "maps_schema_personal_map",
        "maps_schema_navi",
        "maps_schema_take_taxi",
    )

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        actionLabel = "高德",
        transport = McpTransportConfig.StreamableHttp(
            url = httpUrl(),
            headers = learningSessionHeaders(sessionId),
        ),
        isEnabled = true,
    )

    fun isShippedServerId(serverId: String): Boolean = serverId == PluginId

    fun toAcpServer(sessionId: String = ""): JSONObject? =
        mcpServerConfig(sessionId).toAcpMcpServer()

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", ServerName)
                .put("version", "1"),
        )

    fun listToolsResult(): JSONObject = JSONObject().put("tools", toolsArray())

    fun matchesToolName(name: String): Boolean {
        val n = name.trim().lowercase().replace('-', '_')
        if (n.contains("amap") && ToolNames.any { tool -> n.endsWith(tool) || n.contains("_$tool") }) {
            return true
        }
        return ToolNames.any { tool ->
            n == tool || n.endsWith("_$tool") || n.endsWith("__$tool")
        }
    }

    fun canonicalToolName(name: String): String {
        val n = name.trim().lowercase().replace('-', '_')
        if (n == "maps_bicycling" || n.endsWith("_maps_bicycling") || n.endsWith("__maps_bicycling")) {
            return "maps_direction_bicycling"
        }
        return ToolNames.firstOrNull { tool ->
            n == tool || n.endsWith("_$tool") || n.endsWith("__$tool")
        }.orEmpty()
    }

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        val visible = parsed?.toString() ?: rawOutput
        val code = parsed?.optString("code").orEmpty()
        val isError = parsed?.optBoolean("ok", true) == false && code != "input_required"
        val result = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", visible),
                ),
            )
            .put("structuredContent", parsed ?: JSONObject().put("raw", rawOutput))
            .put("isError", isError)
        if (code == "input_required") {
            result.put("_meta", JSONObject().put("input_required", true))
        }
        return result
    }

    private fun toolsArray(): JSONArray = JSONArray().apply {
        put(tool("maps_geo", "地理编码：地址转经纬度。", extra = JSONObject()
            .put("address", stringProp("结构化地址"))
            .put("city", stringProp("城市，可选")), required = listOf("address")))
        put(tool("maps_regeocode", "逆地理编码：经纬度转地址。", extra = JSONObject()
            .put("location", stringProp("经度,纬度")), required = listOf("location")))
        put(tool("maps_ip_location", "IP 定位到城市。不要用于「周边/附近」查询。", extra = JSONObject()
            .put("ip", stringProp("IPv4，可空则用当前出口 IP"))))
        put(tool("maps_weather", "按城市名或 adcode 查天气。", extra = JSONObject()
            .put("city", stringProp("城市名或 adcode")), required = listOf("city")))
        put(tool(
            "maps_text_search",
            "按关键词在指定城市搜索 POI。用户说周边/附近时必须改用 maps_around_search，不要用城市名或 IP 定位代替 GPS。回复按距离分段（如 500 米内、1 公里左右、1.5–2 公里），每段下列店名（可加粗），结尾可以自动推荐最近或评分最高的店。不要写距离数字、评分、人均、地址、招牌菜——这些已在店铺卡上。不要用表格。",
            extra = JSONObject()
                .put("keywords", stringProp("搜索关键词"))
                .put("city", stringProp("城市，可选"))
                .put("citylimit", stringProp("是否限制在城市内，true/false"))
                .put("offset", intProp("条数，默认 10，最大 25")),
            required = listOf("keywords"),
        ))
        put(tool(
            "maps_around_search",
            "查附近/周边必须用本工具。省略 location 时宿主自动填入设备 GPS（GCJ-02 经度,纬度）。不要用 maps_text_search 或 maps_ip_location 代替。回复按距离分段（如 500 米内、1 公里左右、1.5–2 公里），每段下列店名（可加粗），结尾可以自动推荐最近或评分最高的店。不要写距离数字、评分、人均、地址、招牌菜——这些已在店铺卡上。不要用表格。",
            extra = JSONObject()
                .put("keywords", stringProp("关键词，可选"))
                .put("location", stringProp("中心点 经度,纬度；可空则用设备 GPS"))
                .put("radius", stringProp("半径米，默认 3000"))
                .put("offset", intProp("条数，默认 10，最大 25")),
            required = listOf(),
        ))
        put(tool("maps_search_detail", "按 POI id 查详情，含 photos。", extra = JSONObject()
            .put("id", stringProp("POI id"))
            .put("poiid", stringProp("Alias for id")), required = listOf()))
        put(tool("maps_direction_driving", "驾车路径规划。", extra = JSONObject()
            .put("origin", stringProp("起点 经度,纬度"))
            .put("destination", stringProp("终点 经度,纬度")), required = listOf("origin", "destination")))
        put(tool("maps_direction_walking", "步行路径规划。", extra = JSONObject()
            .put("origin", stringProp("起点 经度,纬度"))
            .put("destination", stringProp("终点 经度,纬度")), required = listOf("origin", "destination")))
        put(tool("maps_direction_bicycling", "骑行路径规划。", extra = JSONObject()
            .put("origin", stringProp("起点 经度,纬度"))
            .put("destination", stringProp("终点 经度,纬度")), required = listOf("origin", "destination")))
        put(tool("maps_bicycling", "骑行路径规划（maps_direction_bicycling 别名）。", extra = JSONObject()
            .put("origin", stringProp("起点 经度,纬度"))
            .put("destination", stringProp("终点 经度,纬度")), required = listOf("origin", "destination")))
        put(tool("maps_direction_transit_integrated", "公交/综合交通规划。跨城需 city 与 cityd。", extra = JSONObject()
            .put("origin", stringProp("起点 经度,纬度"))
            .put("destination", stringProp("终点 经度,纬度"))
            .put("city", stringProp("起点城市"))
            .put("cityd", stringProp("终点城市")), required = listOf("origin", "destination")))
        put(tool("maps_distance", "测距。", extra = JSONObject()
            .put("origin", stringProp("起点 经度,纬度"))
            .put("destination", stringProp("终点 经度,纬度"))
            .put("type", stringProp("0 直线 1 驾车 3 步行")), required = listOf("origin", "destination")))
        put(tool("maps_schema_personal_map", "生成高德专属地图唤端链接。", extra = JSONObject()
            .put("name", stringProp("行程或点位名称"))
            .put("location", stringProp("经度,纬度"))
            .put("locations", stringProp("多个点，分号分隔 经度,纬度"))))
        put(tool("maps_schema_navi", "导航到目的地的高德唤端链接。", extra = JSONObject()
            .put("location", stringProp("目的地 经度,纬度"))
            .put("name", stringProp("目的地名称")), required = listOf("location")))
        put(tool("maps_schema_take_taxi", "打车唤端链接。", extra = JSONObject()
            .put("origin", stringProp("起点 经度,纬度"))
            .put("destination", stringProp("终点 经度,纬度"))
            .put("sname", stringProp("起点名称"))
            .put("dname", stringProp("终点名称")), required = listOf("origin", "destination")))
    }

    private fun tool(
        name: String,
        description: String,
        extra: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put("properties", extra)
                .put("required", JSONArray(required))
                .put("additionalProperties", false),
        )

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)
}
