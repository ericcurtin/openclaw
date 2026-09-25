package ai.openclaw.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentBorderStrong
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCardSurface
import ai.openclaw.app.ui.mobileSurface
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileTextTertiary

internal data class DraftApplication(
  val input: String,
  val lastAppliedDraft: String?,
  val consumed: Boolean,
)

internal fun applyDraftText(
  draftText: String?,
  currentInput: String,
  lastAppliedDraft: String?,
): DraftApplication {
  val draft =
    draftText?.trim()?.ifEmpty { null } ?: return DraftApplication(
      input = currentInput,
      lastAppliedDraft = null,
      consumed = false,
    )
  if (draft == lastAppliedDraft) {
    return DraftApplication(
      input = currentInput,
      lastAppliedDraft = lastAppliedDraft,
      consumed = false,
    )
  }
  return DraftApplication(
    input = draft,
    lastAppliedDraft = draft,
    consumed = true,
  )
}

@Composable
fun ChatComposer(
  draftText: String?,
  healthOk: Boolean,
  thinkingLevel: String,
  pendingRunCount: Int,
  attachments: List<PendingImageAttachment>,
  onDraftApplied: () -> Unit,
  onPickImages: () -> Unit,
  onRemoveAttachment: (id: String) -> Unit,
  onSetThinkingLevel: (level: String) -> Unit,
  onRefresh: () -> Unit,
  onAbort: () -> Unit,
  onSend: (text: String) -> Unit,
) {
  var input by rememberSaveable { mutableStateOf("") }
  var lastAppliedDraft by rememberSaveable { mutableStateOf<String?>(null) }
  var showThinkingMenu by remember { mutableStateOf(false) }

  LaunchedEffect(draftText) {
    val next = applyDraftText(draftText = draftText, currentInput = input, lastAppliedDraft = lastAppliedDraft)
    input = next.input
    lastAppliedDraft = next.lastAppliedDraft
    if (next.consumed) {
      onDraftApplied()
    }
  }

  val canSend = pendingRunCount == 0 && (input.trim().isNotEmpty() || attachments.isNotEmpty()) && healthOk
  val sendBusy = pendingRunCount > 0

  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (attachments.isNotEmpty()) {
      AttachmentsStrip(attachments = attachments, onRemoveAttachment = onRemoveAttachment)
    }

    // Slim secondary toolbar: thinking level + refresh/abort. Kept visually light so the
    // WhatsApp-style input row below stays the primary affordance.
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Box {
        Surface(
          onClick = { showThinkingMenu = true },
          shape = RoundedCornerShape(999.dp),
          color = mobileCardSurface,
          border = BorderStroke(1.dp, mobileBorder),
        ) {
          Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = thinkingLabel(thinkingLevel),
              style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
              color = mobileTextSecondary,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select thinking level", modifier = Modifier.size(16.dp), tint = mobileTextTertiary)
          }
        }

        DropdownMenu(
          expanded = showThinkingMenu,
          onDismissRequest = { showThinkingMenu = false },
          shape = RoundedCornerShape(16.dp),
          containerColor = mobileCardSurface,
          tonalElevation = 0.dp,
          shadowElevation = 8.dp,
          border = BorderStroke(1.dp, mobileBorder),
        ) {
          ThinkingMenuItem("off", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("low", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("medium", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("high", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      ToolbarIconButton(
        label = "Refresh",
        icon = Icons.Default.Refresh,
        enabled = true,
        onClick = onRefresh,
      )

      ToolbarIconButton(
        label = "Abort",
        icon = Icons.Default.Stop,
        enabled = pendingRunCount > 0,
        onClick = onAbort,
      )
    }

    if (!healthOk) {
      Text(
        text = "Gateway is offline. Connect first in the Connect tab.",
        style = mobileCallout,
        color = ai.openclaw.app.ui.mobileWarning,
      )
    }

    // WhatsApp-style input row: pill-shaped text field (with inline attach) + circular send.
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      ComposerPill(
        value = input,
        onValueChange = { input = it },
        onPickImages = onPickImages,
        modifier = Modifier.weight(1f),
      )

      CircularSendButton(
        enabled = canSend,
        busy = sendBusy,
        onClick = {
          val text = input
          input = ""
          onSend(text)
        },
      )
    }
  }
}

private val ComposerControlSize = 48.dp

@Composable
private fun ComposerPill(
  value: String,
  onValueChange: (String) -> Unit,
  onPickImages: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val textStyle = mobileBodyStyle().copy(color = mobileText)
  Surface(
    modifier = modifier.heightIn(min = ComposerControlSize),
    // Half the resting height => fully rounded pill at one line; stays soft-cornered as it grows.
    shape = RoundedCornerShape(ComposerControlSize / 2),
    color = mobileSurface,
    border = BorderStroke(1.dp, mobileBorder),
  ) {
    Row(
      modifier = Modifier.animateContentSize(),
      verticalAlignment = Alignment.Bottom,
    ) {
      BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
          Modifier
            .weight(1f)
            .padding(start = 18.dp, top = 13.dp, bottom = 13.dp),
        textStyle = textStyle,
        cursorBrush = SolidColor(mobileAccent),
        // Grow from one line up to six, then scroll internally (WhatsApp behavior).
        minLines = 1,
        maxLines = 6,
        decorationBox = { innerTextField ->
          Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
              Text(
                text = "Type a message…",
                style = textStyle,
                color = mobileTextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            innerTextField()
          }
        },
      )

      IconButton(
        onClick = onPickImages,
        modifier = Modifier.size(ComposerControlSize),
      ) {
        Icon(
          Icons.Default.AttachFile,
          contentDescription = "Attach",
          modifier = Modifier.size(22.dp),
          tint = mobileTextSecondary,
        )
      }
    }
  }
}

@Composable
private fun CircularSendButton(
  enabled: Boolean,
  busy: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.size(ComposerControlSize),
    shape = CircleShape,
    color = if (enabled) mobileAccent else mobileBorderStrong,
    contentColor = if (enabled) Color.White else mobileTextTertiary,
    border = BorderStroke(1.dp, if (enabled) mobileAccentBorderStrong else mobileBorderStrong),
  ) {
    Box(contentAlignment = Alignment.Center) {
      if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
      } else {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(22.dp))
      }
    }
  }
}

