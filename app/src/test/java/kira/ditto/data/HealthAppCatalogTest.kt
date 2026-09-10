package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAppCatalogTest {
    @Test
    fun coversMainstreamBrandHealthApps() {
        val packages = HealthAppCatalog.apps.map { it.packageName }.toSet()
        val required = listOf(
            "com.google.android.apps.healthdata",
            "com.google.android.apps.fitness",
            "com.huawei.health",
            "com.hihonor.health",
            "com.mi.health",
            "com.heytap.health",
            "com.vivo.health",
            "com.sec.android.app.shealth",
            "com.gotokeep.keep",
            "com.tencent.mm",
        )
        required.forEach { pkg ->
            assertTrue(pkg, packages.contains(pkg))
        }
        assertEquals(packages.size, HealthAppCatalog.apps.size)
    }

    @Test
    fun matchesPhoneNameToVendorHealthApp() {
        val huawei = HealthAppCatalog.matchPhone("HUAWEI", "HUAWEI", "ALN-AL00", "HUAWEI Mate 60")
        assertEquals("Huawei", huawei.matchedBrand)
        assertEquals("com.huawei.health", huawei.recommended?.packageName)
        val honor = HealthAppCatalog.matchPhone("HONOR", "HONOR", "ANY-AN00", "荣耀 Magi")
        assertEquals("Honor", honor.matchedBrand)
        assertEquals("com.hihonor.health", honor.recommended?.packageName)
        val xiaomi = HealthAppCatalog.matchPhone("Xiaomi", "Redmi", "23013RK75C", "")
        assertEquals("Xiaomi", xiaomi.matchedBrand)
        assertEquals("com.mi.health", xiaomi.recommended?.packageName)
    }
}
