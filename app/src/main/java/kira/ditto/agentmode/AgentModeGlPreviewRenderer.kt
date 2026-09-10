package kira.ditto.agentmode

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Virtual-display producer plus GLES compositor.
 *
 * The virtual display always renders into a GL-owned
 * [SurfaceTexture] (`GL_TEXTURE_EXTERNAL_OES`). A dedicated GL thread
 * samples that texture and draws it to a [SurfaceView] window surface.
 * Collapsing the preview only destroys the window surface; the producer
 * and virtual display stay alive. OpenGL ES is the right API here:
 * `SurfaceTexture` is an EXTERNAL_OES consumer, not a Vulkan image.
 */
class AgentModeGlPreviewRenderer {
    private val thread = HandlerThread("aether-agent-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: Surface? = null

    private var oesTextureId = 0
    private var program = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTexMatrix = -1
    private var vertexBuffer: FloatBuffer? = null

    private var producerTexture: SurfaceTexture? = null
    private var producerSurface: Surface? = null
    private var producerWidth = 0
    private var producerHeight = 0
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false

    fun ensureProducerSurface(width: Int, height: Int): Surface {
        var result: Surface? = null
        var error: Throwable? = null
        runOnGlThreadBlocking {
            try {
                result = ensureProducerLocked(width, height)
            } catch (throwable: Throwable) {
                error = throwable
            }
        }
        error?.let { throw it }
        return result ?: error("GL producer surface was not created.")
    }

    fun producerSurfaceOrNull(): Surface? = producerSurface?.takeIf { it.isValid }

    fun attachOutput(surface: Surface) {
        handler.post {
            runCatching { attachWindowLocked(surface) }
                .onFailure { Log.w(LogTag, "hop=gl_attach_failed ${it.message}") }
        }
    }

    fun detachOutput(surface: Surface) {
        runOnGlThreadBlocking {
            if (windowSurface === surface || windowSurface == null) {
                releaseWindowLocked()
            }
        }
    }

    fun releaseProducer() {
        runOnGlThreadBlocking {
            releaseProducerLocked()
        }
    }

    fun release() {
        runOnGlThreadBlocking {
            releaseProducerLocked()
            releaseEglLocked()
        }
        thread.quitSafely()
    }

    private fun ensureProducerLocked(width: Int, height: Int): Surface {
        ensureEglLocked()
        makePbufferCurrentLocked()
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val existing = producerSurface
        if (existing != null && existing.isValid && producerTexture != null) {
            if (producerWidth != w || producerHeight != h) {
                producerTexture?.setDefaultBufferSize(w, h)
                producerWidth = w
                producerHeight = h
            }
            return existing
        }
        releaseProducerLocked()
        if (oesTextureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            oesTextureId = ids[0]
            check(oesTextureId != 0) { "glGenTextures failed for EXTERNAL_OES." }
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        val texture = SurfaceTexture(oesTextureId)
        texture.setDefaultBufferSize(w, h)
        texture.setOnFrameAvailableListener(
            { handler.post { onFrameAvailableLocked() } },
            handler,
        )
        val surface = Surface(texture)
        producerTexture = texture
        producerSurface = surface
        producerWidth = w
        producerHeight = h
        Log.i(LogTag, "hop=gl_producer_ready ${w}x$h")
        return surface
    }

    private fun onFrameAvailableLocked() {
        frameAvailable = true
        drawLocked()
    }

    private fun attachWindowLocked(surface: Surface) {
        if (!surface.isValid) return
        ensureEglLocked()
        if (windowSurface === surface && windowEglSurface != EGL14.EGL_NO_SURFACE) {
            return
        }
        releaseWindowLocked()
        val config = eglConfig ?: return
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.w(LogTag, "hop=gl_window_create_failed err=${EGL14.eglGetError()}")
            return
        }
        windowEglSurface = eglSurface
        windowSurface = surface
        Log.i(LogTag, "hop=gl_window_attached")
        drawLocked()
    }

    private fun drawLocked() {
        val texture = producerTexture ?: return
        val output = windowEglSurface
        if (output == EGL14.EGL_NO_SURFACE) {
            if (frameAvailable) {
                makePbufferCurrentLocked()
                runCatching { texture.updateTexImage() }
                frameAvailable = false
            }
            return
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, output, output, eglContext)) {
            Log.w(LogTag, "hop=gl_make_current_failed err=${EGL14.eglGetError()}")
            return
        }
        if (frameAvailable) {
            runCatching { texture.updateTexImage() }
            frameAvailable = false
        }
        texture.getTransformMatrix(texMatrix)
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, output, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(eglDisplay, output, EGL14.EGL_HEIGHT, height, 0)
        GLES20.glViewport(0, 0, width[0].coerceAtLeast(1), height[0].coerceAtLeast(1))
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        val vertices = vertexBuffer ?: return
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        EGL14.eglSwapBuffers(eglDisplay, output)
    }

