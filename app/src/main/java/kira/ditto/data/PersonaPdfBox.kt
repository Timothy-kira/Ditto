package kira.ditto.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

internal object PersonaPdfBox {
    @Volatile
    private var initialized = false

    fun ensure(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PDFBoxResourceLoader.init(context.applicationContext)
            personaPdfTextExtractor = { bytes ->
                PDDocument.load(bytes).use { document ->
                    PDFTextStripper().getText(document).orEmpty()
                }
            }
            initialized = true
        }
    }
}
