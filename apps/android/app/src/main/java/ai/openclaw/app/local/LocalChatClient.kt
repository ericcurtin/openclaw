package ai.openclaw.app.local

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val ERROR_BODY_LIMIT_BYTES = 8L * 1024

/**
 * No redirects: a provider endpoint that redirects is misconfigured, and following one could move the
 * bearer key to another origin. The read timeout is per idle gap, sized for slow local hardware that
 * loads a model before its first token.
 */
internal fun defaultLocalChatHttpClient(): OkHttpClient =
  OkHttpClient
    .Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

/** The streaming boundary the backend depends on, so tests can script a provider without a socket. */
internal fun interface LocalChatStreamer {
  fun stream(
    request: Request,
    apiKey: String?,
  ): Flow<LocalStreamEvent>
}

/**
 * Streams one chat completion. The flow emits [LocalStreamEvent.Delta] text pieces and then exactly
 * one terminal event, [LocalStreamEvent.Done] or [LocalStreamEvent.StreamError]. Cancelling the
 * collector cancels the HTTP call and ends the flow without a terminal event.
 */
internal class LocalChatClient(
  private val http: OkHttpClient = defaultLocalChatHttpClient(),
) : LocalChatStreamer {
  override fun stream(
    request: Request,
    apiKey: String?,
  ): Flow<LocalStreamEvent> =
    callbackFlow {
      val call = http.newCall(request)
      call.enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
            if (!call.isCanceled()) trySend(LocalStreamEvent.StreamError(localChatFailureForException(e)))
            close()
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
            response.use { readResponse(it, call, apiKey) { event -> trySend(event).isSuccess } }
            close()
          }
        },
      )
      awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

  private fun readResponse(
    response: Response,
    call: Call,
    apiKey: String?,
    emit: (LocalStreamEvent) -> Boolean,
  ) {
    try {
      val source = response.body.source()
      if (!response.isSuccessful) {
        val body =
          runCatching {
            source.request(ERROR_BODY_LIMIT_BYTES)
            source.buffer.readUtf8(minOf(source.buffer.size, ERROR_BODY_LIMIT_BYTES))
          }.getOrNull()
        emit(LocalStreamEvent.StreamError(localChatFailureForHttpStatus(response.code, body, apiKey)))
        return
      }
      val parser = LocalSseParser()
      var sawText = false

      // Returns true once a terminal event was emitted or the collector went away.
      fun handle(payloads: List<String>): Boolean {
        for (payload in payloads) {
          val decoded =
            decodeOpenAiStreamPayload(payload, apiKey) ?: run {
              emit(LocalStreamEvent.StreamError(LocalChatFailure(LocalChatFailureKind.Malformed)))
              return true
            }
          for (event in decoded) {
            when (event) {
              is LocalStreamEvent.Delta -> {
                sawText = true
                if (!emit(event)) return true
              }

              LocalStreamEvent.Done -> {
                emit(if (sawText) event else emptyCompletion())
                return true
              }

              is LocalStreamEvent.StreamError -> {
                emit(event)
                return true
              }
            }
          }
        }
        return false
      }

      while (true) {
        // Whole lines keep multi-byte characters intact across network reads.
        val line = source.readUtf8Line() ?: break
        if (handle(parser.feed("$line\n"))) return
      }
      if (handle(parser.finish())) return
      emit(if (sawText) LocalStreamEvent.Done else emptyCompletion())
    } catch (e: IOException) {
      // A cancelled collector closes the call on purpose; that is not a provider failure.
      if (!call.isCanceled()) emit(LocalStreamEvent.StreamError(localChatFailureForException(e)))
    } catch (_: Exception) {
      // An exception escaping an OkHttp callback would crash the process, so report it as a failure.
      emit(LocalStreamEvent.StreamError(LocalChatFailure(LocalChatFailureKind.Unknown)))
    }
  }

  private fun emptyCompletion() = LocalStreamEvent.StreamError(LocalChatFailure(LocalChatFailureKind.Empty))
}
