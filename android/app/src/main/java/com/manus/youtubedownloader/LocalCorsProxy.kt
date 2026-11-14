package com.manus.youtubedownloader

import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only CORS bridge for explicitly allowlisted WebView origins.
 * Security: never bind beyond 127.0.0.1; every URL and redirect is restricted to HTTP(S),
 * ports 80/443, and DNS results that are globally routable. Credentials and caller Cookie,
 * Authorization and Host are never forwarded. This is not an authentication bypass/proxy.
 */
class LocalCorsProxy : NanoHTTPD("127.0.0.1", 0) {
    private val stopped = AtomicBoolean(false)
    private val calls = java.util.Collections.synchronizedSet(mutableSetOf<Call>())
    private val client = OkHttpClient.Builder()
        .dns(PublicDns())
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // Media bodies are streamed and may take longer than a minute on mobile data.
        // Keep the per-read idle limit, but do not impose a total call deadline.
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    val localPort: Int get() = listeningPort
    val localUrl: String get() = "http://127.0.0.1:$localPort/"

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        val origin = session.headers["origin"]
        if (origin !in ALLOWED_ORIGINS) return error(NanoHTTPD.Response.Status.FORBIDDEN, "Origin not allowed", origin)
        if (session.uri != "/") return error(NanoHTTPD.Response.Status.NOT_FOUND, "Not found", origin)
        if (session.method !in setOf(Method.GET, Method.HEAD, Method.POST, Method.OPTIONS))
            return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET, HEAD, POST and OPTIONS are supported", origin)

        if (session.method == Method.OPTIONS) {
            val requested = session.headers["access-control-request-headers"]
            val requestedNames = requested?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
            if (requestedNames.any { !isAllowedRequestHeader(it) })
                return error(NanoHTTPD.Response.Status.FORBIDDEN, "Requested header not allowed", origin)
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "").apply {
                addCorsHeaders(this, origin)
                addHeader("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
                addHeader("Access-Control-Allow-Headers", requestedNames.joinToString(", ").ifBlank { "Accept, Range" })
                addHeader("Access-Control-Max-Age", "600")
            }
        }

