package com.nervs.wantly.ui.screens.addwish

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nervs.wantly.R
import com.nervs.wantly.ui.rememberAppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWishScreen(
    wishlistId: Long,
    onBack: () -> Unit,
    wishId: Long? = null,
) {
    val vm: AddWishViewModel = rememberAppViewModel {
        AddWishViewModel(wishlistId, it.repository, it.guestCounter, it.sessionManager, it.syncManager, wishId)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as ClipboardManager
    val cdBack = stringResource(R.string.cd_back)
    val cdPaste = stringResource(R.string.cd_paste)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditMode) R.string.edit_wish_title else R.string.add_wish_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, cdBack)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.add_wish_link_section),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                label = { Text(stringResource(R.string.field_url_hint)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        val text = clipboard.primaryClip
                            ?.getItemAt(0)?.coerceToText(context)
                        vm.onUrlChange(text?.toString() ?: "")
                    }) {
                        Icon(Icons.Default.ContentPaste, cdPaste)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = vm::recognize,
                enabled = state.url.isNotBlank() && !state.isParsing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isParsing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (state.isParsing) R.string.add_wish_parsing
                        else R.string.add_wish_recognize,
                    ),
                )
            }
            state.error?.let { error ->
                Text(
                    stringResource(error.messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.imageUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = state.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = state.title,
                onValueChange = vm::onTitleChange,
                label = { Text(stringResource(R.string.field_title_required)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.price,
                    onValueChange = vm::onPriceChange,
                    label = { Text(stringResource(R.string.field_price)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.currency,
                    onValueChange = vm::onCurrencyChange,
                    label = { Text(stringResource(R.string.field_currency_short)) },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                value = state.storeName,
                onValueChange = vm::onStoreChange,
                label = { Text(stringResource(R.string.field_store)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.imageUrl,
                onValueChange = vm::onImageUrlChange,
                label = { Text(stringResource(R.string.field_image_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = vm::onDescriptionChange,
                label = { Text(stringResource(R.string.field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.save(onBack) },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.isEditMode) R.string.action_save else R.string.action_add_to_list,
                    ),
                )
            }
        }
    }
}
