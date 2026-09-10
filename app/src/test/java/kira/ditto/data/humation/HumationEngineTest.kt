package kira.ditto.data.humation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumationEngineTest {
    private val manifest = parseHumationManifest(MINI_MANIFEST)

    @Test
    fun fnv1aMatchesOfficialUnsigned32() {
        assertEquals(4168877112L, fnv1a("kira:head").toLong())
        assertEquals(0, (fnv1a("kira:head") % 2u).toInt())
    }

    @Test
    fun seedPicksFirstHeadWhenHashModTwoIsZero() {
        val state = resolveHumationState(
            manifest,
            HumationAvatarOptions(seed = "kira"),
        )
        assertEquals("head-a", state.selections["head"])
        assertEquals("body-one", state.selections["body"])
        val again = resolveHumationState(manifest, HumationAvatarOptions(seed = "kira"))
        assertEquals(state.selections, again.selections)
    }

    @Test
    fun explicitSelectionOverridesSeed() {
        val state = resolveHumationState(
            manifest,
            HumationAvatarOptions(
                seed = "kira",
                selections = mapOf("head" to "head-b"),
                colors = mapOf("hair" to "#ff6b6b"),
            ),
        )
        assertEquals("head-b", state.selections["head"])
        assertEquals("FF6B6B", state.colors["hair"])
    }

    @Test
    fun renderCompositesPartMarkupAndAppliesColors() {
        val svg = renderHumationSvg(
            manifest,
            HumationAvatarOptions(seed = "kira", colors = mapOf("hair" to "219EBC")),
        ) { path ->
            when (path) {
                "head-a.svg" -> """<svg><path fill="var(--hm-hair, #000000)" d="M0 0"/></svg>"""
                else -> """<svg><rect fill="var(--hm-clothes, #FFFFFF)"/></svg>"""
            }
        }
        assertTrue(svg.contains("data-hm-part-id=\"head-a\""))
        assertTrue(svg.contains("#219EBC"))
        assertTrue(!svg.contains("var(--hm-hair"))
    }
}

private const val MINI_MANIFEST = """
{
  "schemaVersion": "1.0",
  "template": { "id": "humation-1" },
  "defaults": {
    "selections": { "head": "head-a", "body": "body-one" },
    "colors": { "hair": "000000", "clothes": "FFFFFF" },
    "background": "F6F5F4",
    "crop": "avatar"
  },
  "colors": [
    { "id": "hair", "label": "Hair", "default": "000000", "cssVariable": "--hm-hair" },
    { "id": "clothes", "label": "Clothes", "default": "FFFFFF", "cssVariable": "--hm-clothes" }
  ],
  "crops": { "avatar": { "x": -4, "y": -4.5, "width": 88, "height": 88 } },
  "selectionSlots": [
    { "id": "head", "label": "Head", "exclusive": true },
    { "id": "body", "label": "Body", "exclusive": true }
  ],
  "uiGroups": [],
  "layerSlots": [
    { "id": "body", "label": "Body", "order": 1, "offset": { "x": 0, "y": 51 }, "size": { "width": 80, "height": 80 } },
    { "id": "head", "label": "Head", "order": 2, "offset": { "x": 0, "y": -0.5 }, "size": { "width": 80, "height": 80 } }
  ],
  "parts": [
    {
      "id": "head-a",
      "name": "alpha",
      "selectionSlot": "head",
      "aliases": ["head-alpha"],
      "uiGroups": ["head"],
      "layers": [{ "layerSlot": "head", "svgPath": "head-a.svg" }]
    },
    {
      "id": "head-b",
      "name": "bravo",
      "selectionSlot": "head",
      "aliases": ["head-bravo"],
      "uiGroups": ["head"],
      "layers": [{ "layerSlot": "head", "svgPath": "head-b.svg" }]
    },
    {
      "id": "body-one",
      "name": "hoodie",
      "selectionSlot": "body",
      "aliases": ["body-hoodie"],
      "uiGroups": ["body"],
      "layers": [{ "layerSlot": "body", "svgPath": "body-one.svg" }]
    }
  ],
  "aliases": [
    { "alias": "head-alpha", "targetId": "head-a", "status": "active" },
    { "alias": "head-bravo", "targetId": "head-b", "status": "active" },
    { "alias": "body-hoodie", "targetId": "body-one", "status": "active" }
  ]
}
"""
