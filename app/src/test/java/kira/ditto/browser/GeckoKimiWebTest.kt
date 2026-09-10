package kira.ditto.browser

import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoKimiWebTest {
    @Test
    fun oldFetchSymbolsAreGone() {
        val names = GeckoKimiWeb::class.java.declaredMethods.map { it.name }.toHashSet()
        assertTrue("fetch" !in names)
        assertTrue("fetchOnce" !in names)
    }
}
