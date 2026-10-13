package ai.openclaw.app.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalChatBackendTest {
  private val config = LocalModeConfig("https://api.example.com/v1", "gpt-test")
  private val main = LOCAL_MAIN_SESSION_KEY

  private class Fixture(
    test: TestScope,
    config: LocalModeConfig?,
    apiKey: String? = "sk-secret-123",
    script: (Request) -> Flow<LocalStreamEvent> = { flowOf(LocalStreamEvent.Delta("ok"), LocalStreamEvent.Done) },
  ) {
    val store = FakeLocalModeStore(config = config, apiKey = apiKey)
    val conversations = InMemoryLocalConversationStore()
    val streamer = ScriptedStreamer(script)
    val events = mutableListOf<JsonObject>()
    private var clock = 1_000L
    val backend =
      LocalChatBackend(
        scope = test.backgroundScope,
        modeStore = store,
        conversations = conversations,
        client = streamer,
        emitEvent = { name, payload ->
          assertEquals("chat", name)
          events += Json.parseToJsonElement(payload).jsonObject
        },
        // Each reading advances past the delta throttle, so every delta is emitted.
        nowMs = {
          clock += 100L
          clock
        },
        newId = { "generated-id" },
      )

    suspend fun call(
      method: String,
      vararg params: Pair<String, Any?>,
    ): JsonObject {
      val body =
        buildJsonObject {
          params.forEach { (key, value) ->
            when (value) {
              is String -> put(key, value)
              is Boolean -> put(key, value)
              is Number -> put(key, value)
              is JsonElement -> put(key, value)
              else -> Unit
            }
          }
        }
      return Json.parseToJsonElement(backend.request(method, body.toString())).jsonObject
    }

    suspend fun send(
      text: String,
      runId: String,
      sessionKey: String = LOCAL_MAIN_SESSION_KEY,
    ) = call("chat.send", "sessionKey" to sessionKey, "message" to text, "idempotencyKey" to runId)

    fun states(runId: String) = events.filter { it.getValue("runId").jsonPrimitive.content == runId }.map { it.getValue("state").jsonPrimitive.content }
  }

  private fun JsonObject.texts() = getValue("messages").jsonArray.map { it.jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content }

  private fun JsonObject.roles() = getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }

  @Test
  fun healthRequiresASavedSetup() =
    runTest {
      val failure = runCatching { Fixture(this, null).call("health") }.exceptionOrNull()
      assertTrue(failure is LocalChatException)
      assertEquals(LocalChatFailureKind.NotConfigured, (failure as LocalChatException).failure.kind)
      assertEquals("true", Fixture(this, config).call("health").getValue("ok").jsonPrimitive.content)
    }

  @Test
  fun theModelCatalogHasTheOneConfiguredModelAndStatesItHasNoTools() =
    runTest {
      val models = Fixture(this, config).call("models.list").getValue("models").jsonArray
      assertEquals(1, models.size)
      val model = models.single().jsonObject
      assertEquals("gpt-test", model.getValue("id").jsonPrimitive.content)
      assertEquals("local", model.getValue("provider").jsonPrimitive.content)
      assertEquals("false", model.getValue("supportsTools").jsonPrimitive.content)
      assertEquals(0, Fixture(this, null).call("models.list").getValue("models").jsonArray.size)
    }

  @Test
  fun aSendStreamsThenPersistsBothTurnsInReplayableShape() =
    runTest {
      val fixture =
        Fixture(this, config, script = { flowOf(LocalStreamEvent.Delta("Hel"), LocalStreamEvent.Delta("lo"), LocalStreamEvent.Done) })
      val ack = fixture.send("Hi there", "run-1")
      assertEquals("run-1", ack.getValue("runId").jsonPrimitive.content)
      assertEquals("started", ack.getValue("status").jsonPrimitive.content)
      runCurrent()
      assertEquals(listOf("delta", "delta", "final"), fixture.states("run-1"))
      val deltas = fixture.events.filter { it.getValue("state").jsonPrimitive.content == "delta" }
      // The controller replaces its streaming text with each delta, so events carry cumulative text.
      assertEquals(listOf("Hel", "Hello"), deltas.map { it.getValue("message").jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content })
      val final = fixture.events.last()
      assertEquals(main, final.getValue("sessionKey").jsonPrimitive.content)
      assertEquals("Hello", final.getValue("message").jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content)

      val history = fixture.call("chat.history", "sessionKey" to main)
      assertEquals(listOf("user", "assistant"), history.roles())
      assertEquals(listOf("Hi there", "Hello"), history.texts())
      val user = history.getValue("messages").jsonArray[0].jsonObject
      assertEquals("run-1:user", user.getValue("idempotencyKey").jsonPrimitive.content)
      val assistant = history.getValue("messages").jsonArray[1].jsonObject
      assertEquals("run-1", assistant.getValue("runId").jsonPrimitive.content)
      assertEquals("gpt-test", assistant.getValue("model").jsonPrimitive.content)
      assertNull(history["inFlightRun"])
    }

  @Test
  fun theProviderRequestIsPlainTextWithTheStoredKeyAndPriorTurns() =
    runTest {
      val fixture = Fixture(this, config)
      fixture.send("first", "run-1")
      runCurrent()
      fixture.send("second", "run-2")
      runCurrent()
      val second = fixture.streamer.requests[1]
      assertEquals("https://api.example.com/v1/chat/completions", second.url.toString())
      assertEquals("Bearer sk-secret-123", second.header("Authorization"))
      assertEquals("sk-secret-123", fixture.streamer.apiKeys[1])
      val body = Json.parseToJsonElement(second.bodyText()).jsonObject
      assertEquals(setOf("model", "stream", "messages"), body.keys)
      val turns = body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content }
      assertEquals(listOf("user" to "first", "assistant" to "ok", "user" to "second"), turns)
    }

  @Test
  fun aProviderFailureEmitsAnErrorAndPersistsNoAssistantTurn() =
    runTest {
      val fixture = Fixture(this, config, script = { flowOf(LocalStreamEvent.StreamError(LocalChatFailure(LocalChatFailureKind.Unauthorized, httpStatus = 401))) })
      fixture.send("Hi", "run-1")
      runCurrent()
      assertEquals(listOf("error"), fixture.states("run-1"))
      val message = fixture.events.single().getValue("errorMessage").jsonPrimitive.content
      assertTrue(message, message.contains("API key"))
      assertFalse(message.contains("sk-secret"))
      assertEquals(listOf("user"), fixture.call("chat.history", "sessionKey" to main).roles())
    }

  @Test
  fun aPartialReplyBeforeAnErrorIsKeptButLeftOutOfLaterContext() =
    runTest {
      var failing = true
      val fixture =
        Fixture(
          this,
          config,
          script = {
            if (failing) {
              flowOf(LocalStreamEvent.Delta("par"), LocalStreamEvent.StreamError(LocalChatFailure(LocalChatFailureKind.Timeout)))
            } else {
              flowOf(LocalStreamEvent.Delta("fine"), LocalStreamEvent.Done)
            }
          },
        )
      fixture.send("one", "run-1")
      runCurrent()
      val history = fixture.call("chat.history", "sessionKey" to main)
      assertEquals(listOf("one", "par"), history.texts())
      assertEquals("error", history.getValue("messages").jsonArray[1].jsonObject.getValue("stopReason").jsonPrimitive.content)
      failing = false
      fixture.send("two", "run-2")
      runCurrent()
      val body = Json.parseToJsonElement(fixture.streamer.requests[1].bodyText()).jsonObject
      assertEquals(listOf("one", "two"), body.getValue("messages").jsonArray.map { it.jsonObject.getValue("content").jsonPrimitive.content })
    }

  @Test
  fun abortCancelsTheRunAndKeepsTheTextSoFar() =
    runTest {
      val fixture =
        Fixture(
          this,
          config,
          script = {
            flow {
              emit(LocalStreamEvent.Delta("Hel"))
              awaitCancellation()
            }
          },
        )
      fixture.send("Hi", "run-1")
      runCurrent()
      assertEquals(listOf("delta"), fixture.states("run-1"))
      // While streaming, history reports the run so the controller never times it out.
      val inFlight = fixture.call("chat.history", "sessionKey" to main).getValue("inFlightRun").jsonObject
      assertEquals("run-1", inFlight.getValue("runId").jsonPrimitive.content)
      assertEquals("Hel", inFlight.getValue("text").jsonPrimitive.content)
      val abort = fixture.call("chat.abort", "sessionKey" to main, "runId" to "run-1")
      assertEquals("true", abort.getValue("aborted").jsonPrimitive.content)
      runCurrent()
      assertEquals(listOf("delta", "aborted"), fixture.states("run-1"))
      val history = fixture.call("chat.history", "sessionKey" to main)
      assertEquals(listOf("Hi", "Hel"), history.texts())
      assertEquals("aborted", history.getValue("messages").jsonArray[1].jsonObject.getValue("stopReason").jsonPrimitive.content)
      assertNull(history["inFlightRun"])
    }

  @Test
  fun replayingASendWithTheSameKeyNeverDuplicatesTheTurn() =
    runTest {
      val fixture = Fixture(this, config)
      fixture.send("Hi", "run-1")
      runCurrent()
      val replay = fixture.send("Hi", "run-1")
      assertEquals("ok", replay.getValue("status").jsonPrimitive.content)
      runCurrent()
      assertEquals(1, fixture.streamer.requests.size)
      assertEquals(listOf("user", "assistant"), fixture.call("chat.history", "sessionKey" to main).roles())
    }

  @Test
  fun attachmentsAreRefusedInsteadOfSilentlyDropped() =
    runTest {
      val fixture = Fixture(this, config)
      val failure =
        runCatching {
          fixture.call(
            "chat.send",
            "sessionKey" to main,
            "message" to "see this",
            "idempotencyKey" to "run-1",
            "attachments" to JsonArray(listOf(buildJsonObject { put("type", "image") })),
          )
        }.exceptionOrNull()
      assertTrue(failure is LocalChatUnavailableException)
      assertTrue(failure?.message.orEmpty().contains("Gateway"))
      assertTrue(fixture.streamer.requests.isEmpty())
      assertTrue(fixture.conversations.messages(main).isEmpty())
    }

  @Test
  fun gatewayOnlyMethodsAreReportedAsUnavailableNotFaked() =
    runTest {
      val fixture = Fixture(this, config)
      val failure = runCatching { fixture.call("sessions.branches.list") }.exceptionOrNull()
      assertTrue(failure is LocalChatUnavailableException)
      // The controller treats exactly this phrasing as "this feature is unsupported".
      assertTrue(failure?.message.orEmpty().contains("unknown method: sessions.branches.list"))
      assertTrue(runCatching { fixture.call("sessions.patch", "key" to main, "pinned" to true) }.exceptionOrNull() is LocalChatUnavailableException)
      // The whole surface of the local route: chat, sessions, and health. Nothing that executes or reads the device.
      assertEquals(
        setOf("health", "chat.metadata", "models.list", "sessions.list", "sessions.describe", "sessions.create", "sessions.patch", "sessions.delete", "chat.history", "chat.send", "chat.abort"),
        LOCAL_ADVERTISED_METHODS,
      )
    }

  @Test
  fun sessionsCanBeCreatedListedRenamedAndDeleted() =
    runTest {
      val fixture = Fixture(this, config)
      val created = fixture.call("sessions.create").getValue("key").jsonPrimitive.content
      assertEquals("agent:local:chat-generatedid", created)
      fixture.send("Plan a trip to Lisbon", "run-1", sessionKey = created)
      runCurrent()
      val listed = fixture.call("sessions.list", "agentId" to "local").getValue("sessions").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }
      assertEquals(setOf(main, created), listed.toSet())
      val titled = fixture.call("sessions.describe", "key" to created).getValue("session").jsonObject
      assertEquals("Plan a trip to Lisbon", titled.getValue("displayName").jsonPrimitive.content)
      fixture.call("sessions.patch", "key" to created, "label" to "Trip")
      assertEquals("Trip", fixture.call("sessions.describe", "key" to created).getValue("session").jsonObject.getValue("displayName").jsonPrimitive.content)
      assertEquals(1, fixture.call("sessions.list", "search" to "tri").getValue("sessions").jsonArray.size)
      assertEquals("true", fixture.call("sessions.delete", "key" to created).getValue("deleted").jsonPrimitive.content)
      assertTrue(fixture.conversations.messages(created).isEmpty())
      assertEquals(listOf(main), fixture.call("sessions.list").getValue("sessions").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content })
    }

  @Test
  fun aTamperedCleartextEndpointNeverReachesTheNetwork() =
    runTest {
      val fixture = Fixture(this, LocalModeConfig("http://api.example.com/v1", "gpt-test"))
      fixture.send("Hi", "run-1")
      runCurrent()
      assertTrue(fixture.streamer.requests.isEmpty())
      assertEquals(listOf("error"), fixture.states("run-1"))
    }

  @Test
  fun removingAllDataStopsRunsAndClearsTheConversations() =
    runTest {
      val fixture =
        Fixture(
          this,
          config,
          script = {
            flow {
              emit(LocalStreamEvent.Delta("Hel"))
              awaitCancellation()
            }
          },
        )
      fixture.send("Hi", "run-1")
      runCurrent()
      fixture.backend.removeAllData()
      assertTrue(fixture.conversations.sessions().isEmpty())
      assertTrue(fixture.conversations.messages(main).isEmpty())
      assertEquals("aborted", fixture.events.last().getValue("state").jsonPrimitive.content)
    }
}
