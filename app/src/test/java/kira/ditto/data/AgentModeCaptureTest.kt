package kira.ditto.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeCaptureTest {
    @Test
    fun observeTrueAndSomBothRequestAFrame() {
        assertTrue(AgentModeCapture.wantsObserve(JSONObject().put("observe", true)))
        assertTrue(AgentModeCapture.wantsObserve(JSONObject().put("observe", "som")))
        assertTrue(AgentModeCapture.wantsSom(JSONObject().put("observe", "som")))
        assertFalse(AgentModeCapture.wantsSom(JSONObject().put("observe", true)))
        assertFalse(AgentModeCapture.wantsObserve(JSONObject()))
    }

    @Test
    fun screenshotCropUsesNormalizedRegion() {
        val top = AgentModeCapture.pixelCrop(
            imageWidth = 1000,
            imageHeight = 2000,
            arguments = JSONObject().put("crop_region", "top"),
        )
        requireNotNull(top)
        assertEquals(0, top.left)
        assertEquals(0, top.top)
        assertEquals(1000, top.width)
        assertEquals(668, top.height)

        val explicit = AgentModeCapture.pixelCrop(
            imageWidth = 1000,
            imageHeight = 1000,
            arguments = JSONObject()
                .put("crop_left", 100)
                .put("crop_top", 200)
                .put("crop_right", 500)
                .put("crop_bottom", 800),
        )
        requireNotNull(explicit)
        assertEquals(100, explicit.left)
        assertEquals(200, explicit.top)
        assertEquals(400, explicit.width)
        assertEquals(600, explicit.height)
        assertNull(
            AgentModeCapture.pixelCrop(
                imageWidth = 720,
                imageHeight = 1280,
                arguments = JSONObject().put("action", "screenshot"),
            ),
        )
    }
}