    private fun ensureEglLocked() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglContext != EGL14.EGL_NO_CONTEXT &&
            program != 0
        ) {
            return
        }
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed." }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            "eglInitialize failed: ${EGL14.eglGetError()}"
        }
        val config = chooseConfigLocked()
        eglConfig = config
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
            "eglCreateContext failed: ${EGL14.eglGetError()}"
        }
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(pbuffer != null && pbuffer != EGL14.EGL_NO_SURFACE) {
            "eglCreatePbufferSurface failed: ${EGL14.eglGetError()}"
        }
        makePbufferCurrentLocked()
        program = buildProgramLocked()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        vertexBuffer = quadBuffer()
        Log.i(LogTag, "hop=gl_egl_ready")
    }

    private fun chooseConfigLocked(): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0)
        val config = configs[0]
        check(ok && count[0] > 0 && config != null) {
            "eglChooseConfig failed: ${EGL14.eglGetError()}"
        }
        return config
    }

    private fun buildProgramLocked(): Int {
        val vertex = compileShaderLocked(GLES20.GL_VERTEX_SHADER, VertexShader)
        val fragment = compileShaderLocked(GLES20.GL_FRAGMENT_SHADER, FragmentShader)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertex)
        GLES20.glAttachShader(prog, fragment)
        GLES20.glLinkProgram(prog)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            error("GL program link failed: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return prog
    }

    private fun compileShaderLocked(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GL shader compile failed: $log")
        }
        return shader
    }

    private fun makePbufferCurrentLocked() {
        if (pbuffer == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) {
            error("eglMakeCurrent pbuffer failed: ${EGL14.eglGetError()}")
        }
    }

    private fun releaseWindowLocked() {
        if (windowEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(eglDisplay, windowEglSurface)
            windowEglSurface = EGL14.EGL_NO_SURFACE
        }
        windowSurface = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && pbuffer != EGL14.EGL_NO_SURFACE) {
            runCatching { makePbufferCurrentLocked() }
        }
    }

    private fun releaseProducerLocked() {
        releaseWindowLocked()
        producerSurface?.let { runCatching { it.release() } }
        producerSurface = null
        producerTexture?.let { runCatching { it.release() } }
        producerTexture = null
        producerWidth = 0
        producerHeight = 0
        frameAvailable = false
        if (oesTextureId != 0 && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                makePbufferCurrentLocked()
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            }
            oesTextureId = 0
        }
    }

    private fun releaseEglLocked() {
        if (program != 0) {
            runCatching { GLES20.glDeleteProgram(program) }
            program = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbuffer)
                pbuffer = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        eglConfig = null
        vertexBuffer = null
    }

    private fun runOnGlThreadBlocking(block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (!posted) {
            error("GL renderer thread is not running.")
        }
        if (!done.await(3, TimeUnit.SECONDS)) {
            error("GL renderer timed out.")
        }
    }

    companion object {
        private const val LogTag = "AetherAgentMode"

        private const val VertexShader = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private fun quadBuffer(): FloatBuffer {
            // x, y, u, v — full-screen strip. Texture transform comes from SurfaceTexture.
            val data = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            )
            return ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
                .also { it.position(0) }
        }
    }
}
