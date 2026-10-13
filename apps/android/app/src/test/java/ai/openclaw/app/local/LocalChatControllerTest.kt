package ai.openclaw.app.local

import ai.openclaw.app.chat.ChatCacheScope
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatController
import ai.openclaw.app.chat.createChatController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Boundary proof for the chosen seam: the real [ChatController], with its real outbox, runs plain
 * chat against [LocalChatBackend] through the same request/event contract it uses for a Gateway.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LocalChatControllerTest {
  private class Fixture(
    test: TestScope,
    streamer: ScriptedStreamer,
  ) {
    val store = FakeLocalModeStore(config = LocalModeConfig("https://api.example.com/v1", "gpt-test"), apiKey = "sk-test", active = true)
    val conversations = InMemoryLocalConversationStore()
    lateinit var controller: ChatController
    private val backend =
      LocalChatBackend(
        scope = test.backgroundScope,
        modeStore = store,
        conversations = conversations,
        client = streamer,
        emitEvent = { event, payload -> controller.handleGatewayEvent(event, payload) },
      )
    private val route = LocalChatRoute(backend, store)

    init {
      controller =
        test.backgroundScope.createChatController(
          cacheScope = { ChatCacheScope(LOCAL_ROUTE_ID, 1L) },
          gatewayAdvertisesMethod = { it in LOCAL_ADVERTISED_METHODS },
          gatewayAdvertisesCapability = { it in LOCAL_ADVERTISED_CAPABILITIES },
          captureRequestLease = route::captureLease,
          requestGateway = backend::request,
        )
    }

    val owner = ChatComposerOwner(LOCAL_ROUTE_ID, LOCAL_AGENT_ID, LOCAL_MAIN_SESSION_KEY)
  }

  @Test
  fun plainChatStreamsAndSettlesThroughTheExistingController() =
    runTest {
      val streamer = ScriptedStreamer { flowOf(LocalStreamEvent.Delta("Hel"), LocalStreamEvent.Delta("lo"), LocalStreamEvent.Done) }
      val fixture = Fixture(this, streamer)
      val controller = fixture.controller
      controller.applyMainSessionKey(LOCAL_MAIN_SESSION_KEY)
      runCurrent()
      assertTrue("health must come from the local route", controller.healthOk.value)
      assertEquals("local/gpt-test", controller.modelCatalog.value.single().let { "${it.provider}/${it.id}" })

      assertTrue(controller.sendMessageAwaitAcceptance("Hi", "off", emptyList(), fixture.owner, idempotencyKey = "run-1"))
      runCurrent()

      assertEquals(listOf("user", "assistant"), controller.messages.value.map { it.role })
      assertEquals("Hello", controller.messages.value.last().content.first().text)
      assertEquals(0, controller.pendingRunCount.value)
      assertNull(controller.streamingAssistantText.value)
      assertNull(controller.errorText.value)
      assertTrue("a delivered send must retire its outbox row", controller.outboxItems.value.isEmpty())
      assertEquals(1, streamer.requests.size)
    }

  @Test
  fun aProviderErrorReachesTheChatBannerWithoutCrashingTheTurn() =
    runTest {
      val streamer = ScriptedStreamer { flowOf(LocalStreamEvent.StreamError(LocalChatFailure(LocalChatFailureKind.RateLimited, httpStatus = 429))) }
      val fixture = Fixture(this, streamer)
      val controller = fixture.controller
      controller.applyMainSessionKey(LOCAL_MAIN_SESSION_KEY)
      runCurrent()
      assertTrue(controller.sendMessageAwaitAcceptance("Hi", "off", emptyList(), fixture.owner, idempotencyKey = "run-1"))
      runCurrent()
      assertEquals("The provider is limiting requests. Wait a moment and try again.", controller.errorText.value)
      assertEquals(0, controller.pendingRunCount.value)
      assertFalse(controller.messages.value.any { it.role == "assistant" })
    }

  @Test
  fun newChatCreatesAnIndependentLocalSession() =
    runTest {
      val fixture = Fixture(this, ScriptedStreamer { flowOf(LocalStreamEvent.Delta("ok"), LocalStreamEvent.Done) })
      val controller = fixture.controller
      controller.applyMainSessionKey(LOCAL_MAIN_SESSION_KEY)
      runCurrent()
      assertTrue(controller.startNewChatAwait())
      runCurrent()
      assertTrue(controller.sessionKey.value, controller.sessionKey.value.startsWith("agent:local:chat-"))
      assertEquals(setOf(LOCAL_MAIN_SESSION_KEY, controller.sessionKey.value), fixture.conversations.sessions().map { it.key }.toSet())
    }
}
