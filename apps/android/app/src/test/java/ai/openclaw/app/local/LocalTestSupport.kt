package ai.openclaw.app.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okio.Buffer

/** Credential store fake: same contract as the encrypted store, with the key visible for assertions. */
internal class FakeLocalModeStore(
  config: LocalModeConfig? = null,
  apiKey: String? = null,
  active: Boolean = false,
) : LocalModeStore {
  private val configState = MutableStateFlow(config)
  private val activeState = MutableStateFlow(active && config != null)
  private val hasKeyState = MutableStateFlow(apiKey != null)

  var storedKey: String? = apiKey
    private set

  override val config: StateFlow<LocalModeConfig?> = configState.asStateFlow()
  override val active: StateFlow<Boolean> = activeState.asStateFlow()
  override val hasApiKey: StateFlow<Boolean> = hasKeyState.asStateFlow()

  override fun loadApiKey(): String? = storedKey

  override fun save(
    config: LocalModeConfig,
    apiKey: LocalApiKeyChange,
  ): Boolean {
    configState.value = config
    when (apiKey) {
      LocalApiKeyChange.Keep -> Unit
      LocalApiKeyChange.Clear -> storedKey = null
      is LocalApiKeyChange.Set -> storedKey = apiKey.key.trim().takeIf { it.isNotEmpty() }
    }
    hasKeyState.value = storedKey != null
    return true
  }

  override fun setActive(active: Boolean): Boolean {
    if (active && configState.value == null) return false
    activeState.value = active
    return true
  }

  override fun remove(): Boolean {
    configState.value = null
    storedKey = null
    activeState.value = false
    hasKeyState.value = false
    return true
  }
}

internal class InMemoryLocalConversationStore : LocalConversationStore {
  private val sessions = linkedMapOf<String, LocalSessionRecord>()
  private val messages = linkedMapOf<String, MutableList<LocalMessageRecord>>()

  override suspend fun sessions(): List<LocalSessionRecord> = sessions.values.sortedByDescending { it.updatedAtMs }

  override suspend fun session(key: String): LocalSessionRecord? = sessions[key]

  override suspend fun upsertSession(record: LocalSessionRecord) {
    sessions[record.key] = record
  }

  override suspend fun messages(sessionKey: String): List<LocalMessageRecord> = messages[sessionKey].orEmpty().toList()

  override suspend fun appendMessage(record: LocalMessageRecord): LocalMessageRecord {
    val list = messages.getOrPut(record.sessionKey) { mutableListOf() }
    return record.copy(seq = list.size + 1L).also { list += it }
  }

  override suspend fun deleteSession(key: String) {
    sessions.remove(key)
    messages.remove(key)
  }

  override suspend fun clear() {
    sessions.clear()
    messages.clear()
  }
}

/** Scripts the provider side of a completion and records every request the backend builds. */
internal class ScriptedStreamer(
  var script: (Request) -> Flow<LocalStreamEvent>,
) : LocalChatStreamer {
  val requests = mutableListOf<Request>()
  val apiKeys = mutableListOf<String?>()

  override fun stream(
    request: Request,
    apiKey: String?,
  ): Flow<LocalStreamEvent> {
    requests += request
    apiKeys += apiKey
    return script(request)
  }
}

internal fun Request.bodyText(): String =
  Buffer().also { buffer -> checkNotNull(body).writeTo(buffer) }.readUtf8()
