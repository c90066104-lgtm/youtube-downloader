package com.manus.youtubedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request

class LocalCorsProxyTest {
    @Test fun acceptsPublicHttpAndHttpsTargets() {
        assertEquals("https://www.youtube.com/watch?v=abc", LocalCorsProxy.validateUrl("https://www.youtube.com/watch?v=abc").toString())
        assertEquals("http://8.8.8.8/", LocalCorsProxy.validateUrl("http://8.8.8.8/").toString())
    }

    @Test fun preservesPercentEncodedPathAndQueryForSignedMediaUrls() {
        val input = "https://rr1---sn.example.googlevideo.com/api/manifest/hls_playlist/%2Fseg?mime=video%2Fmp4&sig=a%2Bb&n=x%3Dy#discarded"
        val expected = "https://rr1---sn.example.googlevideo.com/api/manifest/hls_playlist/%2Fseg?mime=video%2Fmp4&sig=a%2Bb&n=x%3Dy"
        assertEquals(expected, LocalCorsProxy.validateUrl(input).toASCIIString())
    }

    @Test fun blocksLoopbackPrivateAndLinkLocalAddresses() {
        listOf("http://127.0.0.1/", "http://10.0.0.1/", "http://192.168.1.5/", "http://169.254.169.254/").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { LocalCorsProxy.validateUrl(url) }
        }
    }

    @Test fun blocksLocalHostnamesCredentialsAndUnexpectedPorts() {
        listOf(
            "http://localhost/", "http://printer.local/", "http://user:pass@example.com/",
            "http://example.com:8080/", "file:///etc/passwd", "ftp://example.com/file"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { LocalCorsProxy.validateUrl(url) }
        }
    }

    @Test fun startsOnLoopbackHandlesCorsPreflightAndRejectsUnknownOrigin() {
        val proxy = LocalCorsProxy()
        try {
            proxy.start(5_000, false)
            assertTrue(proxy.localPort > 0)
            assertTrue(proxy.localUrl.startsWith("http://127.0.0.1:"))
            val client = OkHttpClient()
            val preflight = Request.Builder().url(proxy.localUrl)
                .header("Origin", "https://appassets.androidplatform.net")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "range, x-proxy-host, x-proxy-sec-fetch-site")
                .method("OPTIONS", null).build()
            client.newCall(preflight).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("https://appassets.androidplatform.net", response.header("Access-Control-Allow-Origin"))
                assertTrue(response.header("Access-Control-Allow-Headers")!!.contains("range"))
                assertTrue(response.header("Access-Control-Allow-Headers")!!.contains("x-proxy-host"))
                assertTrue(response.header("Access-Control-Allow-Headers")!!.contains("x-proxy-sec-fetch-site"))
            }
            val sitePreflight = Request.Builder().url(proxy.localUrl)
                .header("Origin", "https://youtube-downloader-dmf.pages.dev")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "range")
                .method("OPTIONS", null).build()
            client.newCall(sitePreflight).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("https://youtube-downloader-dmf.pages.dev", response.header("Access-Control-Allow-Origin"))
            }
            val blocked = Request.Builder().url(proxy.localUrl)
                .header("Origin", "https://untrusted.example")
                .get().build()
            client.newCall(blocked).execute().use { response -> assertEquals(403, response.code) }
        } finally {
            proxy.stop()
        }
    }
}
