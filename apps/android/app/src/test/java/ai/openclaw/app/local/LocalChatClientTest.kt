package ai.openclaw.app.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalChatClientTest {
  private fun delta(text: String) = """{"choices":[{"delta":{"content":"$text"}}]}"""

  private fun sse(vararg payloads: String) = payloads.joinToString("") { "data: $it\n\n" }

  private fun MockWebServer.request(apiKey: String? = "sk-test"): Request =
    buildLocalChatRequest(url("/v1"), "test-model", apiKey, listOf(LocalChatTurn("user", "Hi")))

  private fun collect(
    server: MockWebServer,
    apiKey: String? = "sk-test",
    client: LocalChatClient = LocalChatClient(),
  ): List<LocalStreamEvent> = runBlocking { withTimeout(10_000) { client.stream(server.request(apiKey), apiKey).toList() } }

  private fun failure(events: List<LocalStreamEvent>): LocalChatFailure = (events.last() as LocalStreamEvent.StreamError).failure

  @Test
  fun streamsDeltasUntilDoneAndSendsTheKeyOnlyAsABearerHeader() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(delta("Hel"), delta("lo"), "[DONE]")))
      val events = collect(server)
      assertEquals(listOf(LocalStreamEvent.Delta("Hel"), LocalStreamEvent.Delta("lo"), LocalStreamEvent.Done), events)
      val recorded = server.takeRequest()
      assertEquals("/v1/chat/completions", recorded.path)
      assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
      assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
    }
  }

  @Test
  fun aKeylessServerReceivesNoAuthorizationHeader() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setBody(sse(delta("ok"), "[DONE]")))
      collect(server, apiKey = null)
      assertNull(server.takeRequest().getHeader("Authorization"))
    }
  }

  @Test
  fun eventsSplitAcrossNetworkChunksStillDecode() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setChunkedBody(sse(delta("Gr\u00fc\u00dfe"), delta(" Welt"), "[DONE]"), 5))
      val events = collect(server)
      assertEquals(listOf(LocalStreamEvent.Delta("Gr\u00fc\u00dfe"), LocalStreamEvent.Delta(" Welt"), LocalStreamEvent.Done), events)
    }
  }

  @Test
  fun aStreamThatEndsWithoutDoneStillCompletesWhenItProducedText() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setBody(sse(delta("Hi"))))
      assertEquals(listOf(LocalStreamEvent.Delta("Hi"), LocalStreamEvent.Done), collect(server))
    }
  }

  @Test
  fun aCompletionWithoutAnyTextIsReportedAsEmpty() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setBody(""))
      server.enqueue(MockResponse().setBody(sse("[DONE]")))
      assertEquals(LocalChatFailureKind.Empty, failure(collect(server)).kind)
      assertEquals(LocalChatFailureKind.Empty, failure(collect(server)).kind)
    }
  }

  @Test
  fun anErrorInsideTheStreamEndsItAfterTheTextSoFar() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setBody(sse(delta("Hel"), """{"error":{"message":"overloaded"}}""", delta("never"))))
      val events = collect(server)
      assertEquals(LocalStreamEvent.Delta("Hel"), events.first())
      assertEquals(2, events.size)
      assertEquals(LocalChatFailure(LocalChatFailureKind.Provider, providerMessage = "overloaded"), failure(events))
    }
  }

  @Test
  fun anUnreadablePayloadIsMalformed() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setBody("data: {oops\n\n"))
      assertEquals(LocalChatFailureKind.Malformed, failure(collect(server)).kind)
    }
  }

  @Test
  fun httpErrorsAreMappedAndNeverEchoTheKey() {
    MockWebServer().use { server ->
      server.start()
      val keyEcho = """{"error":{"message":"Incorrect API key provided: sk-test"}}"""
      server.enqueue(MockResponse().setResponseCode(401).setBody(keyEcho))
      server.enqueue(MockResponse().setResponseCode(403).setBody(keyEcho))
      server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
      server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
      server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
      server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"context too long for sk-test"}}"""))
      val unauthorized = failure(collect(server))
      assertEquals(LocalChatFailure(LocalChatFailureKind.Unauthorized, httpStatus = 401), unauthorized)
      assertEquals(LocalChatFailure(LocalChatFailureKind.Unauthorized, httpStatus = 403), failure(collect(server)))
      assertEquals(LocalChatFailure(LocalChatFailureKind.NotFound, httpStatus = 404, providerMessage = "model not found"), failure(collect(server)))
      assertEquals(LocalChatFailureKind.RateLimited, failure(collect(server)).kind)
      assertEquals(LocalChatFailure(LocalChatFailureKind.ServerError, httpStatus = 500), failure(collect(server)))
      assertEquals("context too long for [redacted]", failure(collect(server)).providerMessage)
    }
  }

  @Test
  fun redirectsAreNotFollowedSoTheKeyStaysOnTheConfiguredOrigin() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
      server.enqueue(MockResponse().setBody(sse(delta("followed"), "[DONE]")))
      assertEquals(LocalChatFailureKind.Redirect, failure(collect(server)).kind)
      assertEquals(1, server.requestCount)
    }
  }

  @Test
  fun anUnreachableServerIsReportedWithoutThrowing() {
    val server = MockWebServer()
    server.start()
    val request = server.request()
    server.close()
    val events = runBlocking { withTimeout(10_000) { LocalChatClient().stream(request, "sk-test").toList() } }
    assertEquals(LocalChatFailureKind.Unreachable, failure(events).kind)
  }

  @Test
  fun cancellingTheCollectorCancelsTheHttpCall() {
    MockWebServer().use { server ->
      server.start()
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      val canceled = CountDownLatch(1)
      val client =
        LocalChatClient(
          defaultLocalChatHttpClient()
            .newBuilder()
            .eventListener(
              object : EventListener() {
                override fun canceled(call: Call) {
                  canceled.countDown()
                }
              },
            ).build(),
        )
      runBlocking {
        val job = launch(Dispatchers.IO) { client.stream(server.request(), "sk-test").collect { } }
        // The request reaching the server proves the call is in flight before it is cancelled.
        assertTrue(server.takeRequest(10, TimeUnit.SECONDS) != null)
        withTimeout(10_000) { job.cancelAndJoin() }
      }
      assertTrue(canceled.await(10, TimeUnit.SECONDS))
    }
  }
}
