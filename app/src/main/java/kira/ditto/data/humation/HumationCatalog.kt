package kira.ditto.data.humation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import kira.ditto.data.PersonaAvatarSpec
import java.util.concurrent.ConcurrentHashMap

class HumationCatalog(
    val manifest: HumationManifest,
    private val loadAsset: (String) -> String,
) {
    private val svgCache = ConcurrentHashMap<String, String>()

    fun loadSvg(path: String): String = svgCache.getOrPut(path) { loadAsset(path) }

    fun renderSvg(spec: PersonaAvatarSpec): String = renderHumationSvg(
        manifest = manifest,
        options = spec.toOptions(),
        loadSvg = ::loadSvg,
    )

    fun nextPartId(slotId: String, currentPartId: String): String {
        val parts = manifest.partsForSlot(slotId)
        if (parts.isEmpty()) return currentPartId
        val index = parts.indexOfFirst { it.id == currentPartId }
        return parts[(index + 1).mod(parts.size)].id
    }

    fun partsForSlot(slotId: String): List<HumationPart> = manifest.partsForSlot(slotId)

    fun partName(partId: String): String =
        manifest.parts.firstOrNull { it.id == partId }?.name.orEmpty()

    companion object {
        @Volatile
        private var instance: HumationCatalog? = null

        fun get(context: Context): HumationCatalog {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val assets = context.applicationContext.assets
                val manifest = parseHumationManifest(
                    assets.open("humation/manifest.json").bufferedReader().use { it.readText() },
                )
                val created = HumationCatalog(manifest) { path ->
                    assets.open("humation/$path").bufferedReader().use { it.readText() }
                }
                instance = created
                return created
            }
        }
    }
}

fun PersonaAvatarSpec.toOptions(): HumationAvatarOptions = HumationAvatarOptions(
    seed = seed,
    selections = selections,
    colors = colors,
    background = background.takeIf { it.isNotBlank() },
)

object HumationRenderer {
    fun renderBitmap(context: Context, spec: PersonaAvatarSpec, sizePx: Int): Bitmap {
        val catalog = HumationCatalog.get(context)
        val svg = SVG.getFromString(catalog.renderSvg(spec))
        val bitmap = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        svg.setDocumentWidth(sizePx.toFloat())
        svg.setDocumentHeight(sizePx.toFloat())
        svg.renderToCanvas(canvas)
        return bitmap
    }
}

val HumationColorPresets = listOf(
    "000000",
    "FFFFFF",
    "F6F5F4",
    "FF6B6B",
    "FFB703",
    "8ECAE6",
    "219EBC",
    "2A9D8F",
    "E9C46A",
    "F4A261",
    "E76F51",
    "8338EC",
    "3A86FF",
    "FB5607",
    "C1121F",
    "6D6875",
)
