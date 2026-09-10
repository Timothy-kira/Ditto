package kira.ditto.data

import java.io.ByteArrayOutputStream

internal object AsrWav {
    fun pcm16leMonoToWav(pcm: ByteArray, sampleRateHz: Int = AsrChunkAssembler.SampleRateHz): ByteArray {
        val byteRate = sampleRateHz * 2
        val header = ByteArrayOutputStream(44)
        fun writeAscii(value: String) = header.write(value.toByteArray(Charsets.US_ASCII))
        fun writeInt(value: Int) {
            header.write(value and 0xFF)
            header.write(value shr 8 and 0xFF)
            header.write(value shr 16 and 0xFF)
            header.write(value shr 24 and 0xFF)
        }
        fun writeShort(value: Int) {
            header.write(value and 0xFF)
            header.write(value shr 8 and 0xFF)
        }
        writeAscii("RIFF")
        writeInt(36 + pcm.size)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(sampleRateHz)
        writeInt(byteRate)
        writeShort(2)
        writeShort(16)
        writeAscii("data")
        writeInt(pcm.size)
        return header.toByteArray() + pcm
    }

    fun pcm16leMonoLastSeconds(
        pcm: ByteArray,
        seconds: Int,
        sampleRateHz: Int = AsrChunkAssembler.SampleRateHz,
    ): ByteArray {
        val maxBytes = (seconds.coerceAtLeast(1) * sampleRateHz * AsrChunkAssembler.BytesPerSample)
        if (pcm.size <= maxBytes) return pcm
        var start = pcm.size - maxBytes
        if (start % 2 != 0) start -= 1
        return pcm.copyOfRange(start.coerceAtLeast(0), pcm.size)
    }

    fun wavPcmPayload(wav: ByteArray): ByteArray {
        if (wav.size > 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF") {
            return wav.copyOfRange(44, wav.size)
        }
        return wav
    }

    fun wavLastSeconds(wav: ByteArray, seconds: Int): ByteArray =
        pcm16leMonoToWav(pcm16leMonoLastSeconds(wavPcmPayload(wav), seconds))
}
