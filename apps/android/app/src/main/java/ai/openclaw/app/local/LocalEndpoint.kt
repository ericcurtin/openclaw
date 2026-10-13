package ai.openclaw.app.local

import ai.openclaw.app.gateway.isLocalCleartextGatewayHost
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal enum class LocalEndpointError {
  Blank,
  InvalidUrl,
  UnsupportedScheme,
  EmbeddedCredentials,
  QueryOrFragment,
  InsecureRemoteHost,
}

internal sealed interface LocalEndpointResult {
  data class Valid(
    val baseUrl: HttpUrl,
  ) : LocalEndpointResult

  data class Invalid(
    val error: LocalEndpointError,
  ) : LocalEndpointResult
}

/**
 * Validates a user-entered OpenAI-compatible base URL (for example `https://api.openai.com/v1`).
 *
 * Mirrors the Gateway endpoint policy: `https` works everywhere, and cleartext `http` is limited to
 * loopback, `.local`, private LAN, and emulator-bridge hosts. The Android network security config
 * permits cleartext globally, so this check is the only enforcement point for the provider client.
 * Credentials never travel in the URL, so userinfo, query, and fragment are rejected.
 */
internal fun parseLocalEndpoint(raw: String): LocalEndpointResult {
  val trimmed = raw.trim()
  if (trimmed.isEmpty()) return LocalEndpointResult.Invalid(LocalEndpointError.Blank)
  val schemeEnd = trimmed.indexOf("://")
  val withScheme =
    if (schemeEnd < 0) {
      // Match Gateway entry: a bare host defaults to the secure scheme.
      "https://$trimmed"
    } else {
      val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.US)
      if (scheme != "http" && scheme != "https") {
        return LocalEndpointResult.Invalid(LocalEndpointError.UnsupportedScheme)
      }
      trimmed
    }
  val url = withScheme.toHttpUrlOrNull() ?: return LocalEndpointResult.Invalid(LocalEndpointError.InvalidUrl)
  if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
    return LocalEndpointResult.Invalid(LocalEndpointError.EmbeddedCredentials)
  }
  if (url.query != null || url.fragment != null) {
    return LocalEndpointResult.Invalid(LocalEndpointError.QueryOrFragment)
  }
  // The emulator bridge 10.0.2.2 sits inside 10.0.0.0/8, so the alias flag never changes this answer.
  if (!url.isHttps && !isLocalCleartextGatewayHost(url.host, allowEmulatorBridgeAlias = false)) {
    return LocalEndpointResult.Invalid(LocalEndpointError.InsecureRemoteHost)
  }
  return LocalEndpointResult.Valid(url)
}

/** Appends `chat/completions` to the validated base, tolerating a trailing slash. */
internal fun HttpUrl.chatCompletionsUrl(): HttpUrl = newBuilder().addPathSegments("chat/completions").build()
