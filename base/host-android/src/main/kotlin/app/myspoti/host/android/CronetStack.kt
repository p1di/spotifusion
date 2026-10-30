package app.myspoti.host.android

import android.content.Context
import okhttp3.Headers
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Cronet from Google Play services: the Chromium network stack for a refused relayed command
 * on Android 13 and older, where the platform has no [PlatformStack] (see RelayRoutes).
 *
 * The provider Play services installs adds a few hundred KB to the APK. The embedded Cronet
 * (org.chromium.net:cronet-embedded) needs no Play services but ships about 10 MB of native code
 * for the two ABIs this app is built for; a phone without Play services keeps OkHttp and the
 * other address family only.
 *
 * The request carries the relay's headers less Accept-Encoding: Chromium writes its own, as
 * Chrome does, and decodes what it asked for. No cookie: the real Web Player sends none to
 * spclient.
 */
internal object CronetStack {
    @Volatile private var engine: CronetEngine? = null
    @Volatile private var started = false

    /** What the stack is: "ready (<provider> <version>)", "unavailable: <why>", "starting"... */
    @Volatile var status: String = "not started"
        private set

    val available: Boolean get() = engine != null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-cronet").apply { isDaemon = true } }

    /** Installs the Play services provider in the background; [available] once it is there. */
    fun prepare(context: Context) {
        if (started || android.os.Build.VERSION.SDK_INT >= 34) return
        started = true
        status = "starting"
        val app = context.applicationContext
        runCatching {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(app).addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    status = "unavailable: ${task.exception?.javaClass?.simpleName ?: "no Google Play services"}"
                    Logbook.log("relay: Cronet $status")
                    return@addOnCompleteListener
                }
                runCatching {
                    val provider = CronetProvider.getAllProviders(app)
                        .firstOrNull { it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK }
                        ?: error("no native provider")
                    engine = provider.createBuilder().enableHttp2(true).enableQuic(true).enableBrotli(true).build()
                    status = "ready (${provider.name} ${provider.version})"
                }.onFailure { status = "unavailable: ${it.message}" }
                Logbook.log("relay: Cronet $status")
            }
        }.onFailure {
            status = "unavailable: ${it.message}"
            Logbook.log("relay: Cronet $status")
        }
    }

    fun fetch(
        url: String,
        method: String,
        headers: Headers,
        sent: String?,
        done: (code: Int, headers: List<Pair<String, String>>, text: String, protocol: String, error: String?) -> Unit,
    ) {
        val cronet = engine ?: return done(0, emptyList(), "", "", "Cronet $status")
        val out = ByteArrayOutputStream()
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) = request.followRedirect()

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) = request.read(ByteBuffer.allocateDirect(32 * 1024))

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
                buffer.clear()
                request.read(buffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) =
                done(info.httpStatusCode, info.allHeadersAsList.map { it.key to it.value }, out.toString(Charsets.UTF_8.name()), info.negotiatedProtocol, null)

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) =
                done(0, emptyList(), "", "", error.message ?: "failed")

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = done(0, emptyList(), "", "", "canceled")
        }
        runCatching {
            val builder = cronet.newUrlRequestBuilder(url, callback, executor).setHttpMethod(method)
            headers.forEach { (name, value) -> if (!name.equals("Accept-Encoding", true)) builder.addHeader(name, value) }
            if (method != "GET" && method != "HEAD") {
                // Cronet refuses a body without a type: the one a browser's fetch gives a string body.
                if (headers["Content-Type"] == null) builder.addHeader("Content-Type", "text/plain;charset=UTF-8")
                builder.setUploadDataProvider(UploadDataProviders.create((sent ?: "").toByteArray()), executor)
            }
            builder.build().start()
        }.onFailure { done(0, emptyList(), "", "", it.message ?: "not sent") }
    }
}
