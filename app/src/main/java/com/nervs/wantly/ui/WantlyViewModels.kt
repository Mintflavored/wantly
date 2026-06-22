package com.nervs.wantly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nervs.wantly.WantlyApp
import com.nervs.wantly.di.AppContainer

/** Создаёт ViewModel через ручной DI-контейнер приложения. */
@Composable
inline fun <reified VM : ViewModel> rememberAppViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val context = LocalContext.current
    return viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) { "WantlyApp недоступен" }
                create((app as WantlyApp).container)
            }
        },
    )
}
