package dev.weft.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.weft.contracts.AskKind
import dev.weft.contracts.UserAnswer

/**
 * Default Compose renderer for the substrate's pending dialog requests.
 * Drop it anywhere in the composition tree (typically near the root) to
 * render ask/confirm/info dialogs when a tool calls them.
 *
 * Apps that want custom dialog styling skip this and render against
 * [ComposeUiBridge.pending] themselves.
 */
@Composable
public fun PendingRequestRenderer(uiBridge: ComposeUiBridge) {
    when (val req = uiBridge.pending) {
        is ComposeUiBridge.PendingRequest.Ask -> AskDialog(req, uiBridge)
        is ComposeUiBridge.PendingRequest.Confirm -> ConfirmDialog(req, uiBridge)
        is ComposeUiBridge.PendingRequest.Info -> InfoDialog(req, uiBridge)
        null -> Unit
    }
}

@Composable
private fun InfoDialog(req: ComposeUiBridge.PendingRequest.Info, ui: ComposeUiBridge) {
    AlertDialog(
        onDismissRequest = { ui.acknowledgeInfo() },
        title = { Text(req.title) },
        text = { req.body?.let { Text(it) } ?: Box(modifier = Modifier.height(0.dp)) },
        confirmButton = { TextButton(onClick = { ui.acknowledgeInfo() }) { Text("OK") } },
    )
}

@Composable
private fun AskDialog(req: ComposeUiBridge.PendingRequest.Ask, ui: ComposeUiBridge) {
    when (req.kind) {
        AskKind.YES_NO -> AlertDialog(
            onDismissRequest = { ui.dismiss() },
            title = { Text(req.question) },
            confirmButton = { TextButton(onClick = { ui.answer(UserAnswer.YesNo(true)) }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { ui.answer(UserAnswer.YesNo(false)) }) { Text("No") } },
        )
        AskKind.CHOICE -> AlertDialog(
            onDismissRequest = { ui.dismiss() },
            title = { Text(req.question) },
            text = {
                Column {
                    req.options.forEach { opt ->
                        TextButton(
                            onClick = { ui.answer(UserAnswer.Choice(opt)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(opt) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ui.dismiss() }) { Text("Cancel") } },
        )
        AskKind.FREE_TEXT -> {
            var value by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { ui.dismiss() },
                title = { Text(req.question) },
                text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = false) },
                confirmButton = { TextButton(onClick = { ui.answer(UserAnswer.Text(value)) }) { Text("Submit") } },
                dismissButton = { TextButton(onClick = { ui.dismiss() }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(req: ComposeUiBridge.PendingRequest.Confirm, ui: ComposeUiBridge) {
    AlertDialog(
        onDismissRequest = { ui.confirm(false) },
        title = { Text(req.action) },
        text = { req.body?.let { Text(it) } ?: Box(modifier = Modifier.height(0.dp)) },
        confirmButton = { TextButton(onClick = { ui.confirm(true) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { ui.confirm(false) }) { Text("Cancel") } },
    )
}
