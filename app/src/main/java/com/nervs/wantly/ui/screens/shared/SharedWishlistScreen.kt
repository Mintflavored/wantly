package com.nervs.wantly.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nervs.wantly.R
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.model.WishStatus
import com.nervs.wantly.ui.common.openUrl
import com.nervs.wantly.ui.components.WishCard
import com.nervs.wantly.ui.rememberAppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedWishlistScreen(
    token: String,
    onBack: () -> Unit,
) {
    val vm: SharedWishlistViewModel = rememberAppViewModel {
        SharedWishlistViewModel(token, it.api)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cdBack = stringResource(R.string.cd_back)

    val title = (state as? SharedWishlistUiState.Loaded)?.wishlist?.title ?: "…"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, cdBack)
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val st = state) {
            SharedWishlistUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            SharedWishlistUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.shared_load_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = { vm.load() }) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            SharedWishlistUiState.NotFound -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.CardGiftcard,
                        null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.shared_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            is SharedWishlistUiState.Loaded -> {
                val wishes = st.wishes
                if (wishes.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.CardGiftcard,
                            null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.wishes_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + 80.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(wishes, key = { it.id }) { wish ->
                            // WishCard принимает WishEntity — маппим DTO → Entity для display.
                            // read-only: edit/delete/syncError скрыты через isReadOnly=true.
                            val entity = WishEntity(
                                id = wish.id,
                                wishlistId = wish.wishlistId,
                                title = wish.title,
                                description = wish.description,
                                url = wish.url,
                                imageUrl = wish.imageUrl,
                                price = wish.price,
                                currency = wish.currency,
                                storeName = wish.storeName,
                                status = wish.status,
                                synced = true,
                                serverId = wish.id,
                            )
                            WishCard(
                                wish = entity,
                                onCycleStatus = {},
                                onOpen = { openUrl(context, wish.url) },
                                onDelete = {},
                                onEdit = {},
                                onSyncError = {},
                                isReadOnly = true,
                            )
                        }
                        item {
                            Text(
                                stringResource(R.string.shared_list_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
