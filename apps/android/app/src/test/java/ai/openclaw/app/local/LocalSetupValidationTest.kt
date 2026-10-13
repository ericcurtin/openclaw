package ai.openclaw.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSetupValidationTest {
  private fun valid(
    baseUrl: String = "https://api.example.com/v1",
    model: String = "gpt-test",
    key: String = "",
    clear: Boolean = false,
  ) = validateLocalSetup(baseUrl, model, key, clear) as LocalSetupValidation.Valid

  private fun error(
    baseUrl: String = "https://api.example.com/v1",
    model: String = "gpt-test",
    key: String = "",
  ) = (validateLocalSetup(baseUrl, model, key, false) as LocalSetupValidation.Invalid).error

  @Test
  fun aValidSetupIsNormalizedAndKeepsTheStoredKeyByDefault() {
    val result = valid(baseUrl = "  https://api.example.com/v1  ", model = "  gpt-test  ")
    assertEquals(LocalModeConfig("https://api.example.com/v1", "gpt-test"), result.config)
    assertEquals(LocalApiKeyChange.Keep, result.apiKey)
  }

  @Test
  fun aTypedKeyReplacesAndClearRemovesAndTypedWinsOverClear() {
    assertEquals(LocalApiKeyChange.Set("sk-new"), valid(key = " sk-new ").apiKey)
    assertEquals(LocalApiKeyChange.Clear, valid(clear = true).apiKey)
    assertEquals(LocalApiKeyChange.Set("sk-new"), valid(key = "sk-new", clear = true).apiKey)
  }

  @Test
  fun serversWithoutAKeyAreSupported() {
    val result = valid(baseUrl = "http://192.168.1.20:11434/v1", model = "llama3.2")
    assertEquals(LocalApiKeyChange.Keep, result.apiKey)
  }

  @Test
  fun endpointProblemsSurfaceAsFieldErrors() {
    assertEquals(LocalSetupError.BaseUrlBlank, error(baseUrl = ""))
    assertEquals(LocalSetupError.BaseUrlInvalid, error(baseUrl = "https://"))
    assertEquals(LocalSetupError.BaseUrlUnsupportedScheme, error(baseUrl = "ftp://host"))
    assertEquals(LocalSetupError.BaseUrlEmbeddedCredentials, error(baseUrl = "https://user:pw@host/v1"))
    assertEquals(LocalSetupError.BaseUrlQueryOrFragment, error(baseUrl = "https://host/v1?key=sk"))
    assertEquals(LocalSetupError.BaseUrlInsecureRemoteHost, error(baseUrl = "http://api.example.com/v1"))
  }

  @Test
  fun modelAndKeyAreChecked() {
    assertEquals(LocalSetupError.ModelBlank, error(model = "   "))
    assertEquals(LocalSetupError.ModelTooLong, error(model = "m".repeat(201)))
    assertEquals(LocalSetupError.ApiKeyInvalid, error(key = "sk with space"))
    assertEquals(LocalSetupError.ApiKeyInvalid, error(key = "sk-\u00e9"))
  }

  @Test
  fun aKeyChangeNeverPrintsTheKey() {
    val text = LocalApiKeyChange.Set("sk-secret-123").toString()
    assertFalse(text.contains("sk-secret"))
    assertTrue(text.contains("redacted"))
  }
}
