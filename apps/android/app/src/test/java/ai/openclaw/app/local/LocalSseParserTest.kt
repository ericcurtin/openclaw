package ai.openclaw.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSseParserTest {
  private fun chunk(text: String) = """{"choices":[{"delta":{"content":"$text"}}]}"""

  private fun decode(payload: String) = decodeOpenAiStreamPayload(payload)

  @Test
  fun completedEventsAreReturnedAtTheirBlankLine() {
    val parser = LocalSseParser()
    assertEquals(listOf("one", "two"), parser.feed("data: one\n\ndata: two\n\n"))
  }

  @Test
  fun anEventSplitAcrossChunksEmitsOnlyOnceComplete() {
    val parser = LocalSseParser()
    assertEquals(emptyList<String>(), parser.feed("da"))
    assertEquals(emptyList<String>(), parser.feed("ta: {\"a\""))
    assertEquals(emptyList<String>(), parser.feed(":1}\n"))
    assertEquals(listOf("{\"a\":1}"), parser.feed("\n"))
  }

  @Test
  fun everySplitPointOfAStreamYieldsTheSameEvents() {
    val stream = "data: first\r\n\r\n: keep-alive\r\ndata: sec\ndata: ond\r\r"
    val expected = listOf("first", "sec\nond")
    for (split in 0..stream.length) {
      val parser = LocalSseParser()
      val events = parser.feed(stream.substring(0, split)) + parser.feed(stream.substring(split)) + parser.finish()
      assertEquals("split at $split", expected, events)
    }
  }

  @Test
  fun commentsAndOtherFieldsAreIgnoredAndOneLeadingSpaceIsStripped() {
    val parser = LocalSseParser()
    assertEquals(listOf(" two spaces"), parser.feed(": ping\nevent: message\nid: 7\nretry: 100\ndata:  two spaces\n\n"))
  }

  @Test
  fun finishFlushesAnEventThatLacksATrailingBlankLine() {
    val parser = LocalSseParser()
    assertEquals(emptyList<String>(), parser.feed("data: tail"))
    assertEquals(listOf("tail"), parser.finish())
    assertEquals(emptyList<String>(), parser.finish())
  }

  @Test
  fun textDeltasAreDecoded() {
    assertEquals(listOf(LocalStreamEvent.Delta("Hel")), decode(chunk("Hel")))
  }

  @Test
  fun doneMarkerEndsTheStream() {
    assertEquals(listOf(LocalStreamEvent.Done), decode("[DONE]"))
    assertEquals(listOf(LocalStreamEvent.Done), decode("  [DONE]  "))
  }

  @Test
  fun chunksWithoutTextProduceNothing() {
    assertEquals(emptyList<LocalStreamEvent>(), decode("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
    assertEquals(emptyList<LocalStreamEvent>(), decode("""{"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}"""))
    assertEquals(emptyList<LocalStreamEvent>(), decode("""{"choices":[{"delta":{"content":null}}]}"""))
    assertEquals(emptyList<LocalStreamEvent>(), decode("""{"choices":[],"usage":{"total_tokens":3}}"""))
    assertEquals(emptyList<LocalStreamEvent>(), decode("""{"choices":[{"delta":{"reasoning_content":"thinking"}}]}"""))
    assertEquals(emptyList<LocalStreamEvent>(), decode("   "))
  }

  @Test
  fun toolCallDeltasAreNeverSurfacedAsText() {
    assertEquals(
      emptyList<LocalStreamEvent>(),
      decode("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"exec","arguments":"{}"}}]}}]}"""),
    )
  }

  @Test
  fun errorBodiesInsideTheStreamBecomeProviderFailures() {
    val event = decode("""{"error":{"message":"The server is overloaded","type":"overloaded"}}""")?.single() as LocalStreamEvent.StreamError
    assertEquals(LocalChatFailureKind.Provider, event.failure.kind)
    assertEquals("The server is overloaded", event.failure.providerMessage)
  }

  @Test
  fun aKeyEchoedByTheProviderIsRedacted() {
    val event = decodeOpenAiStreamPayload("""{"error":{"message":"Bad key sk-secret-123"}}""", apiKey = "sk-secret-123")?.single() as LocalStreamEvent.StreamError
    assertEquals("Bad key [redacted]", event.failure.providerMessage)
  }

  @Test
  fun payloadsThatAreNotJsonObjectsAreRejected() {
    assertNull(decode("not json"))
    assertNull(decode("[1,2,3]"))
    assertTrue(decode("{}")!!.isEmpty())
  }
}
