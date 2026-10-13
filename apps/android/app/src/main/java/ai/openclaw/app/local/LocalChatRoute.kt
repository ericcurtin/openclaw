package ai.openclaw.app.local

import ai.openclaw.app.chat.ChatCacheScope
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The one seam [ChatController][ai.openclaw.app.chat.ChatController] needs to chat without a Gateway
 * socket: while [isActive], its Gateway-shaped requests go here instead of to the operator session.
 */
internal interface LocalChatTransport {
  fun isActive(): Boolean

  suspend fun request(
    method: String,
    paramsJson: String?,
  ): String

  /** A lease fenced to this activation, or null when inactive or scoped to another route. */
  fun captureLease(gatewayScope: ChatCacheScope?): GatewaySession.RequestLease?
}

/**
 * The runtime on/off switch for the local route plus the lease fencing that retires stale results.
 * The persisted [LocalModeStore.active] flag is the single source of truth, so a restart reopens in
 * the route the user left.
 */
internal class LocalChatRoute(
  private val backend: LocalChatBackend,
  private val store: LocalModeStore,
) : LocalChatTransport {
  private val epoch = AtomicLong(0)

  val active: StateFlow<Boolean> = store.active

  /** Every activation gets a new epoch so a lease captured before a toggle can never commit after it. */
  fun activate(): Boolean = switchTo(true)

  /** Returns true when the route was active and now is not. */
  fun deactivate(): Boolean = switchTo(false)

  private fun switchTo(value: Boolean): Boolean =
    synchronized(epoch) {
      if (store.active.value == value) return@synchronized false
      epoch.incrementAndGet()
      // The in-memory route follows the user's action even if the write fails; the worst case is
      // reopening in the previous route after a restart. Activation without a saved setup is refused.
      store.setActive(value)
      store.active.value == value
    }

  override fun isActive(): Boolean = store.active.value

  override suspend fun request(
    method: String,
    paramsJson: String?,
  ): String = backend.request(method, paramsJson)

  override fun captureLease(gatewayScope: ChatCacheScope?): GatewaySession.RequestLease? {
    if (!isActive()) return null
    if (gatewayScope != null && gatewayScope.gatewayId != LOCAL_ROUTE_ID) return null
    val capturedEpoch = epoch.get()
    return GatewaySession.RequestLease(
      endpointStableId = LOCAL_ROUTE_ID,
      isCurrentImpl = { isActive() && epoch.get() == capturedEpoch },
      advertisedMethods = LOCAL_ADVERTISED_METHODS,
    ) { method, paramsJson, _, withEnqueue ->
      // There is no socket queue to wait on, so admission is immediate.
      withEnqueue {}
      backend.request(method, paramsJson)
    }
  }
}
