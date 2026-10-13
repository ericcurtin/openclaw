package ai.openclaw.app.local

internal enum class LocalSetupError {
  BaseUrlBlank,
  BaseUrlInvalid,
  BaseUrlUnsupportedScheme,
  BaseUrlEmbeddedCredentials,
  BaseUrlQueryOrFragment,
  BaseUrlInsecureRemoteHost,
  ModelBlank,
  ModelTooLong,
  ApiKeyInvalid,
}

internal sealed interface LocalSetupValidation {
  data class Valid(
    val config: LocalModeConfig,
    val apiKey: LocalApiKeyChange,
  ) : LocalSetupValidation

  data class Invalid(
    val error: LocalSetupError,
  ) : LocalSetupValidation
}

private const val MODEL_MAX_CHARS = 200

/**
 * Validates the setup form. The API key is optional (many self-hosted servers need none); a typed
 * key replaces the stored one, [clearKey] removes it, and leaving the field blank keeps it.
 */
internal fun validateLocalSetup(
  baseUrl: String,
  model: String,
  apiKeyInput: String,
  clearKey: Boolean,
): LocalSetupValidation {
  val endpoint =
    when (val parsed = parseLocalEndpoint(baseUrl)) {
      is LocalEndpointResult.Valid -> {
        parsed.baseUrl
      }

      is LocalEndpointResult.Invalid -> {
        return LocalSetupValidation.Invalid(
          when (parsed.error) {
            LocalEndpointError.Blank -> LocalSetupError.BaseUrlBlank
            LocalEndpointError.InvalidUrl -> LocalSetupError.BaseUrlInvalid
            LocalEndpointError.UnsupportedScheme -> LocalSetupError.BaseUrlUnsupportedScheme
            LocalEndpointError.EmbeddedCredentials -> LocalSetupError.BaseUrlEmbeddedCredentials
            LocalEndpointError.QueryOrFragment -> LocalSetupError.BaseUrlQueryOrFragment
            LocalEndpointError.InsecureRemoteHost -> LocalSetupError.BaseUrlInsecureRemoteHost
          },
        )
      }
    }
  val trimmedModel = model.trim()
  if (trimmedModel.isEmpty()) return LocalSetupValidation.Invalid(LocalSetupError.ModelBlank)
  if (trimmedModel.length > MODEL_MAX_CHARS) return LocalSetupValidation.Invalid(LocalSetupError.ModelTooLong)
  val key = apiKeyInput.trim()
  if (key.isNotEmpty() && !isValidLocalApiKey(key)) return LocalSetupValidation.Invalid(LocalSetupError.ApiKeyInvalid)
  val change =
    when {
      key.isNotEmpty() -> LocalApiKeyChange.Set(key)
      clearKey -> LocalApiKeyChange.Clear
      else -> LocalApiKeyChange.Keep
    }
  return LocalSetupValidation.Valid(LocalModeConfig(baseUrl = endpoint.toString(), model = trimmedModel), change)
}
