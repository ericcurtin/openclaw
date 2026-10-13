package ai.openclaw.app.local

import ai.openclaw.app.i18n.nativeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class LocalChatFailureKind {
  NotConfigured,
  InvalidEndpoint,
  Unauthorized,
  NotFound,
  Redirect,
  BadRequest,
  RateLimited,
  ServerError,
  Timeout,
  Unreachable,
  Tls,
  Malformed,
  Empty,
  Provider,
  Unknown,
}

/**
 * Provider and transport failures in a form that is safe to show: [providerMessage] is already
 * redacted and bounded, and no request header or URL is ever captured.
 */
internal data class LocalChatFailure(
  val kind: LocalChatFailureKind,
  val httpStatus: Int? = null,
  val providerMessage: String? = null,
)

private const val PROVIDER_MESSAGE_MAX_CHARS = 240

/** Maps a non-2xx response. Auth failures never echo the body, which can quote the rejected key. */
internal fun localChatFailureForHttpStatus(
  status: Int,
  body: String?,
  apiKey: String?,
): LocalChatFailure {
  val kind =
    when {
      status == 401 || status == 403 -> LocalChatFailureKind.Unauthorized
      status == 404 -> LocalChatFailureKind.NotFound
      status in 300..399 -> LocalChatFailureKind.Redirect
      status == 408 || status == 504 -> LocalChatFailureKind.Timeout
      status == 429 -> LocalChatFailureKind.RateLimited
      status in 400..499 -> LocalChatFailureKind.BadRequest
      status >= 500 -> LocalChatFailureKind.ServerError
      else -> LocalChatFailureKind.Unknown
    }
  val message =
    if (kind == LocalChatFailureKind.Unauthorized) null else extractProviderErrorMessage(body)?.let { sanitizeProviderMessage(it, apiKey) }
  return LocalChatFailure(kind = kind, httpStatus = status, providerMessage = message)
}

/** Maps a transport exception by type only; exception messages can carry URLs or headers. */
internal fun localChatFailureForException(error: Throwable): LocalChatFailure {
  val kind =
    when (error) {
      is SocketTimeoutException -> LocalChatFailureKind.Timeout
      is UnknownHostException, is ConnectException, is NoRouteToHostException -> LocalChatFailureKind.Unreachable
      is SSLException -> LocalChatFailureKind.Tls
      is IOException -> LocalChatFailureKind.Unreachable
      else -> LocalChatFailureKind.Unknown
    }
  return LocalChatFailure(kind = kind)
}

/** Pulls `error.message`, `error` as a string, `message`, or `detail` out of a provider error body. */
internal fun extractProviderErrorMessage(body: String?): String? {
  val text = body?.trim().orEmpty()
  if (text.isEmpty()) return null
  val root: JsonElement = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
  val obj: JsonObject = root as? JsonObject ?: return null
  return providerMessageFrom(obj)
}

internal fun providerMessageFrom(obj: JsonObject): String? {
  val error = obj["error"]
  val fromError =
    when (error) {
      is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
      is JsonPrimitive -> error.contentOrNull
      else -> null
    }
  return (fromError ?: (obj["message"] as? JsonPrimitive)?.contentOrNull ?: (obj["detail"] as? JsonPrimitive)?.contentOrNull)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
}

/** Removes the configured key (and its bearer form) from provider text, then bounds the length. */
internal fun sanitizeProviderMessage(
  message: String,
  apiKey: String?,
): String {
  var cleaned = message.replace(Regex("\\s+"), " ").trim()
  val key = apiKey?.trim().orEmpty()
  if (key.isNotEmpty()) cleaned = cleaned.replace(key, "[redacted]")
  return if (cleaned.length <= PROVIDER_MESSAGE_MAX_CHARS) cleaned else cleaned.take(PROVIDER_MESSAGE_MAX_CHARS - 1).trimEnd() + "…"
}

/** English-source, localizable text for the chat error banner. */
internal fun LocalChatFailure.userMessage(): String =
  when (kind) {
    LocalChatFailureKind.NotConfigured -> {
      nativeString("Local mode is not set up. Add an endpoint in Settings.")
    }

    LocalChatFailureKind.InvalidEndpoint -> {
      nativeString("The saved base URL is not valid. Update it in Local mode settings.")
    }

    LocalChatFailureKind.Unauthorized -> {
      nativeString("The provider rejected the API key. Check it in Local mode settings.")
    }

    LocalChatFailureKind.NotFound -> {
      nativeString("The provider could not find that endpoint or model. Check the base URL and model name.")
    }

    LocalChatFailureKind.Redirect -> {
      nativeString("The server redirected the request. Check the base URL, including https or http.")
    }

    LocalChatFailureKind.BadRequest -> {
      providerMessage?.let { nativeString("The provider rejected the request: \$message", it) }
        ?: nativeString("The provider rejected the request.")
    }

    LocalChatFailureKind.RateLimited -> {
      nativeString("The provider is limiting requests. Wait a moment and try again.")
    }

    LocalChatFailureKind.ServerError -> {
      nativeString("The provider reported a server error (HTTP \$status). Try again shortly.", httpStatus ?: 500)
    }

    LocalChatFailureKind.Timeout -> {
      nativeString("The provider took too long to respond. Try again.")
    }

    LocalChatFailureKind.Unreachable -> {
      nativeString("Could not reach the server. Check the base URL and your network connection.")
    }

    LocalChatFailureKind.Tls -> {
      nativeString("Could not make a secure connection. Use https, or http only for a server on your own network.")
    }

    LocalChatFailureKind.Malformed -> {
      nativeString("The provider sent a response this app could not read.")
    }

    LocalChatFailureKind.Empty -> {
      nativeString("The model returned no text.")
    }

    LocalChatFailureKind.Provider -> {
      providerMessage?.let { nativeString("The provider reported an error: \$message", it) }
        ?: nativeString("The provider reported an error.")
    }

    LocalChatFailureKind.Unknown -> {
      nativeString("The request failed.")
    }
  }

/** Carries a mapped failure through the gateway-shaped request seam; the message is user-facing. */
internal class LocalChatException(
  val failure: LocalChatFailure,
) : IllegalStateException(failure.userMessage())