@Composable
private fun ToolbarIconButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.size(32.dp),
    colors =
      IconButtonDefaults.iconButtonColors(
        contentColor = mobileTextSecondary,
        disabledContentColor = mobileTextTertiary,
      ),
  ) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
  }
}

@Composable
private fun ThinkingMenuItem(
  value: String,
  current: String,
  onSet: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  DropdownMenuItem(
    text = { Text(thinkingLabel(value), style = mobileCallout, color = mobileText) },
    onClick = {
      onSet(value)
      onDismiss()
    },
    trailingIcon = {
      if (value == current.trim().lowercase()) {
        Text("✓", style = mobileCallout, color = mobileAccent)
      } else {
        Spacer(modifier = Modifier.width(10.dp))
      }
    },
  )
}

private fun thinkingLabel(raw: String): String {
  return when (raw.trim().lowercase()) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    else -> "Off"
  }
}

@Composable
private fun AttachmentsStrip(
  attachments: List<PendingImageAttachment>,
  onRemoveAttachment: (id: String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (att in attachments) {
      AttachmentChip(
        fileName = att.fileName,
        onRemove = { onRemoveAttachment(att.id) },
      )
    }
  }
}

@Composable
private fun AttachmentChip(fileName: String, onRemove: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, mobileBorderStrong),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = fileName,
        style = mobileCaption1,
        color = mobileText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Surface(
        onClick = onRemove,
        shape = RoundedCornerShape(999.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorderStrong),
      ) {
        Text(
          text = "×",
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
          color = mobileTextSecondary,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun mobileBodyStyle() =
  MaterialTheme.typography.bodyMedium.copy(
    fontFamily = ai.openclaw.app.ui.mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  )
