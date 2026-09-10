package kira.ditto.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrEngineSelectionTest {
    @Test
    fun looksLikeAsrModelMatchesSpeechEngines() {
        assertTrue(looksLikeAsrModel("whisper-1"))
        assertTrue(looksLikeAsrModel("fun-asr-paraformer"))
        assertTrue(looksLikeAsrModel("SenseVoiceSmall"))
        assertTrue(looksLikeAsrModel("gpt-4o-transcribe"))
        assertTrue(looksLikeAsrModel("stepaudio-2.5-asr-stream"))
        assertFalse(looksLikeAsrModel("gpt-4o"))
        assertFalse(looksLikeAsrModel("text-embedding-3-large"))
    }

    @Test
    fun autoPicksHmsThenMlKitThenSystem() {
        val snapshot = AsrCapabilitySnapshot(
            hms = AsrAvailability.Available,
            mlKit = AsrAvailability.Available,
            system = AsrAvailability.Available,
        )
        assertEquals(
            AsrEngineKind.Hms,
            resolveAsrEngineKind(AsrDeviceModelKeys.Auto, snapshot, catalogHasModel = false),
        )
        assertEquals(
            AsrEngineKind.MlKit,
            resolveAsrEngineKind(
                AsrDeviceModelKeys.Auto,
                snapshot.copy(hms = AsrAvailability.Unavailable),
                catalogHasModel = false,
            ),
        )
        assertEquals(
            AsrEngineKind.System,
            resolveAsrEngineKind(
                AsrDeviceModelKeys.Auto,
                AsrCapabilitySnapshot(system = AsrAvailability.Available),
                catalogHasModel = false,
            ),
        )
        assertNull(
            resolveAsrEngineKind(AsrDeviceModelKeys.Auto, AsrCapabilitySnapshot(), catalogHasModel = false),
        )
    }

    @Test
    fun unselectedAndUnknownCatalogAreNotReady() {
        assertNull(resolveAsrEngineKind("", AsrCapabilitySnapshot(), catalogHasModel = false))
        assertNull(resolveAsrEngineKind("provider:missing", AsrCapabilitySnapshot(), catalogHasModel = false))
        assertEquals(
            AsrEngineKind.Cloud,
            resolveAsrEngineKind("provider:whisper", AsrCapabilitySnapshot(), catalogHasModel = true),
        )
    }

    @Test
    fun chunkAssemblerFlushesAroundFiftySeconds() {
        val fiftySeconds = AsrChunkAssembler.SampleRateHz * AsrChunkAssembler.BytesPerSample * 50
        assertFalse(AsrChunkAssembler.shouldFlush(fiftySeconds - 2))
        assertTrue(AsrChunkAssembler.shouldFlush(fiftySeconds))
        assertEquals("hello world", AsrChunkAssembler.joinTranscripts(listOf(" hello", "", "world ")))
        assertEquals("杭州西湖很大而且美", AsrChunkAssembler.joinTranscripts(listOf("杭州西湖很大", "西湖很大而且美")))
        assertEquals("去西湖", AsrChunkAssembler.joinTranscripts(listOf("去西湖", "去西湖")))
        assertEquals("hello world peace", AsrChunkAssembler.joinTranscripts(listOf("hello world", "world peace")))
        assertEquals(1_000L, AsrChunkAssembler.pcmDurationMs(32_000))
    }

    @Test
    fun normalizeAsrKeepsDeviceKeys() {
        assertEquals(AsrDeviceModelKeys.Auto, normalizeAsrModelKey("device:auto", emptyList()))
        assertEquals(AsrDeviceModelKeys.Auto, normalizeAsrModelKey("device:unknown", emptyList()))
        assertEquals("", normalizeAsrModelKey("provider:whisper", emptyList()))
    }

    @Test
    fun stepAudioStreamUrlUsesRealtimePath() {
        assertEquals(
            "wss://api.stepfun.com/v1/realtime/asr/stream",
            stepAudioStreamUrl("https://api.stepfun.com"),
        )
        assertEquals(
            "wss://api.stepfun.com/v1/realtime/asr/stream",
            stepAudioStreamUrl("https://api.stepfun.com/v1"),
        )
        assertEquals(
            "wss://api.stepfun.com/step_plan/v1/realtime/asr/stream",
            stepAudioStreamUrl("https://api.stepfun.com/step_plan"),
        )
        assertEquals(
            "wss://api.stepfun.com/step_plan/v1/realtime/asr/stream",
            stepAudioStreamUrl("https://api.stepfun.com/step_plan/v1"),
        )
        assertEquals(
            "wss://api.stepfun.ai/v1/realtime/asr/stream",
            stepAudioStreamUrl("https://api.stepfun.ai"),
        )
        assertEquals(
            "https://api.stepfun.com/v1/audio/asr/sse",
            stepAudioSseUrl("https://api.stepfun.com/v1"),
        )
    }

    @Test
    fun stepAudioStreamPrefersPublicRealtimeOverPlan() {
        assertEquals(
            listOf(
                "wss://api.stepfun.com/v1/realtime/asr/stream",
                "wss://api.stepfun.com/step_plan/v1/realtime/asr/stream",
            ),
            stepAudioStreamCandidateUrls("https://api.stepfun.com"),
        )
        assertEquals(
            listOf(
                "https://api.stepfun.com/step_plan/v1/audio/asr/sse",
                "https://api.stepfun.com/v1/audio/asr/sse",
            ),
            stepAudioSseCandidateUrls("https://api.stepfun.com/v1"),
        )
        assertEquals(
            listOf(
                "wss://api.stepfun.com/v1/realtime/asr/stream",
                "wss://api.stepfun.com/step_plan/v1/realtime/asr/stream",
            ),
            stepAudioStreamCandidateUrls("https://api.stepfun.com/step_plan"),
        )
        assertFalse(looksLikeStepFunHost("http://127.0.0.1:8080"))
        assertEquals(
            listOf(
                "ws://127.0.0.1:8080/v1/realtime/asr/stream",
                "ws://127.0.0.1:8080/step_plan/v1/realtime/asr/stream",
            ),
            stepAudioStreamCandidateUrls("http://127.0.0.1:8080"),
        )
    }

    @Test
    fun stepAudioSpeechUrlsCoverPlanAndPublic() {
        assertEquals(
            "https://api.stepfun.com/step_plan/v1/audio/speech",
            stepAudioSpeechUrl("https://api.stepfun.com/step_plan/v1"),
        )
        assertEquals(
            listOf(
                "https://api.stepfun.com/step_plan/v1/audio/speech",
                "https://api.stepfun.com/v1/audio/speech",
            ),
            stepAudioSpeechCandidateUrls("https://api.stepfun.com/v1"),
        )
        assertEquals("stepaudio-2.5-tts", resolveStepAudioTtsModelId("step-audio-2.5"))
        assertEquals("stepaudio-2.5-tts", resolveStepAudioTtsModelId("stepaudio-2.5-tts"))
    }

    @Test
    fun asrHandshakeErrorsMapPaymentRequired() {
        assertEquals(
            402,
            asrHttpStatusFromMessage("Expected HTTP 101 response but was '402 Payment Required'"),
        )
        assertTrue(isAsrBillingFailure(402, null))
        assertTrue(isAsrBillingFailure(null, "Expected HTTP 101 response but was '402 Payment Required'"))
        assertEquals(
            "阶跃语音识别暂时连不上套餐接口",
            websocketAsrErrorMessage("Expected HTTP 101 response but was '402 Payment Required'", 402),
        )
        assertEquals("连接阶跃语音识别失败", websocketAsrErrorMessage("Expected HTTP 101 response but was '500'", 500))
        assertTrue(shouldRetryAsrEndpoint(402))
        assertFalse(shouldRetryAsrEndpoint(401))
        assertEquals("zh-CN", asrSpeechLanguageTag("zh"))
        assertEquals("zh-CN", asrSpeechLanguageTag(null))
        assertEquals("en-US", asrSpeechLanguageTag("en"))
    }

    @Test
    fun stepAudioModelRouting() {
        assertTrue(isStepAudioStreamModel("stepaudio-2.5-asr-stream"))
        assertTrue(isStepAudioStreamModel("step-asr-1.1-stream"))
        assertFalse(isStepAudioStreamModel("stepaudio-2.5-asr"))
        assertTrue(isStepAudioSseModel("stepaudio-2.5-asr"))
        assertFalse(isStepAudioSseModel("stepaudio-2.5-asr-stream"))
        assertFalse(isStepAudioSseModel("whisper-1"))
        assertEquals("stepaudio-2.5-asr", stepAudioSseModelId("stepaudio-2.5-asr-stream"))
    }

    @Test
    fun step25DeltaReplacesDisplayText() {
        var display = ""
        display = applyStepAudioText(
            display,
            parseStepAudioStreamEvent(
                """{"type":"conversation.item.input_audio_transcription.delta","text":"你好请问有什么可以帮助您的","stash":"退款"}""",
            ),
        )
        assertEquals("你好请问有什么可以帮助您的", display)
        display = applyStepAudioText(
            display,
            parseStepAudioStreamEvent(
                """{"type":"conversation.item.input_audio_transcription.delta","text":"你好请问有什么可以帮助您的退款"}""",
            ),
        )
        assertEquals("你好请问有什么可以帮助您的退款", display)
        val completed = parseStepAudioStreamEvent(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"你好，世界"}""",
        )
        assertEquals("你好，世界", completed.completedTranscript)
        val error = parseStepAudioStreamEvent(
            """{"type":"error","error":{"message":"Plan 下双向 ASR 不可用"}}""",
        )
        assertEquals("Plan 下双向 ASR 不可用", error.errorMessage)
    }

    @Test
    fun accumulatorJoinsCompletedTurns() {
        val acc = StepAudioTranscriptAccumulator()
        acc.apply(
            parseStepAudioStreamEvent(
                """{"type":"conversation.item.input_audio_transcription.delta","text":"第一句"}""",
            ),
        )
        acc.apply(
            parseStepAudioStreamEvent(
                """{"type":"conversation.item.input_audio_transcription.completed","transcript":"第一句"}""",
            ),
        )
        acc.apply(
            parseStepAudioStreamEvent(
                """{"type":"conversation.item.input_audio_transcription.delta","text":"第二句"}""",
            ),
        )
        assertEquals("第一句 第二句", acc.display())
    }
}
