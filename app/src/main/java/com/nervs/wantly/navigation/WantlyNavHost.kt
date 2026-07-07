package com.nervs.wantly.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nervs.wantly.R
import com.nervs.wantly.WantlyApp
import com.nervs.wantly.data.GuestCounter
import com.nervs.wantly.ui.screens.addwish.AddWishScreen
import com.nervs.wantly.ui.screens.auth.AuthScreen
import com.nervs.wantly.ui.screens.home.HomeScreen
import com.nervs.wantly.ui.screens.profile.ProfileScreen
import com.nervs.wantly.ui.screens.shared.SharedWishlistScreen
import com.nervs.wantly.ui.screens.wishlist.WishlistDetailScreen

private object Routes {
    const val HOME = "home"
    const val WISHLIST = "wishlist"
    const val ADD_WISH = "addWish"
    const val EDIT_WISH = "editWish"
    const val AUTH = "auth"
    const val PROFILE = "profile"
    const val SHARED = "shared"
    const val ARG_ID = "id"
    const val ARG_WISHLIST_ID = "wishlistId"
    const val ARG_WISH_ID = "wishId"
    const val ARG_TOKEN = "token"
}

private data class TabItem(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    TabItem(Routes.HOME, R.string.nav_lists, Icons.Default.Bookmark),
    TabItem(Routes.PROFILE, R.string.nav_profile, Icons.Default.Person),
)

@Composable
fun WantlyNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as WantlyApp
    val isLoggedIn by app.container.sessionManager.isLoggedIn.collectAsStateWithLifecycle(initialValue = false)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Deep-link: wantlyapp.ru/s/{token} → shared viewer.
    // Обрабатываем стартовый intent один раз при запуске.
    LaunchedEffect(Unit) {
        val data = context.findActivity()?.intent?.data
        if (data != null && data.path?.startsWith("/s/") == true) {
            val token = data.lastPathSegment
            if (!token.isNullOrBlank()) {
                navController.navigate("${Routes.SHARED}/$token")
            }
        }
    }

    // Гостевой prompt: показываем когда гость добавил 3+ желания и ещё не показывали
    var showSignupPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!isLoggedIn) {
            val counter = app.container.guestCounter
            if (counter.getWishCount() >= GuestCounter.PROMPT_THRESHOLD && !counter.wasPromptShown()) {
                showSignupPrompt = true
            }
        }
    }

    val showBottomBar = currentRoute in TABS.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.labelRes)) },
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(onWishlistClick = { id -> navController.navigate("${Routes.WISHLIST}/$id") })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onCreateAccount = {
                        navController.navigate("${Routes.AUTH}/register")
                    },
                )
            }
            composable(
                route = "${Routes.WISHLIST}/{${Routes.ARG_ID}}",
                arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: return@composable
                WishlistDetailScreen(
                    wishlistId = id,
                    onAddWish = { navController.navigate("${Routes.ADD_WISH}/$id") },
                    onBack = { navController.popBackStack() },
                    onEditWish = { wishId -> navController.navigate("${Routes.EDIT_WISH}/$id/$wishId") },
                )
            }
            composable(
                route = "${Routes.ADD_WISH}/{${Routes.ARG_ID}}",
                arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: return@composable
                AddWishScreen(wishlistId = id, onBack = { navController.popBackStack() })
            }
            composable(
                route = "${Routes.EDIT_WISH}/{${Routes.ARG_WISHLIST_ID}}/{${Routes.ARG_WISH_ID}}",
                arguments = listOf(
                    navArgument(Routes.ARG_WISHLIST_ID) { type = NavType.LongType },
                    navArgument(Routes.ARG_WISH_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                val wishlistId = entry.arguments?.getLong(Routes.ARG_WISHLIST_ID) ?: return@composable
                val wishId = entry.arguments?.getLong(Routes.ARG_WISH_ID) ?: return@composable
                AddWishScreen(wishlistId = wishlistId, onBack = { navController.popBackStack() }, wishId = wishId)
            }
            composable(
                route = "${Routes.AUTH}/{mode}",
                arguments = listOf(navArgument("mode") { type = NavType.StringType }),
            ) { entry ->
                val mode = entry.arguments?.getString("mode") == "register"
                AuthScreen(
                    isRegisterMode = mode,
                    onBack = { navController.popBackStack() },
                    onSuccess = {
                        app.container.guestCounter.reset()
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                )
            }
            composable(
                route = "${Routes.SHARED}/{${Routes.ARG_TOKEN}}",
                arguments = listOf(navArgument(Routes.ARG_TOKEN) { type = NavType.StringType }),
            ) { entry ->
                val token = entry.arguments?.getString(Routes.ARG_TOKEN) ?: return@composable
                SharedWishlistScreen(token = token, onBack = { navController.popBackStack() })
            }
        }
    }

    // Гостевое предложение о регистрации
    if (showSignupPrompt) {
        AlertDialog(
            onDismissRequest = {
                showSignupPrompt = false
                app.container.guestCounter.markPromptShown()
            },
            title = { Text(stringResource(R.string.signup_prompt_title)) },
            text = { Text(stringResource(R.string.signup_prompt_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignupPrompt = false
                    app.container.guestCounter.markPromptShown()
                    navController.navigate("${Routes.AUTH}/register")
                }) {
                    Text(
                        stringResource(R.string.signup_prompt_action),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSignupPrompt = false
                    app.container.guestCounter.markPromptShown()
                }) {
                    Text(stringResource(R.string.signup_prompt_later))
                }
            },
        )
    }
}

/** Достаёт Activity из Context (для чтения intent.data deep-link). */
private fun Context.findActivity(): ComponentActivity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
