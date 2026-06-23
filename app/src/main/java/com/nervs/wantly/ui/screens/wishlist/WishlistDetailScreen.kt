package com.nervs.wantly.ui.screens.wishlist

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nervs.wantly.R
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.ui.common.openUrl
import com.nervs.wantly.ui.components.WishCard
import com.nervs.wantly.ui.rememberAppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistDetailScreen(
    wishlistId: Long,
    onAddWish: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: WishlistDetailViewModel =
        rememberAppViewModel { WishlistDetailViewModel(wishlistId, it.repository, it.syncManager) }
    val wishlist by vm.wishlist.collectAsStateWithLifecycle()
    val wishes by vm.wishes.collectAsStateWithLifecycle()
    val cdBack = stringResource(R.string.cd_back)
    val cdAddWish = stringResource(R.string.cd_add_wish)
    var wishToDelete by remember { mutableStateOf<WishEntity?>(null) }

    val currentWishlist = wishlist
    if (currentWishlist == null) {
        // Список удалён или ещё не загружен. Room отдаёт почти мгновенно,
        // поэтому null на практике = список не существует.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, cdBack)
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.wishlist_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentWishlist.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, cdBack)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddWish) {
                Icon(Icons.Default.Add, cdAddWish)
            }
        },
    ) { innerPadding ->
        if (wishes.isEmpty()) {
            EmptyWishes(Modifier.fillMaxSize().padding(innerPadding))
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
                    WishCard(
                        wish = wish,
                        onCycleStatus = { vm.cycleStatus(wish) },
                        onOpen = { openUrl(context, wish.url) },
                        onDelete = { wishToDelete = wish },
                    )
                }
            }
        }
    }

    // Пункт 5: подтверждение удаления желания
    wishToDelete?.let { wish ->
        AlertDialog(
            onDismissRequest = { wishToDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_wish_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_wish_message, wish.title))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteWish(wish)
                    wishToDelete = null
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { wishToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyWishes(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
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
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(R.string.wishes_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
