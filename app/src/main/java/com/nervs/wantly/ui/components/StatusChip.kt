package com.nervs.wantly.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nervs.wantly.data.model.WishStatus

@Composable
fun StatusChip(
    status: WishStatus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val container = when (status) {
        WishStatus.WANTED -> MaterialTheme.colorScheme.surfaceVariant
        WishStatus.RESERVED -> MaterialTheme.colorScheme.tertiaryContainer
        WishStatus.PURCHASED -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when (status) {
        WishStatus.WANTED -> MaterialTheme.colorScheme.onSurfaceVariant
        WishStatus.RESERVED -> MaterialTheme.colorScheme.onTertiaryContainer
        WishStatus.PURCHASED -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(status.labelRes),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
