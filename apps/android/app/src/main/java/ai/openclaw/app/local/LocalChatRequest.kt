package ai.openclaw.app.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One conversation turn in the shape local mode persists and replays: plain text with a role. */
internal data class LocalChatTurn(
  val role: String,
  val text: String,
)

/**
 * A plain chat completion: no `tools`, `tool_choice`, `functions`, or attachments are ever sent,
 * so the model cannot be offered anything beyond text.
 */
internal fun buildLocalChatRequest(
  baseUrl: HttpUrl,
  model: String,
  apiKey: String?,
  turns: List<LocalChatTurn>,
): Request {
  val body =
    buildJsonObject {
      put("model", model)
      put("stream", true)
      put(
        "messages",
        buildJsonArray {
          turns.forEach { turn ->
            add(
              buildJsonObject {
                put("role", turn.role)
                put("content", turn.text)
              },
            )
          }
        },
      )
    }
  val builder =
    Request
      .Builder()
      .url(baseUrl.chatCompletionsUrl())
      .header("Accept", "text/event-stream")
      .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
  // The key leaves the device only here, as the bearer credential for the configured endpoint.
  val key = apiKey?.trim().orEmpty()
  if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
  return builder.build()
}

/** API keys are opaque ASCII tokens. Rejecting anything else keeps header construction from throwing. */
internal fun isValidLocalApiKey(key: String): Boolean = key.isNotEmpty() && key.all { it.code in 0x21..0x7E }
