package com.nervs.wantly.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nervs.wantly.R

/**
 * Диалог управления публичным доступом к списку.
 *
 * @param isCurrentlyShared текущее состояние isShared на сервере.
 * @param currentToken share-token (null если не shared или ещё не загружен).
 * @param onToggleShare вызывается при переключении switch с новым значением.
 *        Caller делает API toggleShare и обновляет токен.
 */
@Composable
fun ShareWishlistDialog(
    isCurrentlyShared: Boolean,
    currentToken: String?,
    onDismiss: () -> Unit,
    onToggleShare: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var isShared by remember { mutableStateOf(isCurrentlyShared) }
    var token by remember { mutableStateOf(currentToken) }

    // Обновляем локальный token при изменении prop (асинхронный ответ toggleShare).
    LaunchedEffect(currentToken) {
        if (currentToken != null) token = currentToken
    }
    // Если sharing выключили (currentToken стал null после toggle off) — синхронизируем.
    LaunchedEffect(isCurrentlyShared) {
        if (!isCurrentlyShared) {
            isShared = false
            token = null
        }
    }

    val link = token?.let { "https://wantlyapp.ru/s/$it" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dialog_share_switch_label))
                    Switch(
                        checked = isShared,
                        onCheckedChange = { newValue ->
                            isShared = newValue
                            if (!newValue) token = null
                            onToggleShare(newValue)
                        },
                    )
                }

                if (isShared && link != null) {
                    Text(
                        stringResource(R.string.dialog_share_link_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = {},
                            readOnly = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                        )
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("link", link))
                            Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, null)
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.action_copy_link))
                        }
                    }
                    TextButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Мои пожелания: $link")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, null))
                    }) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.action_share_via))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
