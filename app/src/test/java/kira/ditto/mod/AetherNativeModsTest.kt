package kira.ditto.mod

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AetherNativeModsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesNativeManifestWithDexAndLibraryPaths() {
        val packageRoot = temporaryFolder.newFolder("demo-mod")
        File(packageRoot, "mod.dex").writeBytes(byteArrayOf(1, 2, 3))
        File(packageRoot, "lib").mkdirs()

        val manifest = parseAetherNativeModManifest(
            packageRoot = packageRoot,
            manifest = JSONObject(
                """
                {
                  "name": "demo-native-mod",
                  "version": "1.2.3",
                  "aether": {
                    "native": {
                      "classpath": "./mod.dex",
                      "libraryPath": "./lib",
                      "entrypoints": ["example.DemoMod"]
                    }
                  }
                }
                """.trimIndent()
            ),
        )

        assertEquals("demo-native-mod", manifest?.descriptor?.id)
        assertEquals(listOf("example.DemoMod"), manifest?.descriptor?.entrypoints)
        assertEquals("mod.dex", manifest?.classpath?.single()?.name)
        assertEquals("lib", manifest?.libraryPaths?.single()?.name)
    }

    @Test
    fun ignoresPackagesWithoutNativeManifest() {
        val packageRoot = temporaryFolder.newFolder("script-only")

        assertNull(
            parseAetherNativeModManifest(
                packageRoot = packageRoot,
                manifest = JSONObject(
                    """
                    {
                      "name": "script-only",
                      "aether": {
                        "extensions": ["./index.ts"]
                      }
                    }
                    """.trimIndent()
                ),
            )
        )
    }

    @Test
    fun rejectsIncompatibleNativeApiRange() {
        val failure = try {
            requireAetherApiCompatibility(
                configured = JSONObject().put("min", 2).put("max", 3),
                currentVersion = 1,
                label = "Native mod demo",
            )
            fail("Expected incompatible API range")
            return
        } catch (throwable: IllegalArgumentException) {
            throwable
        }

        assertTrue(failure.message.orEmpty().contains("requires API 2 or newer"))
    }

    @Test
    fun allowsNewerNativeApiWhenManifestOptsIn() {
        requireAetherApiCompatibility(
            configured = JSONObject()
                .put("min", 1)
                .put("max", 1)
                .put("allowNewer", true),
            currentVersion = 2,
            label = "Native mod demo",
        )
    }

    @Test
    fun nativeToolTitlesPreferPriorityAndCleanupByOwner() {
        val registry = AetherNativeToolTitleRegistry()
        val low = registry.register("Demo", "Using demo", "Used demo", "low", priority = 1)
        registry.register("demo", "Running demo", "Completed demo", "high", priority = 2)
        assertEquals("Running demo", registry.titleFor("DEMO", true))
        assertEquals("Completed demo", registry.titleFor("demo", false))
        registry.unregisterOwner("high")
        assertEquals("Using demo", registry.titleFor("demo", true))
        low()
        assertNull(registry.titleFor("demo", false))
    }
}