        val raw = session.parameters["url"]?.firstOrNull()
            ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing url parameter", origin)
        val initial = try { validateUrl(raw) } catch (e: Exception) {
            return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid destination URL: ${safeMessage(e)}", origin)
        }
        val requestBody = if (session.method == Method.POST) {
            val length = session.headers["content-length"]?.toLongOrNull()
                ?: return error(NanoHTTPD.Response.Status.LENGTH_REQUIRED, "Content-Length is required for POST", origin)
            if (length < 0L || length > MAX_POST_BYTES) return error(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, "POST body is too large", origin)
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val bytes = files["postData"]?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                if (bytes.size.toLong() > MAX_POST_BYTES) return error(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, "POST body is too large", origin)
                bytes
            } catch (e: Exception) {
                return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Could not read POST body", origin)
            }
        } else null
        if (stopped.get()) return error(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "Proxy is closed", origin)

        var current = initial
        var response: OkHttpResponse? = null
        try {
            for (redirects in 0..MAX_REDIRECTS) {
                val builder = Request.Builder().url(current.toString())
                if (session.method == Method.HEAD) builder.head()
                else if (session.method == Method.POST) builder.post((requestBody ?: ByteArray(0)).toRequestBody(session.headers["content-type"]?.toMediaTypeOrNull()))
                else builder.get()
                copyRequestHeaders(session.headers, builder, current)
                val call = client.newCall(builder.build())
                calls.add(call)
                if (stopped.get()) { calls.remove(call); call.cancel(); throw IOException("Proxy is closed") }
                val upstream = call.execute()
                calls.remove(call)
                if (isRedirect(upstream.code)) {
                    val location = upstream.header("Location")
                    if (location.isNullOrBlank()) { response = upstream; break }
                    if (redirects == MAX_REDIRECTS) { upstream.close(); throw IOException("Too many redirects (maximum $MAX_REDIRECTS)") }
                    val next = try { current.resolve(location) } catch (e: Exception) {
                        upstream.close(); throw IOException("Invalid redirect URL")
                    }
                    upstream.close()
                    current = validateUrl(next.toString()) // revalidate host, port, scheme and DNS at connection
                    continue
                }
                response = upstream
                break
            }
            val upstream = response ?: throw IOException("Upstream request failed")
            return streamingResponse(upstream, origin, session.method == Method.HEAD)
        } catch (e: Exception) {
            response?.close()
            return error(statusFor(502), "Proxy request failed: ${safeMessage(e)}", origin)
        }
    }

    private fun copyRequestHeaders(input: Map<String, String>, out: Request.Builder, target: URI) {
        for ((key, value) in input) {
            val name = key.lowercase(Locale.ROOT)
            if (!isForwardableRequestHeader(name)) continue
            if (value.length > MAX_HEADER_VALUE || value.any { it == '\r' || it == '\n' || it == '\u0000' }) continue
            val forwardedName = if (name.startsWith("x-proxy-")) name.removePrefix("x-proxy-") else name
            if (forwardedName == "origin" || forwardedName == "referer") {
                if (!isSafeNavigationHeader(value)) continue
                out.header(if (forwardedName == "origin") "Origin" else "Referer", value)
                continue
            }
            if (name in FORWARDED_REQUEST_HEADERS) {
                out.header(canonicalHeader(name), value)
            }
        }
        // Derive these sensitive navigation headers from the validated target, never from caller input.
        val targetOrigin = "${target.scheme}://${target.rawAuthority}"
        if (out.build().header("Origin") == null) out.header("Origin", targetOrigin)
        if (out.build().header("Referer") == null) out.header("Referer", "$targetOrigin/")
    }

    private fun streamingResponse(upstream: OkHttpResponse, origin: String?, headOnly: Boolean): NanoHTTPD.Response {
        val status = statusFor(upstream.code)
        val body = upstream.body
        val mime = upstream.header("Content-Type") ?: "application/octet-stream"
        val contentLength = if (headOnly) -1L else body?.contentLength() ?: 0L
        val stream = if (headOnly) null else body?.byteStream()
        val closing = if (!headOnly && stream != null) object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                try { stream.close() } finally { upstream.close() }
            }
        } else null
        val result = when {
            headOnly -> newFixedLengthResponse(status, mime, "")
            contentLength >= 0 -> newFixedLengthResponse(status, mime, closing ?: ByteArrayInputStream(ByteArray(0)), contentLength)
            else -> newChunkedResponse(status, mime, closing ?: ByteArrayInputStream(ByteArray(0)))
        }.apply {
            if (headOnly) upstream.header("Content-Length")?.let { addHeader("Content-Length", it) }
            SAFE_RESPONSE_HEADERS.forEach { name -> upstream.header(name)?.let { value ->
                if (value.length <= MAX_HEADER_VALUE && value.none { it == '\r' || it == '\n' }) addHeader(name, value)
            } }
            addCorsHeaders(this, origin)
        }
        if (headOnly || stream == null) upstream.close()
        return result
    }

    private fun addCorsHeaders(response: NanoHTTPD.Response, origin: String?) {
        if (origin != null && origin in ALLOWED_ORIGINS) {
            response.addHeader("Access-Control-Allow-Origin", origin)
            response.addHeader("Vary", "Origin")
        }
        response.addHeader("Access-Control-Expose-Headers", "Accept-Ranges, Content-Length, Content-Range, ETag, Last-Modified")
        response.addHeader("X-Content-Type-Options", "nosniff")
    }

    override fun stop() {
        if (stopped.compareAndSet(false, true)) {
            synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }
            client.dispatcher.cancelAll()
            super.stop()
        }
    }

    private fun error(status: NanoHTTPD.Response.IStatus, text: String, origin: String? = null): NanoHTTPD.Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", text.take(512)).apply { addCorsHeaders(this, origin) }

    companion object {
        private const val MAX_REDIRECTS = 8
        private const val MAX_HEADER_VALUE = 8192
        private const val MAX_POST_BYTES = 2L * 1024 * 1024
        private val ALLOWED_ORIGINS = setOf(
            "https://appassets.androidplatform.net",
            "https://f0b8d40e.youtube-downloader-dmf.pages.dev",
            "https://youtube-downloader-dmf.pages.dev"
        )
        private val ALLOWED_REQUEST_HEADERS = setOf(
            "accept", "accept-language", "range", "if-range", "user-agent", "content-type",
            "x-proxy-origin", "x-proxy-referer", "x-youtube-client-name", "x-youtube-client-version",
            "x-youtube-page-cl", "x-youtube-page-label", "x-youtube-utc-offset", "x-youtube-time-zone",
            "x-goog-visitor-id"
        )
        private val FORWARDED_REQUEST_HEADERS = ALLOWED_REQUEST_HEADERS - setOf("content-type", "x-proxy-origin", "x-proxy-referer")
        private val SAFE_RESPONSE_HEADERS = listOf("Accept-Ranges", "Content-Range", "ETag", "Last-Modified", "Cache-Control", "Expires")

        /** Strict destination validation; DNS public-address enforcement is repeated at socket lookup. */
        internal fun validateUrl(value: String): URI {
            require(value.length in 1..8192) { "URL length is invalid" }
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https") { "Only HTTP and HTTPS are allowed" }
            require(uri.rawUserInfo == null) { "Userinfo is forbidden" }
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: throw IllegalArgumentException("Host is missing")
            require(host.isNotEmpty() && !host.endsWith(".local") && host != "localhost") { "Local hostnames are forbidden" }
            val port = uri.port
            require(port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) { "Only ports 80 and 443 are allowed" }
            if (isIpLiteral(host)) require(isPublicAddress(InetAddress.getByName(host))) { "Non-public IP address is forbidden" }
            // Preserve the raw encoded path and query. Rebuilding this URI with the
            // component constructor decodes escapes such as %2B/%3D in signed GoogleVideo
            // URLs, changing the signature and potentially yielding empty HLS responses.
            // Fragments are deliberately not sent upstream.
            val authority = uri.rawAuthority ?: throw IllegalArgumentException("Host authority is missing")
            val rawPath = uri.rawPath?.ifEmpty { "/" } ?: "/"
            val rawQuery = uri.rawQuery?.let { "?$it" } ?: ""
            return URI("$scheme://$authority$rawPath$rawQuery")
        }

        private fun isIpLiteral(host: String): Boolean = host.contains(':') || host.matches(Regex("[0-9.]+"))

        private fun isPublicAddress(address: InetAddress): Boolean {
            val b = address.address
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            if (address is Inet4Address) {
                val a = b[0].toInt() and 255; val c = b[1].toInt() and 255
                // IANA special-use, private, shared (CGNAT), documentation, benchmarking and reserved ranges.
                if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c in 64..127) ||
                    (a == 169 && c == 254) || (a == 172 && c in 16..31) || (a == 192 && c == 168) ||
                    (a == 192 && c == 0) || (a == 192 && c == 2) || (a == 198 && c in 18..19) ||
                    (a == 198 && c == 51) || (a == 203 && c == 0)) return false
                return true
            }
            if (address is Inet6Address) {
                // Accept global unicast only (2000::/3); exclude documentation 2001:db8::/32 and discard ranges.
                if ((b[0].toInt() and 0xE0) != 0x20) return false
                if ((b[0].toInt() and 255) == 0x20 && (b[1].toInt() and 255) == 0x01 && (b[2].toInt() and 255) == 0x0d && (b[3].toInt() and 255) == 0xb8) return false
                return true
            }
            return false
        }

        private fun statusFor(code: Int): NanoHTTPD.Response.IStatus = object : NanoHTTPD.Response.IStatus {
            override fun getRequestStatus(): Int = code
            override fun getDescription(): String = when (code) {
                206 -> "Partial Content"
                else -> "Upstream Response"
            }
        }

        private fun isRedirect(code: Int) = code in setOf(301, 302, 303, 307, 308)
        // pyodide-http/urllib may remap browser-forbidden headers to X-Proxy-*.
        // Accept them at CORS preflight, but copyRequestHeaders forwards only the
        // explicit safe allowlist (plus validated Origin/Referer); unknown names,
        // cookies and credentials are consequently ignored upstream.
        private fun isAllowedRequestHeader(name: String) = name in ALLOWED_REQUEST_HEADERS || name.startsWith("x-proxy-")
        private fun isForwardableRequestHeader(name: String) = name in FORWARDED_REQUEST_HEADERS || name in setOf("x-proxy-origin", "x-proxy-referer")
        private fun isSafeNavigationHeader(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
                (if (isIpLiteral(uri.host)) isPublicAddress(InetAddress.getByName(uri.host)) else
                    uri.host.lowercase(Locale.ROOT).trimEnd('.') != "localhost" && !uri.host.lowercase(Locale.ROOT).endsWith(".local"))
        } catch (_: Exception) { false }
        private fun canonicalHeader(name: String) = when (name) {
            "user-agent" -> "User-Agent"; "accept-language" -> "Accept-Language"; "if-range" -> "If-Range"
            "x-youtube-client-name" -> "X-YouTube-Client-Name"; "x-youtube-client-version" -> "X-YouTube-Client-Version"
            "x-youtube-page-cl" -> "X-YouTube-Page-CL"; "x-youtube-page-label" -> "X-YouTube-Page-Label"
            "x-youtube-utc-offset" -> "X-YouTube-Utc-Offset"; "x-youtube-time-zone" -> "X-YouTube-Time-Zone"
            "x-goog-visitor-id" -> "X-Goog-Visitor-Id"; else -> "Accept"
        }
        private fun safeMessage(e: Exception) = (e.message ?: e.javaClass.simpleName).replace(Regex("[\\r\\n]"), " ").take(180)
    }

    private class PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val normalized = hostname.lowercase(Locale.ROOT).trimEnd('.')
            if (normalized == "localhost" || normalized.endsWith(".local")) throw UnknownHostException("Local hostname is forbidden")
            val addresses = try { Dns.SYSTEM.lookup(normalized) } catch (e: Exception) {
                throw UnknownHostException("DNS lookup failed")
            }
            if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) })
                throw UnknownHostException("Host resolves to a non-public IP address")
            return addresses
        }
    }
}
