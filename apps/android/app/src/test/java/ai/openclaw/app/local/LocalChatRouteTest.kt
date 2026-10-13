package ai.openclaw.app.local

import ai.openclaw.app.chat.ChatCacheScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalChatRouteTest {
  private val config = LocalModeConfig("https://api.example.com/v1", "gpt-test")
  private val scope = ChatCacheScope(LOCAL_ROUTE_ID, 1L)

  private fun route(store: LocalModeStore): LocalChatRoute =
    LocalChatRoute(
      LocalChatBackend(CoroutineScope(SupervisorJob()), store, InMemoryLocalConversationStore(), ScriptedStreamer { emptyFlow() }, { _, _ -> }),
      store,
    )

  @Test
  fun anInactiveRouteServesNoLease() {
    val route = route(FakeLocalModeStore(config = config))
    assertFalse(route.isActive())
    assertNull(route.captureLease(scope))
    assertNull(route.captureLease(null))
  }

  @Test
  fun activationNeedsASavedSetup() {
    val route = route(FakeLocalModeStore())
    assertFalse(route.activate())
    assertFalse(route.isActive())
  }

  @Test
  fun aLeaseIsFencedToOneActivation() {
    val store = FakeLocalModeStore(config = config)
    val route = route(store)
    assertTrue(route.activate())
    val first = checkNotNull(route.captureLease(scope))
    assertEquals(LOCAL_ROUTE_ID, first.endpointStableId)
    assertTrue(first.isCurrent())
    assertTrue(first.supportsMethod("chat.send"))
    assertFalse(first.supportsMethod("exec.approval.list"))
    assertTrue(route.deactivate())
    assertFalse(first.isCurrent())
    assertTrue(route.activate())
    assertFalse("a lease from the earlier activation must stay retired", first.isCurrent())
    assertTrue(checkNotNull(route.captureLease(scope)).isCurrent())
  }

  @Test
  fun aLeaseScopedToAGatewayRouteIsRefused() {
    val route = route(FakeLocalModeStore(config = config, active = true))
    assertNull(route.captureLease(ChatCacheScope("manual|gateway.local|18789", 3L)))
    assertNotNull(route.captureLease(scope))
  }

  @Test
  fun deactivatingAnInactiveRouteChangesNothing() {
    assertFalse(route(FakeLocalModeStore(config = config)).deactivate())
  }

  @Test
  fun aLeaseRequestReachesTheLocalBackend() {
    val store = FakeLocalModeStore(config = config, active = true)
    val lease = checkNotNull(route(store).captureLease(scope))
    var admissions = 0
    val response = runBlocking { lease.request("health", null) { admissions += 1 } }
    assertTrue(response.contains("true"))
    // Admission is immediate: the lease hands its caller's gate exactly one chance to reject.
    assertEquals(1, admissions)
  }

  @Test
  fun theChatIdentityIsTheRegistryIdUntilLocalModeIsFocused() =
    runTest {
      val registry = MutableStateFlow<String?>("manual|gateway.local|18789")
      val local = MutableStateFlow(false)
      val flow = ActiveRouteIdFlow(registry, local)
      assertEquals("manual|gateway.local|18789", flow.value)
      val seen = mutableListOf<String?>()
      val job = launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
      local.value = true
      assertEquals(LOCAL_ROUTE_ID, flow.value)
      registry.value = "manual|other|18789"
      assertEquals(LOCAL_ROUTE_ID, flow.value)
      local.value = false
      assertEquals("manual|other|18789", flow.value)
      job.cancel()
      assertEquals(listOf("manual|gateway.local|18789", LOCAL_ROUTE_ID, "manual|other|18789"), seen)
    }
}
