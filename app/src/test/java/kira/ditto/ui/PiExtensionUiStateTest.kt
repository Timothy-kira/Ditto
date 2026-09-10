package kira.ditto.ui

import kira.ditto.data.InstalledPiExtension
import kira.ditto.data.PiExtensionInstallKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PiExtensionUiStateTest {
    @Test
    fun importedRefreshReplacesStaleImportsAndPreservesPackages() {
        val installedPackage = extension(
            id = "package:npm:example",
            name = "Example package",
            kind = PiExtensionInstallKind.Package,
        )
        val staleImport = extension(
            id = "import:aether:/old",
            name = "Old import",
            kind = PiExtensionInstallKind.Imported,
        )
        val currentImport = extension(
            id = "import:aether:/current",
            name = "Current import",
            kind = PiExtensionInstallKind.Imported,
        )

        assertEquals(
            listOf(currentImport, installedPackage),
            mergeImportedPiExtensions(
                current = listOf(staleImport, installedPackage),
                imported = listOf(currentImport),
            ),
        )
    }

    private fun extension(
        id: String,
        name: String,
        kind: PiExtensionInstallKind,
    ) = InstalledPiExtension(
        id = id,
        name = name,
        source = id,
        kind = kind,
    )
}
