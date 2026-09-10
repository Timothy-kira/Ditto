package kira.ditto.platform

import kira.ditto.runtime.NativePickedFileListener
import kira.ditto.runtime.NativePickedFilesListener
import kira.ditto.runtime.NativePickedDirectoryListener
import kira.ditto.runtime.NativeFileExportListener
import kira.ditto.runtime.NativeAuthenticationSessionListener
import kira.ditto.runtime.NativeRuntimeHost
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class IosPlatformServices(
    internal val host: NativeRuntimeHost,
) : PlatformServices {
    override suspend fun pickFile(imagesOnly: Boolean): PlatformPickedFile? =
        suspendCancellableCoroutine { continuation ->
            host.pickFile(imagesOnly, object : NativePickedFileListener {
                override fun onSelected(name: String, mimeType: String, bytes: ByteArray) {
                    if (continuation.isActive) {
                        continuation.resume(PlatformPickedFile(name, mimeType, bytes))
                    }
                }

                override fun onCancelled() {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onError(message: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                }
            })
        }

    override suspend fun pickFiles(imagesOnly: Boolean): List<PlatformPickedFile> =
        suspendCancellableCoroutine { continuation ->
            val selected = mutableListOf<PlatformPickedFile>()
            host.pickFiles(imagesOnly, object : NativePickedFilesListener {
                override fun onSelected(name: String, mimeType: String, bytes: ByteArray) {
                    selected += PlatformPickedFile(name, mimeType, bytes)
                }

                override fun onCompleted() {
                    if (continuation.isActive) continuation.resume(selected.toList())
                }

                override fun onCancelled() {
                    if (continuation.isActive) continuation.resume(emptyList())
                }

                override fun onError(message: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                }
            })
        }

    override suspend fun pickDirectory(): PlatformPickedDirectory? =
        suspendCancellableCoroutine { continuation ->
            val selected = mutableListOf<PlatformPickedDirectoryFile>()
            host.pickDirectory(object : NativePickedDirectoryListener {
                override fun onSelected(relativePath: String, mimeType: String, bytes: ByteArray) {
                    selected += PlatformPickedDirectoryFile(relativePath, mimeType, bytes)
                }

                override fun onCompleted(name: String) {
                    if (continuation.isActive) {
                        continuation.resume(PlatformPickedDirectory(name, selected.toList()))
                    }
                }

                override fun onCancelled() {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onError(message: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                }
            })
        }

    override suspend fun exportFile(name: String, mimeType: String, bytes: ByteArray): Boolean? =
        suspendCancellableCoroutine { continuation ->
            host.exportFile(name, mimeType, bytes, object : NativeFileExportListener {
                override fun onCompleted() {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled() {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onError(message: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                }
            })
        }

    override fun openUrl(url: String): Boolean = host.openUrl(url)
    override fun openAuthenticationUrl(
        url: String,
        onCallback: (String) -> Unit,
        onCancelled: () -> Unit,
    ): Boolean = host.openAuthenticationUrl(url, object : NativeAuthenticationSessionListener {
        override fun onCallback(url: String) = onCallback(url)
        override fun onCancelled() = onCancelled()
        override fun onError(message: String) = onCancelled()
    })
    override fun copyText(text: String): Boolean = host.copyText(text)
    override fun shareText(title: String, text: String): Boolean = host.shareText(title, text)
    override fun shareFile(name: String, mimeType: String, bytes: ByteArray): Boolean =
        host.shareFile(name, mimeType, bytes)
    override fun previewFile(name: String, mimeType: String, bytes: ByteArray): Boolean =
        host.previewFile(name, mimeType, bytes)
    override fun openAlpineFileManager(): Boolean = host.openAlpineFileManager()
    override fun terminateApplication(): Boolean = host.terminateApplication()
}
