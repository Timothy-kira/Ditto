package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AmapAuthTest {
    @Test
    fun normalizeKeyStripsLabelsQuotesAndWhitespace() {
        assertEquals("abcdef0123456789", AmapAuth.normalizeKey("  abcdef0123456789  "))
        assertEquals("abcdef0123456789", AmapAuth.normalizeKey("Key: abcdef0123456789"))
        assertEquals("abcdef0123456789", AmapAuth.normalizeKey("密钥：abcdef0123456789"))
        assertEquals("abcdef0123456789", AmapAuth.normalizeKey("\"abcdef0123456789\""))
        assertEquals("abcdef0123456789", AmapAuth.normalizeKey("abcdef 0123456789"))
        assertEquals(
            "abcdef0123456789",
            AmapAuth.normalizeKey("请复制下面的 Key\nabcdef0123456789"),
        )
        assertEquals("", AmapAuth.normalizeKey("   \n\t  "))
    }
}
