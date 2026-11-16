package com.example.simondicevibe.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.simondicevibe.SimonApp
import com.example.simondicevibe.ui.game.GameScreen
import com.example.simondicevibe.ui.history.HistoryScreen
import com.example.simondicevibe.ui.home.HomeScreen
import com.example.simondicevibe.ui.result.ResultScreen

private object Routes {
    const val HOME = "home"
    const val GAME = "game"
    const val RESULT = "result"
    const val HISTORY = "history"
}

@Composable
fun SimonNavHost() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as SimonApp

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition =
        { slideInHorizontally(tween(380)) { it / 4 } + fadeIn(tween(380)) }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition =
        { slideOutHorizontally(tween(380)) { -it / 6 } + fadeOut(tween(250)) }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = { slideInHorizontally(tween(380)) { -it / 4 } + fadeIn(tween(380)) },
        popExitTransition = { slideOutHorizontally(tween(380)) { it / 4 } + fadeOut(tween(250)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onPlay = { nav.navigate(Routes.GAME) },
                onHistory = { nav.navigate(Routes.HISTORY) },
            )
        }
        composable(Routes.GAME) {
            GameScreen(
                onExit = { nav.popBackStack() },
                onFinished = {
                    nav.navigate(Routes.RESULT) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(Routes.RESULT) {
            val result = app.container.session.lastResult
            if (result == null) {
                LaunchedEffect(Unit) { nav.popBackStack(Routes.HOME, inclusive = false) }
            } else {
                ResultScreen(
                    result = result,
                    onPlayAgain = { nav.navigate(Routes.GAME) { popUpTo(Routes.HOME) } },
                    onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { nav.popBackStack() })
        }
    }
}
