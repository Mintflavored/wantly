package com.nervs.wantly.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nervs.wantly.R
import com.nervs.wantly.WantlyApp
import com.nervs.wantly.data.LogoutSyncOutcome
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onCreateAccount: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as WantlyApp
    val isLoggedIn by app.container.sessionManager.isLoggedIn.collectAsState(initial = false)
    val displayName by app.container.sessionManager.displayName.collectAsState(initial = null)
    val email by app.container.sessionManager.email.collectAsState(initial = null)
    var showLogoutError by remember { mutableStateOf(false) }

    if (showLogoutError) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutError = false },
            confirmButton = {
                TextButton(onClick = { showLogoutError = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            title = { Text(stringResource(R.string.profile_logout_failed_title)) },
            text = { Text(stringResource(R.string.profile_logout_failed_text)) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.profile_title)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            if (isLoggedIn) {
                Text(
                    displayName ?: email ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(24.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            // Verified flush: различаем три исхода.
                            when (app.container.syncManager.pushPendingVerifiedForLogout()) {
                                LogoutSyncOutcome.SUCCESS -> {
                                    // Все dirty данные ушли — чистим Room и сессию атомарно.
                                    app.container.syncManager.clearLocalUnder {
                                        app.container.sessionManager.clearSession()
                                    }
                                }
                                LogoutSyncOutcome.AUTH_EXPIRED -> {
                                    // JWT истёк, push не может отправить. Чистим только
                                    // сессию — Room оставляем, данные уйдут после re-login.
                                    // isLoggedIn сразу становится false → юзер на auth screen.
                                    app.container.sessionManager.clearSession()
                                }
                                LogoutSyncOutcome.TRANSIENT_FAILURE -> {
                                    // Сетевая/серверная ошибка — не вытираем, иначе data loss.
                                    showLogoutError = true
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profile_logout))
                }
            } else {
                Text(
                    stringResource(R.string.profile_guest),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.profile_guest_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(16.dp))
                Button(
                    onClick = onCreateAccount,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CardGiftcard, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.profile_create_account))
                }
            }
        }
    }
}
