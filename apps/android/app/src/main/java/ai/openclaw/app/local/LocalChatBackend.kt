package ai.openclaw.app.local

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.takeUtf16Safe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Gateway methods the local route answers. Everything else is reported as unavailable, never faked. */
internal val LOCAL_ADVERTISED_METHODS: Set<String> =
  setOf(
    "health",
    "chat.metadata",
    "models.list",
    "sessions.list",
    "sessions.describe",
    "sessions.create",
    "sessions.patch",
    "sessions.delete",
    "chat.history",
    "chat.send",
    "chat.abort",
  )

internal val LOCAL_ADVERTISED_CAPABILITIES: Set<String> = setOf("session-scoped-model-catalog")

private const val DELTA_EMIT_INTERVAL_MS = 50L
private const val DERIVED_TITLE_MAX_CHARS = 48
private val PATCH_ENVELOPE_FIELDS = setOf("key", "agentId", "expectedSessionId", "expectedMarkedUnreadAt", "label", "autoLabel")

/** A request the local route cannot serve because it needs a Gateway. The message is user-facing. */
internal class LocalChatUnavailableException(
  message: String,
) : IllegalStateException(message)

/**
 * Serves the small set of Gateway chat RPCs that plain text chat needs, backed by a direct provider
 * call and the on-device [LocalConversationStore]. [ChatController][ai.openclaw.app.chat.ChatController]
 * talks to it through the same request/event seam it uses for a Gateway socket, so the existing
 * transcript, composer, streaming, outbox, and cache code is reused unchanged.
 *
 * There are no tools: requests carry only message text, and attachments are refused.
 */
