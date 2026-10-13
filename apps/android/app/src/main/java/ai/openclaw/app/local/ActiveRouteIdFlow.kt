package ai.openclaw.app.local

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The chat identity the UI keys drafts and sends by: the focused Gateway's registry id, or
 * [LOCAL_ROUTE_ID] while local mode is focused. [value] reads both sources synchronously, so with
 * local mode off it always equals the registry's active id.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class ActiveRouteIdFlow(
  private val registryActive: StateFlow<String?>,
  private val localActive: StateFlow<Boolean>,
) : StateFlow<String?> {
  override val value: String?
    get() = if (localActive.value) LOCAL_ROUTE_ID else registryActive.value

  override val replayCache: List<String?>
    get() = listOf(value)

  override suspend fun collect(collector: FlowCollector<String?>): Nothing {
    combine(registryActive, localActive) { registryId, local -> if (local) LOCAL_ROUTE_ID else registryId }
      .distinctUntilChanged()
      .collect(collector)
    error("route id flow completed unexpectedly")
  }
}
