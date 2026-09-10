package kira.ditto.data

import android.content.Context
import android.content.pm.PackageManager

data class HealthAppEntry(
    val packageName: String,
    val brand: String,
    val label: String,
)

data class HealthAppMatch(
    val matchedBrand: String,
    val recommended: HealthAppEntry?,
    val catalog: List<HealthAppEntry>,
)

object HealthAppCatalog {
    val apps: List<HealthAppEntry> = listOf(
        HealthAppEntry("com.google.android.apps.healthdata", "Google", "Health Connect"),
        HealthAppEntry("com.google.android.apps.fitness", "Google", "Google Fit"),
        HealthAppEntry("com.google.android.gms", "Google", "Google Play services"),
        HealthAppEntry("com.google.android.wearable.app", "Google", "Wear OS"),
        HealthAppEntry("com.huawei.health", "Huawei", "华为运动健康"),
        HealthAppEntry("com.huawei.bone", "Huawei", "华为穿戴"),
        HealthAppEntry("com.huawei.hwid", "Huawei", "华为账号"),
        HealthAppEntry("com.hihonor.health", "Honor", "荣耀运动健康"),
        HealthAppEntry("com.hihonor.bone", "Honor", "荣耀穿戴"),
        HealthAppEntry("com.mi.health", "Xiaomi", "小米运动健康"),
        HealthAppEntry("com.xiaomi.hm.health", "Xiaomi", "Zepp Life / 小米运动"),
        HealthAppEntry("com.xiaomi.wearable", "Xiaomi", "小米穿戴"),
        HealthAppEntry("com.huami.watch", "Zepp", "Zepp"),
        HealthAppEntry("com.huami.midong", "Zepp", "Zepp Life"),
        HealthAppEntry("com.zepp.sport", "Zepp", "Zepp Sport"),
        HealthAppEntry("com.heytap.health", "OPPO", "OPPO 健康 / OHealth"),
        HealthAppEntry("com.heytap.health.international", "OPPO", "OHealth"),
        HealthAppEntry("com.oplus.health", "OPPO", "ColorOS 健康"),
        HealthAppEntry("com.oneplus.health", "OnePlus", "OnePlus Health"),
        HealthAppEntry("com.realme.health", "realme", "realme 健康"),
        HealthAppEntry("com.vivo.health", "vivo", "vivo 健康"),
        HealthAppEntry("com.vivo.healthwidget", "vivo", "vivo 健康小组件"),
        HealthAppEntry("com.sec.android.app.shealth", "Samsung", "Samsung Health"),
        HealthAppEntry("com.samsung.android.service.health", "Samsung", "Samsung Health service"),
        HealthAppEntry("com.samsung.android.app.watchmanager", "Samsung", "Galaxy Wearable"),
        HealthAppEntry("com.samsung.android.heartplugin", "Samsung", "Samsung Health Monitor"),
        HealthAppEntry("com.meizu.flyme.health", "Meizu", "Flyme 健康"),
        HealthAppEntry("com.nubia.health", "nubia", "nubia 健康"),
        HealthAppEntry("cn.nubia.health", "nubia", "nubia 健康"),
        HealthAppEntry("com.zte.heartyservice", "ZTE", "中兴健康"),
        HealthAppEntry("com.motorola.motocare", "Motorola", "Moto Care"),
        HealthAppEntry("com.sonymobile.lifelog", "Sony", "Lifelog"),
        HealthAppEntry("com.transsion.health", "Transsion", "传音健康"),
        HealthAppEntry("com.lenovo.health", "Lenovo", "联想健康"),
        HealthAppEntry("com.fitbit.FitbitMobile", "Fitbit", "Fitbit"),
        HealthAppEntry("com.garmin.android.apps.connectmobile", "Garmin", "Garmin Connect"),
        HealthAppEntry("com.polar.polarflow", "Polar", "Polar Flow"),
        HealthAppEntry("com.stt.android", "Suunto", "Suunto"),
        HealthAppEntry("com.coros.coros", "COROS", "COROS"),
        HealthAppEntry("com.withings.wiscale2", "Withings", "Withings Health Mate"),
        HealthAppEntry("com.ouraring.oura", "Oura", "Oura"),
        HealthAppEntry("com.whoop.app", "WHOOP", "WHOOP"),
        HealthAppEntry("com.gotokeep.keep", "Keep", "Keep"),
        HealthAppEntry("com.codoon.gps", "Codoon", "咕咚"),
        HealthAppEntry("com.yuedong.sport", "Yuedong", "悦动圈"),
        HealthAppEntry("com.boohee.one", "Boohee", "薄荷健康"),
        HealthAppEntry("cn.ledongli.ldl", "Ledongli", "乐动力"),
        HealthAppEntry("com.mandian.android.dongdong", "Dongdong", "动动"),
        HealthAppEntry("com.lefu.pp", "Lifesense", "乐心运动"),
        HealthAppEntry("com.strava", "Strava", "Strava"),
        HealthAppEntry("com.myfitnesspal.android", "MyFitnessPal", "MyFitnessPal"),
        HealthAppEntry("com.nike.plusgps", "Nike", "Nike Run Club"),
        HealthAppEntry("com.runtastic.android", "adidas", "adidas Running"),
        HealthAppEntry("com.peloton.android", "Peloton", "Peloton"),
        HealthAppEntry("com.tencent.mm", "Tencent", "微信（含微信运动）"),
        HealthAppEntry("com.tencent.mobileqq", "Tencent", "QQ（含 QQ 运动）"),
        HealthAppEntry("com.eg.android.AlipayGphone", "Alipay", "支付宝（含运动）"),
    )

    fun matchPhone(
        manufacturer: String,
        brand: String,
        model: String,
        deviceName: String = "",
    ): HealthAppMatch {
        val haystack = listOf(manufacturer, brand, model, deviceName).joinToString(" ").lowercase()
        val matchedBrand = brandFromHaystack(haystack)
        val catalog = if (matchedBrand.isBlank()) {
            emptyList()
        } else {
            apps.filter { it.brand.equals(matchedBrand, ignoreCase = true) }
        }
        return HealthAppMatch(
            matchedBrand = matchedBrand,
            recommended = catalog.firstOrNull(),
            catalog = catalog,
        )
    }

    fun installed(context: Context): List<HealthAppEntry> {
        val pm = context.packageManager
        return apps.filter { entry ->
            try {
                pm.getPackageInfo(entry.packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun brandFromHaystack(haystack: String): String {
        BrandAliases.forEach { (aliases, brand) ->
            if (aliases.any { alias -> haystack.contains(alias) }) return brand
        }
        return ""
    }

    private val BrandAliases = listOf(
        listOf("hihonor", "honor") to "Honor",
        listOf("huawei") to "Huawei",
        listOf("redmi", "poco", "blackshark", "xiaomi") to "Xiaomi",
        listOf("oneplus") to "OnePlus",
        listOf("realme") to "realme",
        listOf("heytap", "oppo", "coloros", "oplus") to "OPPO",
        listOf("iqoo", "vivo") to "vivo",
        listOf("samsung") to "Samsung",
        listOf("google", "pixel") to "Google",
        listOf("meizu", "flyme") to "Meizu",
        listOf("nubia", "redmagic") to "nubia",
        listOf("zte") to "ZTE",
        listOf("motorola", "moto") to "Motorola",
        listOf("sony") to "Sony",
        listOf("infinix", "tecno", "itel", "transsion") to "Transsion",
        listOf("lenovo") to "Lenovo",
    )
}
