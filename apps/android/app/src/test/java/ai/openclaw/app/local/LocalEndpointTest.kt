package ai.openclaw.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointTest {
  private fun valid(raw: String): LocalEndpointResult.Valid = parseLocalEndpoint(raw) as LocalEndpointResult.Valid

  private fun error(raw: String): LocalEndpointError = (parseLocalEndpoint(raw) as LocalEndpointResult.Invalid).error

  @Test
  fun httpsIsAcceptedForPublicHostsAndKeepsTheVersionPath() {
    assertEquals("https://api.openai.com/v1/chat/completions", valid("https://api.openai.com/v1").baseUrl.chatCompletionsUrl().toString())
    assertEquals("https://openrouter.ai/api/v1/chat/completions", valid("https://openrouter.ai/api/v1").baseUrl.chatCompletionsUrl().toString())
    assertEquals("https://api.openai.com/v1/chat/completions", valid("  https://api.openai.com/v1/  ").baseUrl.chatCompletionsUrl().toString())
    assertEquals("https://example.com/chat/completions", valid("https://example.com").baseUrl.chatCompletionsUrl().toString())
  }

  @Test
  fun aBareHostDefaultsToTheSecureScheme() {
    assertEquals("https://api.example.com/v1", valid("api.example.com/v1").baseUrl.toString())
    assertEquals(LocalEndpointError.InsecureRemoteHost, error("http://api.example.com/v1"))
  }

  @Test
  fun cleartextIsAllowedOnlyForLocalHosts() {
    listOf(
      "http://localhost:11434/v1",
      "http://127.0.0.1:8080/v1",
      "http://10.0.2.2:11434/v1",
      "http://10.1.2.3/v1",
      "http://172.16.0.5:1234/v1",
      "http://172.31.255.255/v1",
      "http://192.168.1.20:1234/v1",
      "http://169.254.10.1/v1",
      "http://studio.local:8080/v1",
      "http://[::1]:8080/v1",
    ).forEach { raw -> assertTrue(raw, parseLocalEndpoint(raw) is LocalEndpointResult.Valid) }
  }

  @Test
  fun cleartextToRemoteOrLookalikeHostsIsRejected() {
    listOf(
      "http://api.openai.com/v1",
      "http://8.8.8.8/v1",
      "http://100.64.0.1/v1",
      "http://gateway.tail-example.ts.net/v1",
      "http://172.32.0.1/v1",
      "http://192.169.1.1/v1",
      "http://studio.local.evil.example/v1",
      "http://localhost.evil.example/v1",
    ).forEach { raw -> assertEquals(raw, LocalEndpointError.InsecureRemoteHost, error(raw)) }
  }

  @Test
  fun credentialsNeverTravelInTheUrl() {
    assertEquals(LocalEndpointError.EmbeddedCredentials, error("https://user:pass@api.example.com/v1"))
    assertEquals(LocalEndpointError.EmbeddedCredentials, error("https://sk-secret@api.example.com/v1"))
    assertEquals(LocalEndpointError.QueryOrFragment, error("https://api.example.com/v1?api_key=sk-secret"))
    assertEquals(LocalEndpointError.QueryOrFragment, error("https://api.example.com/v1#token"))
    assertEquals(LocalEndpointError.QueryOrFragment, error("https://api.example.com/v1?"))
  }

  @Test
  fun malformedInputIsClassified() {
    assertEquals(LocalEndpointError.Blank, error("   "))
    assertEquals(LocalEndpointError.UnsupportedScheme, error("ftp://example.com"))
    assertEquals(LocalEndpointError.UnsupportedScheme, error("wss://example.com"))
    assertEquals(LocalEndpointError.InvalidUrl, error("https://"))
  }
}
