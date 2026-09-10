package kira.ditto.kimi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KimiCodeRuntimeControllerTest {
    @Test
    fun installsOnlyThePinnedOfficialPackage() {
        val arguments = kimiCodeInstallArguments()

        assertTrue(arguments.contains("$KimiCodePackageName@$KimiCodePackageVersion"))
        assertEquals("/root/.kimi-code-mobile", arguments[arguments.indexOf("--prefix") + 1])
    }

    @Test
    fun startsTheOfficialLoopbackWebServer() {
        val arguments = kimiCodeServerArguments()

        assertEquals("web", arguments[1])
        assertTrue(arguments.contains("--no-open"))
        assertEquals("127.0.0.1", arguments[arguments.indexOf("--host") + 1])
    }
}
