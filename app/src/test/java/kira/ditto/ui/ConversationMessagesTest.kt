package kira.ditto.ui

import java.io.FileNotFoundException
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationMessagesTest {
    @Test
    fun decodeUriAttachmentBitmapReturnsNullWhenPickerUriIsUnavailable() {
        val bitmap = decodeUriAttachmentBitmap(
            uriString = "content://media/picker/0/com.android.providers.media.photopicker/media/1000012900",
            maxSize = 600,
            openInputStream = { throw FileNotFoundException("File not found for uri") },
        )

        assertNull(bitmap)
    }
}
