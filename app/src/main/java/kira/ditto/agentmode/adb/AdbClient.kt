package kira.ditto.agentmode.adb

import android.os.Build
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

internal class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
) : Closeable {
    private lateinit var socket: Socket
    private lateinit var plainInput: DataInputStream
    private lateinit var plainOutput: DataOutputStream
    private var tlsSocket: SSLSocket? = null
    private var tlsInput: DataInputStream? = null
    private var tlsOutput: DataOutputStream? = null
    private var useTls = false

    private val input: DataInputStream
        get() = if (useTls) tlsInput!! else plainInput
    private val output: DataOutputStream
        get() = if (useTls) tlsOutput!! else plainOutput

    fun connect() {
        socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = 8_000
        socket.connect(InetSocketAddress(host, port), 2_500)
        plainInput = DataInputStream(socket.getInputStream())
        plainOutput = DataOutputStream(socket.getOutputStream())
        write(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::")
        var message = read()
        if (message.command == AdbProtocol.A_STLS) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                error("ADB TLS is not supported before Android 10.")
            }
            write(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null as ByteArray?)
            val ssl = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            ssl.startHandshake()
            tlsSocket = ssl
            tlsInput = DataInputStream(ssl.inputStream)
            tlsOutput = DataOutputStream(ssl.outputStream)
            useTls = true
            message = read()
        }
        if (message.command == AdbProtocol.A_AUTH) {
            write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
            message = read()
            if (message.command != AdbProtocol.A_CNXN) {
                write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }
        if (message.command != AdbProtocol.A_CNXN) {
            error("ADB connect failed with command ${message.command}")
        }
    }

    fun shellCommand(command: String): String {
        val localId = 1
        write(AdbProtocol.A_OPEN, localId, 0, "shell:$command")
        var message = read()
        val outputText = StringBuilder()
        when (message.command) {
            AdbProtocol.A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    when (message.command) {
                        AdbProtocol.A_WRTE -> {
                            if (message.dataLength > 0) {
                                outputText.append(String(message.data ?: ByteArray(0)))
                            }
                            write(AdbProtocol.A_OKAY, localId, remoteId, null as ByteArray?)
                        }
                        AdbProtocol.A_CLSE -> {
                            write(AdbProtocol.A_CLSE, localId, remoteId, null as ByteArray?)
                            break
                        }
                        else -> error("Unexpected ADB shell command ${message.command}")
                    }
                }
            }
            AdbProtocol.A_CLSE -> {
                write(AdbProtocol.A_CLSE, localId, message.arg0, null as ByteArray?)
            }
            else -> error("Unexpected ADB open command ${message.command}")
        }
        return outputText.toString()
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        output.write(AdbMessage(command, arg0, arg1, data).toByteArray())
        output.flush()
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) {
        write(command, arg0, arg1, "$data\u0000".toByteArray())
    }

    private fun read(): AdbMessage {
        val header = ByteArray(AdbMessage.HEADER_LENGTH)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength > 0) {
            ByteArray(dataLength).also { input.readFully(it) }
        } else {
            null
        }
        return AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data).also {
            it.validateOrThrow()
        }
    }

    override fun close() {
        runCatching { plainInput.close() }
        runCatching { plainOutput.close() }
        runCatching { socket.close() }
        runCatching { tlsInput?.close() }
        runCatching { tlsOutput?.close() }
        runCatching { tlsSocket?.close() }
    }
}
