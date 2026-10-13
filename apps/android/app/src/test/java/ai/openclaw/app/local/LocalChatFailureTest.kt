package ai.openclaw.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class LocalChatFailureTest {
  @Test
  fun statusCodesMapToDistinctUserFacingFailures() {
    fun kind(status: Int) = localChatFailureForHttpStatus(status, null, null).kind
    assertEquals(LocalChatFailureKind.Unauthorized, kind(401))
    assertEquals(LocalChatFailureKind.Unauthorized, kind(403))
    assertEquals(LocalChatFailureKind.NotFound, kind(404))
    assertEquals(LocalChatFailureKind.Redirect, kind(302))
    assertEquals(LocalChatFailureKind.Timeout, kind(408))
    assertEquals(LocalChatFailureKind.Timeout, kind(504))
    assertEquals(LocalChatFailureKind.RateLimited, kind(429))
    assertEquals(LocalChatFailureKind.BadRequest, kind(400))
    assertEquals(LocalChatFailureKind.BadRequest, kind(422))
    assertEquals(LocalChatFailureKind.ServerError, kind(500))
    assertEquals(LocalChatFailureKind.ServerError, kind(503))
  }

  @Test
  fun transportExceptionsMapByTypeAndNeverCarryTheirMessage() {
    assertEquals(LocalChatFailureKind.Timeout, localChatFailureForException(SocketTimeoutException("sk-secret")).kind)
    assertEquals(LocalChatFailureKind.Unreachable, localChatFailureForException(UnknownHostException("api.example.com")).kind)
    assertEquals(LocalChatFailureKind.Unreachable, localChatFailureForException(ConnectException("refused")).kind)
    assertEquals(LocalChatFailureKind.Unreachable, localChatFailureForException(NoRouteToHostException("no route")).kind)
    assertEquals(LocalChatFailureKind.Tls, localChatFailureForException(SSLHandshakeException("bad cert")).kind)
    assertEquals(LocalChatFailureKind.Unreachable, localChatFailureForException(IOException("boom")).kind)
    assertEquals(LocalChatFailureKind.Unknown, localChatFailureForException(IllegalStateException("x")).kind)
    assertNull(localChatFailureForException(IOException("Authorization: Bearer sk-secret")).providerMessage)
  }

  @Test
  fun authFailuresDropTheBodyBecauseProvidersQuoteTheRejectedKey() {
    val body = """{"error":{"message":"Incorrect API key provided: sk-secret-123."}}"""
    val failure = localChatFailureForHttpStatus(401, body, "sk-secret-123")
    assertNull(failure.providerMessage)
    assertFalse(failure.userMessage().contains("sk-secret"))
  }

  @Test
  fun providerMessagesAreRedactedCollapsedAndBounded() {
    val body = """{"error":{"message":"model\n  gpt-x   is not available for key sk-secret-123"}}"""
    val message = localChatFailureForHttpStatus(400, body, "sk-secret-123").providerMessage
    assertEquals("model gpt-x is not available for key [redacted]", message)
    val long = sanitizeProviderMessage("x".repeat(1000), null)
    assertEquals(240, long.length)
    assertTrue(long.endsWith("…"))
  }

  @Test
  fun providerBodiesWithDifferentShapesAreUnderstood() {
    assertEquals("a", extractProviderErrorMessage("""{"error":{"message":"a"}}"""))
    assertEquals("b", extractProviderErrorMessage("""{"error":"b"}"""))
    assertEquals("c", extractProviderErrorMessage("""{"message":"c"}"""))
    assertEquals("d", extractProviderErrorMessage("""{"detail":"d"}"""))
    assertNull(extractProviderErrorMessage("""{"error":{}}"""))
    assertNull(extractProviderErrorMessage("<html>Bad gateway</html>"))
    assertNull(extractProviderErrorMessage(""))
    assertNull(extractProviderErrorMessage(null))
  }

  @Test
  fun everyFailureKindHasAnActionableEnglishMessage() {
    LocalChatFailureKind.entries.forEach { kind ->
      val message = LocalChatFailure(kind, httpStatus = 500).userMessage()
      assertTrue("$kind", message.isNotBlank())
      assertFalse("$kind leaks a template placeholder", message.contains("\$"))
    }
    assertEquals(
      "The provider rejected the API key. Check it in Local mode settings.",
      LocalChatFailure(LocalChatFailureKind.Unauthorized).userMessage(),
    )
    assertEquals(
      "The provider reported a server error (HTTP 502). Try again shortly.",
      LocalChatFailure(LocalChatFailureKind.ServerError, httpStatus = 502).userMessage(),
    )
    assertEquals(
      "The provider rejected the request: context too long",
      LocalChatFailure(LocalChatFailureKind.BadRequest, providerMessage = "context too long").userMessage(),
    )
    assertEquals("The model returned no text.", LocalChatFailure(LocalChatFailureKind.Empty).userMessage())
  }

  @Test
  fun theThrownExceptionCarriesTheUserFacingMessage() {
    val exception = LocalChatException(LocalChatFailure(LocalChatFailureKind.RateLimited))
    assertEquals("The provider is limiting requests. Wait a moment and try again.", exception.message)
  }
}
