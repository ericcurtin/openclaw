package ai.openclaw.app.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Incremental Server-Sent Events decoder. Feed arbitrary text chunks; each completed event's `data`
 * payload is returned once its terminating blank line arrives. Handles `\n`, `\r\n`, and `\r` line
 * ends, including a `\r\n` pair split across chunks, comment lines, and multi-line `data` fields.
 */
internal class LocalSseParser {
  private val line = StringBuilder()
  private val data = StringBuilder()
  private var hasData = false
  private var skipLineFeed = false

  fun feed(chunk: String): List<String> {
    val events = mutableListOf<String>()
    for (char in chunk) {
      if (skipLineFeed) {
        skipLineFeed = false
        if (char == '\n') continue
      }
      when (char) {
        '\n' -> {
          endLine(events)
        }

        '\r' -> {
          skipLineFeed = true
          endLine(events)
        }

        else -> {
          line.append(char)
        }
      }
    }
    return events
  }

  /** Flushes a final line and event when the stream ends without a trailing blank line. */
  fun finish(): List<String> {
    val events = mutableListOf<String>()
    if (line.isNotEmpty()) endLine(events)
    endLine(events)
    return events
  }

  private fun endLine(events: MutableList<String>) {
    val text = line.toString()
    line.setLength(0)
    if (text.isEmpty()) {
      if (hasData) {
        events += data.toString()
        data.setLength(0)
        hasData = false
      }
      return
    }
    if (text.startsWith(":")) return
    val colon = text.indexOf(':')
    val field = if (colon < 0) text else text.substring(0, colon)
    if (field != "data") return
    var value = if (colon < 0) "" else text.substring(colon + 1)
    if (value.startsWith(" ")) value = value.substring(1)
    if (hasData) data.append('\n')
    data.append(value)
    hasData = true
  }
}

internal sealed interface LocalStreamEvent {
  data class Delta(
    val text: String,
  ) : LocalStreamEvent

  /** The provider signalled the end of the completion (`[DONE]`). */
  data object Done : LocalStreamEvent

  /** The provider reported an error inside an otherwise successful stream. */
  data class StreamError(
    val failure: LocalChatFailure,
  ) : LocalStreamEvent
}

/**
 * Decodes one OpenAI-compatible `chat.completion.chunk` payload. Only assistant text deltas are
 * surfaced; reasoning fields, tool-call deltas, role-only chunks, and usage chunks yield nothing.
 * Returns null for payloads that are not valid JSON objects.
 */
internal fun decodeOpenAiStreamPayload(
  payload: String,
  apiKey: String? = null,
): List<LocalStreamEvent>? {
  val trimmed = payload.trim()
  if (trimmed.isEmpty()) return emptyList()
  if (trimmed == "[DONE]") return listOf(LocalStreamEvent.Done)
  val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
  if (root.containsKey("error")) {
    val message = providerMessageFrom(root)?.let { sanitizeProviderMessage(it, apiKey) }
    return listOf(LocalStreamEvent.StreamError(LocalChatFailure(LocalChatFailureKind.Provider, providerMessage = message)))
  }
  val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
  val text = ((choice["delta"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
  return if (text.isNullOrEmpty()) emptyList() else listOf(LocalStreamEvent.Delta(text))
}
