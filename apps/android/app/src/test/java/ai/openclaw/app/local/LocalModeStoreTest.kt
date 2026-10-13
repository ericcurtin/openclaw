package ai.openclaw.app.local

import ai.openclaw.app.SecurePrefs
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/** Exercises the encrypted-store design (via an injected plain SharedPreferences) and the fake's contract. */
@RunWith(RobolectricTestRunner::class)
class LocalModeStoreTest {
  private val context get() = RuntimeEnvironment.getApplication()
  private val secureFile = "local-mode-secure-${UUID.randomUUID()}"
  private val config = LocalModeConfig("https://api.example.com/v1", "gpt-test")

  private fun store(): SecurePrefsLocalModeStore =
    SecurePrefsLocalModeStore(SecurePrefs(context, context.getSharedPreferences(secureFile, Context.MODE_PRIVATE)))

  private fun raw() = context.getSharedPreferences(secureFile, Context.MODE_PRIVATE)

  @Test
  fun startsEmptyAndInactive() {
    val store = store()
    assertNull(store.config.value)
    assertFalse(store.active.value)
    assertFalse(store.hasApiKey.value)
    assertNull(store.loadApiKey())
  }

  @Test
  fun theKeyLivesApartFromTheConfigAndIsNeverSerializedWithIt() {
    val store = store()
    assertTrue(store.save(config, LocalApiKeyChange.Set("sk-secret-123")))
    assertEquals(config, store.config.value)
    assertEquals("sk-secret-123", store.loadApiKey())
    assertTrue(store.hasApiKey.value)
    val rawConfig = raw().getString("local.config", null).orEmpty()
    assertTrue(rawConfig.contains("api.example.com"))
    assertFalse(rawConfig.contains("sk-secret-123"))
    assertEquals("sk-secret-123", raw().getString("local.apiKey", null))
  }

  @Test
  fun keepClearAndSetEachDoExactlyOneThing() {
    val store = store()
    store.save(config, LocalApiKeyChange.Set("sk-one"))
    store.save(config.copy(model = "other"), LocalApiKeyChange.Keep)
    assertEquals("other", store.config.value?.model)
    assertEquals("sk-one", store.loadApiKey())
    store.save(config, LocalApiKeyChange.Set("  sk-two  "))
    assertEquals("sk-two", store.loadApiKey())
    store.save(config, LocalApiKeyChange.Clear)
    assertNull(store.loadApiKey())
    assertFalse(store.hasApiKey.value)
    store.save(config, LocalApiKeyChange.Set("   "))
    assertNull(store.loadApiKey())
  }

  @Test
  fun activeNeedsASavedSetupAndSurvivesRestart() {
    val store = store()
    assertFalse(store.setActive(true))
    assertFalse(store.active.value)
    store.save(config, LocalApiKeyChange.Keep)
    assertTrue(store.setActive(true))
    assertTrue(store.active.value)
    val reopened = store()
    assertEquals(config, reopened.config.value)
    assertTrue(reopened.active.value)
    reopened.setActive(false)
    assertFalse(store().active.value)
  }

  @Test
  fun aDanglingActiveFlagWithoutASetupIsIgnored() {
    raw().edit().putString("local.active", "true").commit()
    assertFalse(store().active.value)
  }

  @Test
  fun removeDeletesTheKeyTheEndpointAndTheFlag() {
    val store = store()
    store.save(config, LocalApiKeyChange.Set("sk-secret-123"))
    store.setActive(true)
    assertTrue(store.remove())
    assertNull(store.config.value)
    assertFalse(store.active.value)
    assertFalse(store.hasApiKey.value)
    assertNull(store.loadApiKey())
    listOf("local.config", "local.apiKey", "local.active").forEach { key -> assertFalse(key, raw().contains(key)) }
    assertFalse(raw().all.values.any { it.toString().contains("sk-secret-123") })
    assertNull(store().config.value)
  }

  @Test
  fun theFakeStoreHonorsTheSameKeyContract() {
    val fake = FakeLocalModeStore()
    fake.save(config, LocalApiKeyChange.Set("sk-one"))
    fake.save(config, LocalApiKeyChange.Keep)
    assertEquals("sk-one", fake.loadApiKey())
    fake.save(config, LocalApiKeyChange.Clear)
    assertNull(fake.loadApiKey())
    fake.save(config, LocalApiKeyChange.Set("sk-two"))
    fake.remove()
    assertNull(fake.loadApiKey())
    assertNull(fake.config.value)
  }
}
