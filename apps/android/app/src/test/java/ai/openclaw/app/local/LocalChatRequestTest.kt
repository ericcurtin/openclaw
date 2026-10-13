package ai.openclaw.app.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatRequestTest {
  private val baseUrl = (parseLocalEndpoint("https://api.example.com/v1") as LocalEndpointResult.Valid).baseUrl
  private val turns = listOf(LocalChatTurn("user", "Hi"), LocalChatTurn("assistant", "Hello"), LocalChatTurn("user", "Why?"))

  @Test
  fun buildsAStreamingChatCompletionForTheConfiguredModel() {
    val request = buildLocalChatRequest(baseUrl, "gpt-test", "sk-secret", turns)
    assertEquals("POST", request.method)
    assertEquals("https://api.example.com/v1/chat/completions", request.url.toString())
    assertEquals("text/event-stream", request.header("Accept"))
    assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
    val body = Json.parseToJsonElement(request.bodyText()).jsonObject
    assertEquals("gpt-test", body.getValue("model").jsonPrimitive.content)
    assertEquals(JsonPrimitive(true), body["stream"])
    val messages = body.getValue("messages").jsonArray
    assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
    assertEquals(listOf("Hi", "Hello", "Why?"), messages.map { it.jsonObject.getValue("content").jsonPrimitive.content })
  }

  @Test
  fun neverOffersToolsOrAnythingBeyondText() {
    val body = Json.parseToJsonElement(buildLocalChatRequest(baseUrl, "m", "k", turns).bodyText()).jsonObject
    assertEquals(setOf("model", "stream", "messages"), body.keys)
    (body.getValue("messages") as JsonArray).forEach { message ->
      assertEquals(setOf("role", "content"), (message as JsonObject).keys)
    }
  }

  @Test
  fun theKeyTravelsOnlyAsTheBearerHeader() {
    val request = buildLocalChatRequest(baseUrl, "m", "sk-secret", turns)
    assertEquals("Bearer sk-secret", request.header("Authorization"))
    assertFalse(request.url.toString().contains("sk-secret"))
    assertFalse(request.bodyText().contains("sk-secret"))
    assertEquals(listOf("Authorization"), request.headers.names().filter { request.header(it)?.contains("sk-secret") == true })
  }

  @Test
  fun aMissingOrBlankKeySendsNoAuthorizationHeader() {
    assertNull(buildLocalChatRequest(baseUrl, "m", null, turns).header("Authorization"))
    assertNull(buildLocalChatRequest(baseUrl, "m", "   ", turns).header("Authorization"))
  }

  @Test
  fun apiKeysMustBePrintableAsciiWithoutWhitespace() {
    assertTrue(isValidLocalApiKey("sk-proj_ABC123.def"))
    assertFalse(isValidLocalApiKey(""))
    assertFalse(isValidLocalApiKey("sk abc"))
    assertFalse(isValidLocalApiKey("sk-abc\nX-Injected: 1"))
    assertFalse(isValidLocalApiKey("sk-\u00e9"))
  }
}
