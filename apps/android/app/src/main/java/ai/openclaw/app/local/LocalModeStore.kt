package ai.openclaw.app.local

import ai.openclaw.app.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable route identity for local mode. It never collides with Gateway ids (`manual|…`, discovery ids). */
internal const val LOCAL_ROUTE_ID = "local|chat"

/** The one conversation that always exists, shaped like a Gateway agent-scoped main session key. */
internal const val LOCAL_MAIN_SESSION_KEY = "agent:local:main"

internal const val LOCAL_AGENT_ID = "local"

/**
 * Non-secret local-mode setup. The API key is deliberately not part of this type so it can never be
 * serialized, logged, or copied together with the endpoint.
 */
@Serializable
internal data class LocalModeConfig(
  val baseUrl: String,
  val model: String,
)

/** What to do with the stored API key when the setup is saved. */
internal sealed interface LocalApiKeyChange {
  /** Leave the stored key as it is, so editing the endpoint never needs the key re-entered. */
  data object Keep : LocalApiKeyChange

  data object Clear : LocalApiKeyChange

  data class Set(
    val key: String,
  ) : LocalApiKeyChange {
    // A generated data-class toString would print the key; keep it out of logs and crash text.
    override fun toString(): String = "Set(key=<redacted>)"
  }
}

/**
 * Persistence for the opt-in local route. Everything lives in the app's encrypted store and is removed
 * together by [remove]; none of it is part of the Gateway registry.
 */
internal interface LocalModeStore {
  val config: StateFlow<LocalModeConfig?>

  /** True while local mode is the focused route; survives restarts so the app reopens in it. */
  val active: StateFlow<Boolean>

  /** Whether a key is stored. The key itself is only ever read by [loadApiKey] at request time. */
  val hasApiKey: StateFlow<Boolean>

  fun loadApiKey(): String?

  /** Saves the endpoint and applies [apiKey] to the stored key in one atomic write. */
  fun save(
    config: LocalModeConfig,
    apiKey: LocalApiKeyChange,
  ): Boolean

  fun setActive(active: Boolean): Boolean

  /** Deletes the endpoint, the key, and the active flag. */
  fun remove(): Boolean
}

private const val CONFIG_KEY = "local.config"
private const val API_KEY_KEY = "local.apiKey"
private const val ACTIVE_KEY = "local.active"

/** Same encrypted-storage pattern as Gateway credentials: androidx.security.crypto via [SecurePrefs]. */
internal class SecurePrefsLocalModeStore(
  private val prefs: SecurePrefs,
) : LocalModeStore {
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()
  private val mutableConfig = MutableStateFlow(readConfig())
  private val mutableActive = MutableStateFlow(readActive(mutableConfig.value))
  private val mutableHasApiKey = MutableStateFlow(loadApiKey() != null)

  override val config: StateFlow<LocalModeConfig?> = mutableConfig.asStateFlow()
  override val active: StateFlow<Boolean> = mutableActive.asStateFlow()
  override val hasApiKey: StateFlow<Boolean> = mutableHasApiKey.asStateFlow()

  override fun loadApiKey(): String? = prefs.getString(API_KEY_KEY)?.trim()?.takeIf { it.isNotEmpty() }

  override fun save(
    config: LocalModeConfig,
    apiKey: LocalApiKeyChange,
  ): Boolean =
    synchronized(lock) {
      val writes = mutableMapOf<String, String?>(CONFIG_KEY to json.encodeToString(LocalModeConfig.serializer(), config))
      when (apiKey) {
        LocalApiKeyChange.Keep -> Unit
        LocalApiKeyChange.Clear -> writes[API_KEY_KEY] = null
        is LocalApiKeyChange.Set -> writes[API_KEY_KEY] = apiKey.key.trim().takeIf { it.isNotEmpty() }
      }
      val committed = prefs.commitSecureStrings(writes)
      if (committed) {
        mutableConfig.value = config
        if (API_KEY_KEY in writes) mutableHasApiKey.value = writes[API_KEY_KEY] != null
      }
      committed
    }

  override fun setActive(active: Boolean): Boolean =
    synchronized(lock) {
      if (active && mutableConfig.value == null) return@synchronized false
      val committed = prefs.commitSecureStrings(mapOf(ACTIVE_KEY to if (active) "true" else null))
      // The running route follows the user's action even when persistence fails; the cost is only
      // that the next launch may reopen in the previous route.
      mutableActive.value = active
      committed
    }

  override fun remove(): Boolean =
    synchronized(lock) {
      val committed = prefs.commitSecureStrings(mapOf(CONFIG_KEY to null, API_KEY_KEY to null, ACTIVE_KEY to null))
      if (committed) {
        mutableConfig.value = null
        mutableActive.value = false
        mutableHasApiKey.value = false
      }
      committed
    }

  private fun readConfig(): LocalModeConfig? =
    prefs
      .getString(CONFIG_KEY)
      ?.let { runCatching { json.decodeFromString(LocalModeConfig.serializer(), it) }.getOrNull() }
      ?.takeIf { it.baseUrl.isNotBlank() && it.model.isNotBlank() }

  // A dangling flag without a saved setup must never route chat to an endpoint that is gone.
  private fun readActive(config: LocalModeConfig?): Boolean = config != null && prefs.getString(ACTIVE_KEY) == "true"
}
