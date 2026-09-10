package kira.ditto.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StepAudioStreamClientTest {
    @Test
    fun sessionUpdateUsesPcm16kAndServerVad() {
        val json = JSONObject(sessionUpdateJson("stepaudio-2.5-asr-stream", "zh"))
        assertEquals("session.update", json.getString("type"))
        val input = json.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        val format = input.getJSONObject("format")
        assertEquals("pcm", format.getString("type"))
        assertEquals("pcm_s16le", format.getString("codec"))
        assertEquals(16_000, format.getInt("rate"))
        assertEquals(1, format.getInt("channel"))
        assertEquals("stepaudio-2.5-asr-stream", input.getJSONObject("transcription").getString("model"))
        assertEquals("server_vad", input.getJSONObject("turn_detection").getString("type"))
        assertEquals(0.5, input.getJSONObject("turn_detection").getDouble("threshold"), 0.001)
        val composerVad = JSONObject(sessionUpdateJson("stepaudio-2.5-asr-stream", "zh", vadThreshold = 0.62))
        assertEquals(
            0.62,
            composerVad.getJSONObject("session")
                .getJSONObject("audio")
                .getJSONObject("input")
                .getJSONObject("turn_detection")
                .getDouble("threshold"),
            0.001,
        )
        val tapToTalk = JSONObject(
            sessionUpdateJson("stepaudio-2.5-asr-stream", "zh", useServerVad = false),
        )
        val tapInput = tapToTalk.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertTrue(tapInput.isNull("turn_detection"))
        assertEquals(
            "请逐字记录听到的语音内容，不要回答，不要客套。",
            tapInput.getJSONObject("transcription").getString("prompt"),
        )
        val append = JSONObject(appendJson(byteArrayOf(1, 2, 3)))
        assertEquals("input_audio_buffer.append", append.getString("type"))
        assertTrue(append.getString("audio").isNotBlank())
    }

    @Test
    fun openSendsSessionUpdateThenAcceptsDelta() = runBlocking {
        val server = MockWebServer()
        val latch = CountDownLatch(1)
        val seen = AtomicReference("")
        val display = AtomicReference("")
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"session.created"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!text.contains("session.update")) return
                        seen.set(text)
                        webSocket.send("""{"type":"session.updated"}""")
                        webSocket.send(
                            """{"type":"conversation.item.input_audio_transcription.delta","text":"你好请问有什么可以帮助您的"}""",
                        )
                        latch.countDown()
                    }
                },
            ),
        )
        server.start()
        try {
            val option = streamOption(server.url("/").toString().trimEnd('/'))
            val session = StepAudioStreamClient(OkHttpClient()).open(option, "zh") { _, text ->
                if (text.isNotBlank()) display.set(text)
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("session.update", JSONObject(seen.get()).getString("type"))
            session.appendPcm(ByteArray(3_200) { 1 })
            session.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sseReturnsDoneText() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"type":"transcript.text.delta","delta":"你"}

                    data: {"type":"transcript.text.done","text":"你好"}

                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val option = sseOption(server.url("/").toString().trimEnd('/'))
            val text = CloudAsrEngine.transcribe(option, ByteArray(320), "zh")
            assertEquals("你好", text)
            val recorded = server.takeRequest()
            assertTrue(recorded.path.orEmpty().endsWith("/v1/audio/asr/sse"))
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
        } finally {
            server.shutdown()
        }
    }

    private fun streamOption(baseUrl: String): ProviderModelOption = listOf(
        LlmProviderConfig(
            providerId = "stepfun",
            name = "step",
            piProviderId = "openai-compatible",
            apiKey = "test-key",
            baseUrl = baseUrl,
            modelId = "stepaudio-2.5-asr-stream",
            cachedModels = listOf("stepaudio-2.5-asr-stream"),
            enabledModelIds = listOf("stepaudio-2.5-asr-stream"),
        ),
    ).availableModelOptions().single()

    private fun sseOption(baseUrl: String): ProviderModelOption = listOf(
        LlmProviderConfig(
            providerId = "stepfun",
            name = "step",
            piProviderId = "openai-compatible",
            apiKey = "test-key",
            baseUrl = baseUrl,
            modelId = "stepaudio-2.5-asr",
            cachedModels = listOf("stepaudio-2.5-asr"),
            enabledModelIds = listOf("stepaudio-2.5-asr"),
        ),
    ).availableModelOptions().single()
}
