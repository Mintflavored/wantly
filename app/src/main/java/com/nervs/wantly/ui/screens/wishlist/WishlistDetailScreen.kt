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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nervs.wantly.R
import kotlinx.coroutines.launch
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.ui.common.openUrl
import com.nervs.wantly.ui.components.ShareWishlistDialog
import com.nervs.wantly.ui.components.SkeletonList
import com.nervs.wantly.ui.components.WishCard
import com.nervs.wantly.ui.components.WishCardSkeleton
import com.nervs.wantly.ui.components.WishlistFormDialog
import com.nervs.wantly.ui.rememberAppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistDetailScreen(
    wishlistId: Long,
    onAddWish: () -> Unit,
    onBack: () -> Unit,
    onEditWish: (Long) -> Unit,
) {
    val context = LocalContext.current
    val vm: WishlistDetailViewModel =
        rememberAppViewModel { WishlistDetailViewModel(wishlistId, it.repository, it.syncManager, it.api) }
    val state = vm.state.collectAsStateWithLifecycle().value
    val shareToken by vm.shareToken.collectAsStateWithLifecycle()
    val toggleErrorCount by vm.toggleErrorCount.collectAsStateWithLifecycle()
    val isLoadingToken by vm.isLoadingToken.collectAsStateWithLifecycle()
    val tokenLoadError by vm.tokenLoadError.collectAsStateWithLifecycle()
    val cdBack = stringResource(R.string.cd_back)
    val cdAddWish = stringResource(R.string.cd_add_wish)
    val cdEditWishlist = stringResource(R.string.cd_edit_wishlist)
    val cdShare = stringResource(R.string.cd_share)
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val syncErrorMessage = stringResource(R.string.sync_error_message)
    val syncErrorEdit = stringResource(R.string.sync_error_action_edit)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var wishToDelete by remember { mutableStateOf<WishEntity?>(null) }
    var showEditList by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    // Loading / NotFound рендерятся с TopAppBar+back, но без actions/FAB.
    // Loaded — полноценный экран.
    when (state) {
        WishlistDetailUiState.Loading -> {
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
                SkeletonList(
                    count = 3,
                    item = { WishCardSkeleton() },
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
            return
        }
        WishlistDetailUiState.NotFound -> {
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
        is WishlistDetailUiState.Loaded -> state.let { st ->
            val currentWishlist = st.wishlist
            val wishes = st.wishes
            Scaffold(
                snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                currentWishlist.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, cdBack)
                            }
                        },
                        actions = {
                            // Share доступен только для синхронизированных списков (есть serverId).
                            // Local-only/guest списки нельзя share — toggleShare всё равно не дойдёт
                            // до сервера. Скрываем кнопку вместо показа неработающего dialog.
                            if (currentWishlist.serverId != null) {
                                IconButton(onClick = { showShareDialog = true }) {
                                    Icon(Icons.Default.Share, contentDescription = cdShare)
                                }
                            }
                            IconButton(onClick = { showEditList = true }) {
                                Icon(Icons.Default.Edit, contentDescription = cdEditWishlist)
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
                                onEdit = { onEditWish(wish.id) },
                                onSyncError = {
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = syncErrorMessage,
                                            actionLabel = syncErrorEdit,
                                            duration = androidx.compose.material3.SnackbarDuration.Long,
                                        )
                                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                            onEditWish(wish.id)
                                        }
                                    }
                                },
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

            // Редактирование названия/описания/цвета списка (prefill из текущего).
            if (showEditList) {
                WishlistFormDialog(
                    titleRes = R.string.dialog_edit_list_title,
                    confirmLabelRes = R.string.action_save,
                    onDismiss = { showEditList = false },
                    onConfirm = { title, description, color ->
                        vm.updateWishlist(currentWishlist, title, description, color)
                        showEditList = false
                    },
                    initialTitle = currentWishlist.title,
                    initialDescription = currentWishlist.description.orEmpty(),
                    initialColor = currentWishlist.coverColor,
                )
            }

            // Диалог управления публичным доступом.
            if (showShareDialog) {
                ShareWishlistDialog(
                    isCurrentlyShared = currentWishlist.isShared || shareToken != null,
                    currentToken = shareToken,
                    toggleErrorTick = toggleErrorCount,
                    isSwitchEnabled = !isLoadingToken && !tokenLoadError,
                    tokenLoadError = tokenLoadError,
                    onRetryLoadToken = { vm.loadShareToken() },
                    onDismiss = { showShareDialog = false },
                    onToggleShare = { enabled ->
                        vm.setShare(enabled)
                    },
                )
            }
        }
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
