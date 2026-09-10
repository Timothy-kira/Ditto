package kira.ditto.agentmode.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AdbProtocol {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534C5453
    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096
    const val A_STLS_VERSION = 0x01000000
    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
}

internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCrc32: Int,
    val magic: Int,
    val data: ByteArray?,
) {
    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command,
        arg0,
        arg1,
        data?.size ?: 0,
        crc32(data),
        command xor -1,
        data,
    )

    constructor(command: Int, arg0: Int, arg1: Int, data: String) : this(
        command,
        arg0,
        arg1,
        "$data\u0000".toByteArray(),
    )

    fun validateOrThrow() {
        if (command != magic xor -1) {
            error("bad adb magic")
        }
        if (dataLength != 0 && crc32(data) != dataCrc32) {
            error("bad adb checksum")
        }
    }

    fun toByteArray(): ByteArray {
        val payload = data ?: ByteArray(0)
        return ByteBuffer.allocate(HEADER_LENGTH + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(dataCrc32)
            putInt(magic)
            put(payload)
        }.array()
    }

    companion object {
        const val HEADER_LENGTH = 24

        fun crc32(data: ByteArray?): Int {
            if (data == null) return 0
            var res = 0
            for (byte in data) {
                res += byte.toInt() and 0xff
            }
            return res
        }
    }
}
