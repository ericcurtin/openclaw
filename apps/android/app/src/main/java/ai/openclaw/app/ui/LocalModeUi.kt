package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.local.LocalApiKeyChange
import ai.openclaw.app.local.LocalModeConfig
import ai.openclaw.app.local.LocalSetupError
import ai.openclaw.app.local.LocalSetupValidation
import ai.openclaw.app.local.validateLocalSetup
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The honest limits of local mode, shared by the setup form and the chat notice. */
internal fun localModeLimitsText(): String =
  nativeString(
    "Local mode is plain chat with the model you configure. It has no tools, approvals, sandboxing, memory, channels, or automations. Connect a Gateway to unlock them.",
  )

internal fun localSetupErrorText(error: LocalSetupError): String =
  when (error) {
    LocalSetupError.BaseUrlBlank -> nativeString("Enter the provider's base URL.")
    LocalSetupError.BaseUrlInvalid -> nativeString("Enter a valid URL, for example https://api.openai.com/v1.")
    LocalSetupError.BaseUrlUnsupportedScheme -> nativeString("Use an http or https URL.")
    LocalSetupError.BaseUrlEmbeddedCredentials -> nativeString("Remove the username and password from the URL. Use the API key field instead.")
    LocalSetupError.BaseUrlQueryOrFragment -> nativeString("Remove everything after ? or # from the URL. Use the API key field for credentials.")
    LocalSetupError.BaseUrlInsecureRemoteHost -> nativeString("Use https for servers outside your network. Plain http is allowed only for your local network, .local names, and localhost.")
    LocalSetupError.ModelBlank -> nativeString("Enter the model name your provider expects.")
    LocalSetupError.ModelTooLong -> nativeString("That model name is too long.")
    LocalSetupError.ApiKeyInvalid -> nativeString("The API key has characters that are not allowed. Paste it again without spaces.")
  }

/**
 * Setup form for local mode. State is heap-only (never saveable) so the API key cannot reach a
 * saved-instance Bundle, and the key field is cleared when the form leaves composition.
 */
@Composable
internal fun LocalModeSetupForm(
  initial: LocalModeConfig?,
  hasSavedKey: Boolean,
  submitLabel: String,
  onSubmit: (LocalModeConfig, LocalApiKeyChange) -> Unit,
  modifier: Modifier = Modifier,
) {
  var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
  var model by remember { mutableStateOf(initial?.model.orEmpty()) }
  var apiKey by remember { mutableStateOf("") }
  var clearKey by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<LocalSetupError?>(null) }
  DisposableEffect(Unit) { onDispose { apiKey = "" } }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(localModeLimitsText(), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    ClawTextField(
      value = baseUrl,
      onValueChange = {
        baseUrl = it
        error = null
      },
      placeholder = "",
      label = nativeString("Base URL"),
      modifier = Modifier.testTag("local-mode-base-url"),
    )
    Text(
      nativeString("Any OpenAI-compatible server: OpenAI, OpenRouter, or Ollama, llama.cpp, or LM Studio on your network. Include the version path, such as /v1."),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textMuted,
    )
    ClawTextField(
      value = model,
      onValueChange = {
        model = it
        error = null
      },
      placeholder = "",
      label = nativeString("Model"),
      modifier = Modifier.testTag("local-mode-model"),
    )
    ClawTextField(
      value = apiKey,
      onValueChange = {
        apiKey = it
        clearKey = false
        error = null
      },
      placeholder = "",
      label = nativeString("API key (optional)"),
      secret = true,
      modifier = Modifier.testTag("local-mode-api-key"),
    )
    Text(
      nativeString("The key stays encrypted on this phone and is sent only to the base URL above."),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textMuted,
    )
    if (hasSavedKey && apiKey.isEmpty()) {
      Text(
        if (clearKey) nativeString("The saved key will be removed when you save.") else nativeString("A key is saved. Leave the field blank to keep it."),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
      )
      TextButton(onClick = { clearKey = !clearKey }) {
        Text(if (clearKey) nativeString("Keep saved key") else nativeString("Remove saved key"))
      }
    }
    error?.let { Text(localSetupErrorText(it), color = ClawTheme.colors.warning, modifier = Modifier.testTag("local-mode-error")) }
    ClawPrimaryButton(
      text = submitLabel,
      onClick = {
        when (val result = validateLocalSetup(baseUrl, model, apiKey, clearKey)) {
          is LocalSetupValidation.Valid -> {
            // A fresh setup shows no saved key, so a blank field must not silently keep an old one.
            val change = if (initial == null && result.apiKey == LocalApiKeyChange.Keep) LocalApiKeyChange.Clear else result.apiKey
            onSubmit(result.config, change)
          }

          is LocalSetupValidation.Invalid -> {
            error = result.error
          }
        }
      },
      modifier = Modifier.fillMaxWidth().testTag("local-mode-save"),
    )
  }
}

/** Edit dialog opened from Settings → Gateway. Saving keeps the current route focus unchanged. */
@Composable
internal fun LocalModeSettingsDialog(
  viewModel: MainViewModel,
  onDismiss: () -> Unit,
) {
  val config by viewModel.localModeConfig.collectAsState()
  val hasKey by viewModel.localModeHasApiKey.collectAsState()
  FoldAwareDialog(onDismissRequest = onDismiss, title = nativeString("Local mode")) {
    Surface(
      modifier = Modifier.fillMaxWidth().testTag("local-mode-settings"),
      shape = RoundedCornerShape(ClawTheme.radii.sheet),
      color = ClawTheme.colors.surface,
      contentColor = ClawTheme.colors.text,
    ) {
      Column(
        modifier = Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(nativeString("Local mode"), style = ClawTheme.type.title)
        LocalModeSetupForm(
          initial = config,
          hasSavedKey = hasKey,
          submitLabel = nativeString("Save"),
          onSubmit = { newConfig, key ->
            viewModel.saveLocalMode(newConfig, key, focus = false)
            onDismiss()
          },
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(nativeString("Cancel")) }
      }
    }
  }
}

/** Always-visible reminder at the top of a local chat: what it cannot do and how to unlock it. */
@Composable
internal fun LocalModeChatNotice(
  onConnectGateway: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth().testTag("local-mode-notice"),
    shape = RoundedCornerShape(ClawTheme.radii.panel),
    color = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
    border = BorderStroke(1.dp, ClawTheme.colors.border),
  ) {
    Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(nativeString("Local mode"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
      Text(localModeLimitsText(), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      TextButton(onClick = onConnectGateway) { Text(nativeString("Connect a Gateway")) }
    }
  }
}
