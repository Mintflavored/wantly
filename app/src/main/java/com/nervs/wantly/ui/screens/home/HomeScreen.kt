package com.nervs.wantly.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nervs.wantly.R
import com.nervs.wantly.data.local.WishlistWithCount
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.ui.components.SkeletonList
import com.nervs.wantly.ui.components.WishlistFormDialog
import com.nervs.wantly.ui.components.WishlistRowSkeleton
import com.nervs.wantly.ui.rememberAppViewModel
import com.nervs.wantly.ui.theme.WishlistAccents

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onWishlistClick: (Long) -> Unit) {
    val vm: HomeViewModel = rememberAppViewModel { HomeViewModel(it.repository, it.syncManager) }
    val app = LocalContext.current.applicationContext as com.nervs.wantly.WantlyApp
    var showCreate by remember { mutableStateOf(false) }
    var wishlistToDelete by remember { mutableStateOf<WishlistEntity?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val isOnline by app.container.networkMonitor.isOnline.collectAsStateWithLifecycle()
    val unsyncedCount by app.container.repository.observeUnsyncedCount()
        .collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                actions = {
                    SyncStatusIndicator(isOnline = isOnline, unsyncedCount = unsyncedCount)
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.action_new_list)) },
            )
        },
    ) { innerPadding ->
        val state = vm.state.collectAsStateWithLifecycle().value
        when (state) {
            HomeUiState.Loading -> {
                SkeletonList(
                    count = 4,
                    item = { WishlistRowSkeleton() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding(),
                        ),
                )
            }
            HomeUiState.Error -> {
                // Room flow крайне редко падает, но safety: показываем empty-подобный state.
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.home_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            is HomeUiState.Loaded -> state.wishlists.let { wishlists ->
                if (wishlists.isEmpty()) {
                    EmptyHome(Modifier.fillMaxSize().padding(innerPadding))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(wishlists, key = { it.wishlist.id }) { item ->
                            WishlistRow(
                                item = item,
                                onClick = { onWishlistClick(item.wishlist.id) },
                                onLongClick = { wishlistToDelete = item.wishlist },
                                onSyncError = {
                                    com.nervs.wantly.ui.SnackbarController.send(
                                        com.nervs.wantly.ui.SnackbarMessage(
                                            messageRes = R.string.sync_error_message,
                                            actionLabelRes = R.string.sync_error_action_edit,
                                            onAction = { onWishlistClick(item.wishlist.id) },
                                            duration = SnackbarDuration.Long,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        WishlistFormDialog(
            titleRes = R.string.dialog_new_list_title,
            confirmLabelRes = R.string.action_create,
            onDismiss = { showCreate = false },
            onConfirm = { title, description, color ->
                vm.createWishlist(title, description, color)
                showCreate = false
            },
        )
    }

    wishlistToDelete?.let { wishlist ->
        DeleteWishlistDialog(
            wishlistTitle = wishlist.title,
            onDismiss = { wishlistToDelete = null },
            onConfirm = {
                vm.deleteWishlist(wishlist)
                wishlistToDelete = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WishlistRow(
    item: WishlistWithCount,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSyncError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = WishlistAccents[item.wishlist.coverColor % WishlistAccents.size]
    val countLabel = LocalContext.current.resources.getQuantityString(
        R.plurals.wishes_count,
        item.wishCount,
        item.wishCount,
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.CollectionsBookmark, null, tint = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    item.wishlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.wishlist.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // syncError: сервер отверг список (HTTP 400). Иконка → Snackbar с пояснением.
            if (item.wishlist.syncError) {
                IconButton(onClick = onSyncError) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = stringResource(R.string.cd_sync_error),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier = Modifier) {
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
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(R.string.home_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DeleteWishlistDialog(
    wishlistTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_list_title)) },
        text = {
            Text(stringResource(R.string.dialog_delete_list_message, wishlistTitle))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Индикатор sync-статуса в TopAppBar:
 * - offline (CloudOff, error tint)
 * - unsynced N (CloudQueue, outline tint)
 * - всё синхронизировано (CloudSync, primary tint — «всё ок»)
 */
@Composable
fun SyncStatusIndicator(isOnline: Boolean, unsyncedCount: Int) {
    // Для synced-state используем отдельную строку без форматирования (P3:
    // раньше «Не синхронизировано: 0» для чистого состояния вводило в заблуждение).
    val description = when {
        !isOnline -> stringResource(R.string.cd_sync_offline)
        unsyncedCount > 0 -> stringResource(R.string.cd_sync_unsynced, unsyncedCount)
        else -> stringResource(R.string.cd_sync_synced)
    }
    val tint = when {
        !isOnline -> MaterialTheme.colorScheme.error
        unsyncedCount > 0 -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val icon = when {
        !isOnline -> Icons.Default.CloudOff
        unsyncedCount > 0 -> Icons.Default.CloudQueue
        else -> Icons.Default.CloudSync
    }
    Icon(icon, contentDescription = description, tint = tint)
}
