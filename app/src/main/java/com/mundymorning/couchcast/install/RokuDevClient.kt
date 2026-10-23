package com.mundymorning.couchcast.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

sealed class InstallOutcome {
    /** Roku said it installed the package. Not trusted until verified through ECP. */
    data class Accepted(val identical: Boolean) : InstallOutcome()
    object BadPassword : InstallOutcome()
    data class CompileFailed(val detail: String) : InstallOutcome()
    data class Rejected(val detail: String) : InstallOutcome()
    object InstallerClosed : InstallOutcome()
    data class Unreachable(val detail: String) : InstallOutcome()
    /** The upload was sent but the Roku dropped the connection before replying. It may still have installed. */
    data class ResponseLost(val detail: String) : InstallOutcome()
    data class Unknown(val detail: String) : InstallOutcome()
}

enum class InstallerProbe { OPEN, CLOSED }

/** Talks to the Roku Development Application Installer (HTTP, port 80, Digest user "rokudev"). */
class RokuDevClient(
    private val installerPort: Int = 80,
    private val ecpPort: Int = 8060,
) {
    // The Roku's installer hangs up idle keep-alive sockets. Reusing one gives "unexpected end of stream",
    // so never pool connections here and ask the server to close after each reply.
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(false).followRedirects(false).build()

    private val uploadHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(false).followRedirects(false).build()

    private fun installerUrl(ip: String, path: String) = "http://$ip:$installerPort$path"

    /** Does anything answer on the installer port? A closed port means Developer Mode / installer is off. */
    suspend fun probeInstaller(ip: String): InstallerProbe = probeInstallerDetail(ip).first

    /** Same probe, plus a short human-readable reason so a failure can be shown instead of hidden. */
    suspend fun probeInstallerDetail(ip: String): Pair<InstallerProbe, String> = withContext(Dispatchers.IO) {
        try {
            probeHttp.newCall(Request.Builder().url(installerUrl(ip, "/")).header("Connection", "close").get().build()).execute()
                .use { InstallerProbe.OPEN to "answered HTTP ${it.code}" }
        } catch (e: IOException) {
            InstallerProbe.CLOSED to when (e) {
                is ConnectException -> "port $installerPort refused the connection"
                is SocketTimeoutException -> "port $installerPort did not answer in time"
                else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(80)
            }
        }
    }

    suspend fun install(ip: String, user: String, password: String, zip: ByteArray): InstallOutcome =
        withContext(Dispatchers.IO) {
            var uploadStarted = false
            try {
                // 1. Ask for a Digest challenge.
                val challenge = fetchChallenge(ip)
                    ?: return@withContext InstallOutcome.Unknown("The Roku did not ask for a password the way a Roku installer should.")

                // 2. Upload with the Digest response. The body is in memory, so it is safe to build once.
                val body = multipartBody(zip)
                val auth = DigestAuth.authorization(user, password, "POST", "/plugin_install", challenge)
                val req = Request.Builder().url(installerUrl(ip, "/plugin_install"))
                    .header("Authorization", auth).header("Connection", "close").post(body).build()
                uploadStarted = true
                uploadHttp.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    parseResponse(r.code, text)
                }
            } catch (_: ConnectException) {
                InstallOutcome.InstallerClosed
            } catch (e: IOException) {
                if (uploadStarted) InstallOutcome.ResponseLost(e.message ?: e.javaClass.simpleName)
                else if (e is SocketTimeoutException) InstallOutcome.Unreachable("The Roku stopped answering.")
                else InstallOutcome.Unreachable(e.message ?: "Network error")
            }
        }

    /** Asks for a Digest challenge. One quick retry on a dropped socket (the Roku is single-threaded and flaky here). */
    private fun fetchChallenge(ip: String): DigestAuth.Challenge? {
        var last: IOException? = null
        repeat(3) {
            try {
                val req = Request.Builder().url(installerUrl(ip, "/plugin_install")).header("Connection", "close").get().build()
                return probeHttp.newCall(req).execute().use { DigestAuth.parseChallenge(it.header("WWW-Authenticate")) }
            } catch (e: ConnectException) {
                throw e
            } catch (e: IOException) {
                last = e
                Thread.sleep(400)
            }
        }
        throw last!!
    }

    suspend fun activeAppId(ip: String): String? = withContext(Dispatchers.IO) {
        try {
            probeHttp.newCall(Request.Builder().url("http://$ip:$ecpPort/query/active-app").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                Regex("<app\\s+[^>]*id=\"([^\"]+)\"").find(r.body?.string().orEmpty())?.groupValues?.get(1)
            }
        } catch (_: IOException) {
            null
        }
    }

    suspend fun launchDevApp(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("http://$ip:$ecpPort/launch/${InstallPlanner.DEV_APP_ID}")
                .post(ByteArray(0).toRequestBody(null)).build()
            probeHttp.newCall(req).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        /**
         * Roku's installer uses a very strict hand-written form parser. OkHttp's MultipartBody adds a
         * per-part Content-Length header, which makes it report "mysubmit Field Not Found". So the form
         * is written byte-for-byte like `curl -F mysubmit=Replace -F archive=@x.zip`: only
         * Content-Disposition (plus Content-Type on the file part), nothing else.
         */
        fun multipartBody(zip: ByteArray, boundary: String = "----CouchcastForm" + System.nanoTime().toString(16)): okhttp3.RequestBody {
            val out = java.io.ByteArrayOutputStream()
            fun w(t: String) = out.write(t.toByteArray(Charsets.ISO_8859_1))
            w("--$boundary\r\nContent-Disposition: form-data; name=\"mysubmit\"\r\n\r\nReplace\r\n")
            w("--$boundary\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"couchcast-receiver.zip\"\r\n")
            w("Content-Type: application/zip\r\n\r\n")
            out.write(zip)
            w("\r\n--$boundary--\r\n")
            return out.toByteArray().toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
        }

        /** Classifies the installer's HTML reply. Pure, so it is unit tested. */
        fun parseResponse(code: Int, html: String): InstallOutcome {
            if (code == 401) return InstallOutcome.BadPassword
            val messages = Regex("<font[^>]*color=\"red\"[^>]*>(.*?)</font>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
                .filter { it.isNotEmpty() }.toList()
            val joined = messages.joinToString(" ")
            return when {
                joined.contains("Compilation Failed", true) -> InstallOutcome.CompileFailed(joined)
                joined.contains("Identical to previous version", true) -> InstallOutcome.Accepted(identical = true)
                joined.contains("Install Success", true) -> InstallOutcome.Accepted(identical = false)
                joined.contains("Failure", true) -> InstallOutcome.Rejected(joined)
                code in 200..299 -> InstallOutcome.Unknown("The Roku answered but did not say whether the install worked.")
                else -> InstallOutcome.Rejected("The Roku installer answered with HTTP $code.")
            }
        }
    }
}
