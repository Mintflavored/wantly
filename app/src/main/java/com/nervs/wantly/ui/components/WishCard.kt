package com.nervs.wantly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nervs.wantly.R
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.model.WishStatus
import com.nervs.wantly.ui.common.formatPrice

@Composable
fun WishCard(
    wish: WishEntity,
    onCycleStatus: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onSyncError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val status = WishStatus.fromName(wish.status)
    Card(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!wish.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = wish.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.CardGiftcard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    text = wish.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!wish.storeName.isNullOrBlank()) {
                    Text(
                        text = wish.storeName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                formatPrice(wish.price, wish.currency)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(status = status, onClick = onCycleStatus)
                // syncError: сервер отверг row (HTTP 400). Иконка ведёт к Snackbar/Dialog
                // с пояснением → пользователь редактирует → Repository сбрасывает флаг.
                if (wish.syncError) {
                    IconButton(onClick = onSyncError) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = stringResource(R.string.cd_sync_error),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_delete))
                }
            }
        }
    }
}