internal class LocalChatBackend(
  private val scope: CoroutineScope,
  private val modeStore: LocalModeStore,
  private val conversations: LocalConversationStore,
  private val client: LocalChatStreamer,
  private val emitEvent: (event: String, payloadJson: String) -> Unit,
  private val nowMs: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private class ActiveRun(
    val sessionKey: String,
    val runId: String,
  ) {
    @Volatile var job: Job? = null

    @Volatile var started = false
    private val text = StringBuilder()

    fun append(piece: String) = synchronized(text) { text.append(piece) }

    fun snapshot(): String = synchronized(text) { text.toString() }
  }

  private val activeRuns = ConcurrentHashMap<String, ActiveRun>()
  private val sessionLocks = ConcurrentHashMap<String, Mutex>()

  suspend fun request(
    method: String,
    paramsJson: String?,
  ): String {
    val params = paramsJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: JsonObject(emptyMap())
    val result =
      when (method) {
        "health" -> health()
        "chat.metadata" -> buildJsonObject { put("commands", JsonArray(emptyList())) }
        "models.list" -> modelsList()
        "sessions.list" -> sessionsList(params)
        "sessions.describe" -> sessionsDescribe(params)
        "sessions.create" -> sessionsCreate()
        "sessions.patch" -> sessionsPatch(params)
        "sessions.delete" -> sessionsDelete(params)
        "chat.history" -> chatHistory(params)
        "chat.send" -> chatSend(params)
        "chat.abort" -> chatAbort(params)
        // Keeps the "unknown method" phrasing the controller already treats as an unsupported feature.
        else -> throw LocalChatUnavailableException(nativeString("This needs a Gateway (unknown method: \$method). Connect one in Settings → Gateway.", method))
      }
    return result.toString()
  }

  /** Cancels every in-flight run and waits for each to persist its partial reply. */
  suspend fun cancelRuns() {
    val jobs = activeRuns.values.mapNotNull { it.job }
    jobs.forEach { it.cancel() }
    jobs.joinAll()
  }

  /** Stops all runs, then deletes every local conversation. */
  suspend fun removeAllData() {
    cancelRuns()
    conversations.clear()
  }

  private fun needsGateway() = LocalChatUnavailableException(nativeString("This needs a Gateway. Connect one in Settings → Gateway."))

  private fun health(): JsonObject {
    if (modeStore.config.value == null) throw LocalChatException(LocalChatFailure(LocalChatFailureKind.NotConfigured))
    return buildJsonObject { put("ok", true) }
  }

  private fun modelsList(): JsonObject {
    val config = modeStore.config.value
    return buildJsonObject {
      put(
        "models",
        buildJsonArray {
          if (config != null) {
            add(
              buildJsonObject {
                put("id", config.model)
                put("name", config.model)
                put("provider", LOCAL_PROVIDER)
                put("available", true)
                put("input", buildJsonArray { add(JsonPrimitive("text")) })
                put("reasoning", false)
                // Plain chat only: the catalog states it so no surface offers tool-backed features.
                put("supportsTools", false)
              },
            )
          }
        },
      )
    }
  }

  private suspend fun ensureMainSession(): LocalSessionRecord =
    conversations.session(LOCAL_MAIN_SESSION_KEY) ?: nowMs().let { now ->
      LocalSessionRecord(LOCAL_MAIN_SESSION_KEY, title = null, createdAtMs = now, updatedAtMs = now).also { conversations.upsertSession(it) }
    }

  private fun sessionJson(record: LocalSessionRecord): JsonObject {
    val config = modeStore.config.value
    val title = record.title ?: if (record.key == LOCAL_MAIN_SESSION_KEY) nativeString("Local chat") else null
    return buildJsonObject {
      put("key", record.key)
      put("sessionId", record.key)
      put("agentId", LOCAL_AGENT_ID)
      put("updatedAt", record.updatedAtMs)
      put("isMain", record.key == LOCAL_MAIN_SESSION_KEY)
      if (title != null) {
        put("displayName", title)
        put("derivedTitle", title)
      }
      if (config != null) {
        put("modelProvider", LOCAL_PROVIDER)
        put("model", config.model)
      }
    }
  }

  private suspend fun sessionsList(params: JsonObject): JsonObject {
    ensureMainSession()
    val archived = (params["archived"] as? JsonPrimitive)?.booleanOrNull == true
    val search = params.string("search")?.trim()?.takeIf { it.isNotEmpty() }
    val limit = (params["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
    val all =
      if (archived) {
        emptyList<LocalSessionRecord>()
      } else {
        conversations.sessions().filter { record ->
          search == null || (record.title ?: "").contains(search, ignoreCase = true)
        }
      }
    val page = if (limit == null) all else all.take(limit)
    return buildJsonObject {
      put("sessions", JsonArray(page.map { sessionJson(it) }))
      put("count", page.size)
      put("totalCount", all.size)
      put("hasMore", page.size < all.size)
    }
  }

  private suspend fun sessionsDescribe(params: JsonObject): JsonObject {
    val key = params.string("key") ?: params.string("sessionKey")
    val record = key?.let { if (it == LOCAL_MAIN_SESSION_KEY) ensureMainSession() else conversations.session(it) }
    return buildJsonObject { put("session", record?.let { sessionJson(it) } ?: JsonNull) }
  }

  private suspend fun sessionsCreate(): JsonObject {
    val now = nowMs()
    val record = LocalSessionRecord("agent:$LOCAL_AGENT_ID:chat-${newId().replace("-", "").take(12)}", title = null, createdAtMs = now, updatedAtMs = now)
    conversations.upsertSession(record)
    return buildJsonObject {
      put("key", record.key)
      put("sessionId", record.key)
      put("session", sessionJson(record))
    }
  }

  private suspend fun sessionsPatch(params: JsonObject): JsonObject {
    val key = params.string("key") ?: throw needsGateway()
    // Renaming works on the phone. Pinning, archiving, snoozing, colors, and model or permission
    // changes belong to a Gateway session, so they are refused instead of silently ignored.
    if (params.keys.any { it !in PATCH_ENVELOPE_FIELDS }) throw needsGateway()
    if ("label" in params) {
      val record = (if (key == LOCAL_MAIN_SESSION_KEY) ensureMainSession() else conversations.session(key)) ?: return buildJsonObject { put("ok", false) }
      val label = (params["label"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
      conversations.upsertSession(record.copy(title = label?.takeUtf16Safe(DERIVED_TITLE_MAX_CHARS * 2)))
    }
    return buildJsonObject {
      put("ok", true)
      put("key", key)
    }
  }

  private suspend fun sessionsDelete(params: JsonObject): JsonObject {
    val key = params.string("key") ?: throw needsGateway()
    activeRuns.values.filter { it.sessionKey == key }.mapNotNull { it.job }.let { jobs ->
      jobs.forEach { it.cancel() }
      jobs.joinAll()
    }
    conversations.deleteSession(key)
    return buildJsonObject { put("deleted", true) }
  }

  private suspend fun chatHistory(params: JsonObject): JsonObject {
    val key = params.string("sessionKey") ?: params.string("key") ?: LOCAL_MAIN_SESSION_KEY
    val record =
      if (key == LOCAL_MAIN_SESSION_KEY) {
        ensureMainSession()
      } else {
        conversations.session(key) ?: LocalSessionRecord(key, title = null, createdAtMs = nowMs(), updatedAtMs = nowMs())
      }
    val config = modeStore.config.value
    val messages = conversations.messages(key)
    val inFlight =
      activeRuns.values
        .filter { it.sessionKey == key }
        .let { runs -> runs.firstOrNull { it.started } ?: runs.firstOrNull() }
    return buildJsonObject {
      put("sessionId", record.key)
      put("thinkingLevel", "off")
      put("sessionInfo", sessionJson(record))
      if (config != null) {
        put(
          "defaults",
          buildJsonObject {
            put("model", config.model)
            put("modelProvider", LOCAL_PROVIDER)
          },
        )
      }
      put("messages", JsonArray(messages.map { historyMessage(it, config) }))
      if (inFlight != null) {
        put(
          "inFlightRun",
          buildJsonObject {
            put("runId", inFlight.runId)
            put("text", inFlight.snapshot())
          },
        )
      }
    }
  }

  private fun historyMessage(
    message: LocalMessageRecord,
    config: LocalModeConfig?,
  ): JsonObject =
    buildJsonObject {
      put("role", message.role)
      put("content", textContent(message.text))
      put("timestamp", message.timestampMs)
      message.idempotencyKey?.let { put("idempotencyKey", it) }
      message.runId?.let { put("runId", it) }
      message.stopReason?.let { put("stopReason", it) }
      if (message.role == "assistant" && config != null) {
        put("provider", LOCAL_PROVIDER)
        put("model", config.model)
      }
    }

  private fun textContent(text: String): JsonArray =
    buildJsonArray {
      add(
        buildJsonObject {
          put("type", "text")
          put("text", text)
        },
      )
    }

  private suspend fun chatSend(params: JsonObject): JsonObject {
    // Local mode sends text only. Attachments would be dropped silently, so refuse them loudly.
    val attachments = params["attachments"] as? JsonArray
    if (attachments != null && attachments.isNotEmpty()) {
      throw LocalChatUnavailableException(nativeString("Attachments need a Gateway. Connect one in Settings → Gateway to send them."))
    }
    if (modeStore.config.value == null) throw LocalChatException(LocalChatFailure(LocalChatFailureKind.NotConfigured))
    val key = params.string("sessionKey") ?: params.string("key") ?: LOCAL_MAIN_SESSION_KEY
    val text = params.string("message")?.trim().orEmpty()
    if (text.isEmpty()) throw needsGateway()
    val runId = params.string("idempotencyKey")?.trim()?.takeIf { it.isNotEmpty() } ?: newId()

    // The outbox may replay a send after an ambiguous outcome; the idempotency key makes that safe.
    val existing = conversations.messages(key).any { it.idempotencyKey == "$runId:user" }
    if (existing) {
      return ack(runId, if (activeRuns.containsKey(runId)) "in_flight" else "ok")
    }
    val session = if (key == LOCAL_MAIN_SESSION_KEY) ensureMainSession() else conversations.session(key)
    val now = nowMs()
    val record = session ?: LocalSessionRecord(key, title = null, createdAtMs = now, updatedAtMs = now)
    val withTitle = if (record.title == null && key != LOCAL_MAIN_SESSION_KEY) record.copy(title = deriveTitle(text)) else record
    conversations.upsertSession(withTitle.copy(updatedAtMs = now))
    val stored =
      conversations.appendMessage(
        LocalMessageRecord(
          sessionKey = key,
          seq = 0,
          role = "user",
          text = text,
          timestampMs = now,
          idempotencyKey = "$runId:user",
          runId = runId,
          stopReason = null,
        ),
      )
    val run = ActiveRun(sessionKey = key, runId = runId)
    activeRuns[runId] = run
    run.job = scope.launch { executeRun(run, stored.seq) }
    return ack(runId, "started")
  }

  private fun ack(
    runId: String,
    status: String,
  ): JsonObject =
    buildJsonObject {
      put("runId", runId)
      put("status", status)
    }

  private fun chatAbort(params: JsonObject): JsonObject {
    val runId = params.string("runId")
    val key = params.string("sessionKey") ?: params.string("key")
    val targets = activeRuns.values.filter { run -> if (runId != null) run.runId == runId else key == null || run.sessionKey == key }
    targets.forEach { it.job?.cancel() }
    return buildJsonObject {
      put("ok", true)
      put("aborted", targets.isNotEmpty())
    }
  }

  private fun deriveTitle(text: String): String? =
    text
      .lineSequence()
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() }
      ?.replace(Regex("\\s+"), " ")
      ?.takeUtf16Safe(DERIVED_TITLE_MAX_CHARS)

  private suspend fun executeRun(
    run: ActiveRun,
    userSeq: Long,
  ) {
    val lock = sessionLocks.computeIfAbsent(run.sessionKey) { Mutex() }
    try {
      var terminal: LocalStreamEvent? = null
      // Runs in one session execute in order, like the Gateway's queued follow-ups.
      lock.withLock {
        run.started = true
        val prepared = prepareRequest(run.sessionKey, userSeq)
        var lastEmitMs = 0L
        client.stream(prepared.request, prepared.apiKey).collect { event ->
          if (event is LocalStreamEvent.Delta) {
            run.append(event.text)
            val now = nowMs()
            if (now - lastEmitMs >= DELTA_EMIT_INTERVAL_MS) {
              lastEmitMs = now
              emitEvent("chat", chatEvent(run, "delta", text = run.snapshot()))
            }
          } else {
            terminal = event
          }
        }
      }
      val end = terminal
      finishRun(run, if (end is LocalStreamEvent.StreamError) end.failure else if (end == LocalStreamEvent.Done) null else LocalChatFailure(LocalChatFailureKind.Unknown))
    } catch (e: CancellationException) {
      withContext(NonCancellable) { abortRun(run) }
      throw e
    } catch (e: LocalChatException) {
      finishRun(run, e.failure)
    } catch (_: Exception) {
      // Never surface exception text: it can carry URLs or header values.
      finishRun(run, LocalChatFailure(LocalChatFailureKind.Unknown))
    } finally {
      activeRuns.remove(run.runId)
    }
  }

  private class PreparedRequest(
    val request: okhttp3.Request,
    val apiKey: String?,
  )

  private suspend fun prepareRequest(
    sessionKey: String,
    userSeq: Long,
  ): PreparedRequest {
    val config = modeStore.config.value ?: throw LocalChatException(LocalChatFailure(LocalChatFailureKind.NotConfigured))
    // Re-validate at use so a tampered or stale saved URL can never reach the network in cleartext.
    val baseUrl =
      (parseLocalEndpoint(config.baseUrl) as? LocalEndpointResult.Valid)?.baseUrl
        ?: throw LocalChatException(LocalChatFailure(LocalChatFailureKind.InvalidEndpoint))
    val apiKey = modeStore.loadApiKey()
    val turns =
      conversations
        .messages(sessionKey)
        .filter { it.seq <= userSeq && it.stopReason != "error" }
        .map { LocalChatTurn(role = it.role, text = it.text) }
    return PreparedRequest(buildLocalChatRequest(baseUrl, config.model, apiKey, turns), apiKey)
  }

  private suspend fun finishRun(
    run: ActiveRun,
    failure: LocalChatFailure?,
  ) {
    val text = run.snapshot()
    if (failure == null) {
      val saved = persistAssistant(run, text, stopReason = null)
      // Retire the run before its terminal event: the controller refreshes history on that event,
      // and a history response that still listed this run would re-adopt it as active.
      activeRuns.remove(run.runId)
      if (!saved) return emitSaveFailure(run)
      emitEvent("chat", chatEvent(run, "final", text = text))
    } else {
      if (text.isNotEmpty()) persistAssistant(run, text, stopReason = "error")
      activeRuns.remove(run.runId)
      emitEvent("chat", chatEvent(run, "error", errorMessage = failure.userMessage()))
    }
  }

  private suspend fun abortRun(run: ActiveRun) {
    val text = run.snapshot()
    if (text.isNotEmpty()) persistAssistant(run, text, stopReason = "aborted")
    activeRuns.remove(run.runId)
    emitEvent("chat", chatEvent(run, "aborted"))
  }

  private fun emitSaveFailure(run: ActiveRun) {
    emitEvent("chat", chatEvent(run, "error", errorMessage = nativeString("Could not save this reply on the phone.")))
  }

  /** Returns false when the conversation could not be written, so the caller can say so. */
  private suspend fun persistAssistant(
    run: ActiveRun,
    text: String,
    stopReason: String?,
  ): Boolean {
    if (text.isEmpty()) return false
    return try {
      val now = nowMs()
      conversations.appendMessage(
        LocalMessageRecord(run.sessionKey, 0, "assistant", text, now, idempotencyKey = null, runId = run.runId, stopReason = stopReason),
      )
      conversations.session(run.sessionKey)?.let { conversations.upsertSession(it.copy(updatedAtMs = now)) }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      false
    }
  }

  private fun chatEvent(
    run: ActiveRun,
    state: String,
    text: String? = null,
    errorMessage: String? = null,
  ): String =
    buildJsonObject {
      put("runId", run.runId)
      put("sessionKey", run.sessionKey)
      put("state", state)
      if (text != null) {
        put(
          "message",
          buildJsonObject {
            put("role", "assistant")
            put("content", textContent(text))
          },
        )
      }
      if (errorMessage != null) put("errorMessage", errorMessage)
    }.toString()

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

  private companion object {
    const val LOCAL_PROVIDER = "local"
  }
}
