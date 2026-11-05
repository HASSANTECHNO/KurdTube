package com.techno.kurdtube.backend.proxy

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.techno.kurdtube.backend.utils.async
import com.techno.kurdtube.backend.utils.sync
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executor

class PublicProxyManager {

    fun configure(onResult: (Result<Int>) -> Unit) {
        async {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    throw IllegalStateException("This WebView does not support proxy settings.")
                }

                val proxies = loadProxyCandidates()
                if (proxies.isEmpty()) {
                    throw IllegalStateException("No public HTTPS-capable proxies were found.")
                }

                val proxyConfig = ProxyConfig.Builder().apply {
                    proxies.forEach { proxy ->
                        addProxyRule(proxy, ProxyConfig.MATCH_ALL_SCHEMES)
                    }
                }.build()

                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    Executor { command -> command.run() },
                    Runnable { sync { onResult(Result.success(proxies.size)) } }
                )
            } catch (error: Exception) {
                sync { onResult(Result.failure(error)) }
            }
        }
    }

    fun clear(onComplete: () -> Unit = {}) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onComplete()
            return
        }

        ProxyController.getInstance().clearProxyOverride(
            Executor { command -> command.run() },
            Runnable { sync(onComplete) }
        )
    }

    private fun loadProxyCandidates(): List<String> {
        val listUrls = listOf(
            "https://raw.githubusercontent.com/maximilianfeix/free-proxy-list/main/https.txt",
            "https://cdn.jsdelivr.net/gh/maximilianfeix/free-proxy-list@main/https.txt"
        )

        for (listUrl in listUrls) {
            val connection = URL(listUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "GET"

                if (connection.responseCode !in 200..299) continue

                val candidates = connection.inputStream.bufferedReader().useLines { lines ->
                    lines.mapNotNull(::normalizeProxyUrl)
                        .distinct()
                        .take(MAX_PROXY_CANDIDATES)
                        .toList()
                }
                if (candidates.isNotEmpty()) return candidates
            } catch (_: Exception) {
                // Try the CDN mirror when the GitHub raw endpoint is unavailable.
            } finally {
                connection.disconnect()
            }
        }

        return emptyList()
    }

    private fun normalizeProxyUrl(value: String): String? {
        val candidate = value.trim()
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }

        if (uri.scheme !in SUPPORTED_PROXY_SCHEMES) return null
        if (uri.host.isNullOrBlank() || uri.port !in 1..65535) return null
        return "${uri.scheme}://${uri.host}:${uri.port}"
    }

    private companion object {
        const val MAX_PROXY_CANDIDATES = 20
        val SUPPORTED_PROXY_SCHEMES = setOf("http", "https")
    }
}
