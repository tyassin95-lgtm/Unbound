package com.unbound.rpg.ui

import android.content.Intent
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.unbound.core.journal.Journal
import com.unbound.rpg.domain.AppContainer
import com.unbound.rpg.ui.journal.JournalScreen
import com.unbound.rpg.ui.play.PlayScreen
import com.unbound.rpg.ui.play.PlayViewModel
import com.unbound.rpg.ui.saves.SavesScreen
import com.unbound.rpg.ui.settings.SettingsScreen
import com.unbound.rpg.ui.setup.NewGameScreen
import com.unbound.rpg.ui.setup.OnboardingScreen
import kotlinx.coroutines.launch

private object Routes {
    const val ONBOARDING = "onboarding"
    const val SAVES = "saves"
    const val NEW_GAME = "new_game"
    const val SETTINGS = "settings"
    const val PLAY = "play/{gameId}"
    const val JOURNAL = "journal/{gameId}"

    fun play(gameId: String) = "play/$gameId"
    fun journal(gameId: String) = "journal/$gameId"
}

@Composable
fun UnboundApp(container: AppContainer) {
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel(factory = factoryFor { AppViewModel(container) })

    val settingsState by appViewModel.settings.collectAsState()
    val start = remember {
        if (container.credentials.hasKey()) Routes.SAVES else Routes.ONBOARDING
    }

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onConnect = appViewModel::storeKey,
                onSkip = {
                    appViewModel.markOnboarded()
                    navController.navigate(Routes.SAVES) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
                testing = settingsState.testing,
                testResult = settingsState.testResult,
                testSucceeded = settingsState.testSucceeded,
                onTest = { appViewModel.testConnection() },
                onContinue = {
                    appViewModel.markOnboarded()
                    navController.navigate(Routes.SETTINGS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
            )
        }

        composable(Routes.SAVES) {
            val saves by appViewModel.saves.collectAsState()
            val context = LocalContext.current
            val exported by appViewModel.exported.collectAsState()

            LaunchedEffect(Unit) { appViewModel.refreshSaves() }

            // Handing the save to the OS share sheet keeps UNBOUND out of the storage-permission
            // business entirely, and the player chooses where it lands.
            LaunchedEffect(exported) {
                exported?.let { text ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_TITLE, "unbound-save.json")
                    }
                    context.startActivity(Intent.createChooser(intent, "Export save"))
                    appViewModel.consumeExport()
                }
            }

            SavesScreen(
                saves = saves,
                hasCredential = appViewModel.hasCredential(),
                onContinue = { navController.navigate(Routes.play(it)) },
                onNewGame = { navController.navigate(Routes.NEW_GAME) },
                onRename = appViewModel::renameGame,
                onDuplicate = appViewModel::duplicateGame,
                onDelete = appViewModel::deleteGame,
                onExport = appViewModel::exportGame,
                onImport = { navController.navigate(Routes.SETTINGS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.NEW_GAME) {
            val creating by appViewModel.creating.collectAsState()
            val created by appViewModel.createdGameId.collectAsState()

            LaunchedEffect(created) {
                created?.let { gameId ->
                    appViewModel.consumeCreatedGame()
                    navController.navigate(Routes.play(gameId)) {
                        popUpTo(Routes.NEW_GAME) { inclusive = true }
                    }
                }
            }

            NewGameScreen(
                onCreate = appViewModel::createGame,
                onCancel = { navController.popBackStack() },
                creating = creating,
            )
        }

        composable(Routes.SETTINGS) {
            LaunchedEffect(Unit) { appViewModel.refreshUsage() }
            SettingsScreen(
                state = settingsState,
                onBack = { navController.popBackStack() },
                onStoreKey = appViewModel::storeKey,
                onRemoveKey = appViewModel::removeKey,
                onTestKey = { appViewModel.testConnection() },
                onRefreshModels = { appViewModel.refreshModels() },
                onPickTextModel = appViewModel::setTextModel,
                onPickImageModel = appViewModel::setImageModel,
                onImageMode = appViewModel::setImageMode,
                onNarrationLength = appViewModel::setNarrationLength,
                onSuggestions = appViewModel::setSuggestions,
                onReducedMotion = appViewModel::setReducedMotion,
                onDeveloperMode = appViewModel::setDeveloperMode,
                onLimits = appViewModel::setLimits,
                onClearImageCache = appViewModel::clearImageCache,
            )
        }

        composable(Routes.PLAY) { entry ->
            val gameId = entry.arguments?.getString("gameId") ?: return@composable
            val playViewModel: PlayViewModel = viewModel(
                key = "play-$gameId",
                factory = factoryFor { PlayViewModel(container, gameId) },
            )
            PlayScreen(
                viewModel = playViewModel,
                onOpenJournal = { navController.navigate(Routes.journal(gameId)) },
                onOpenMenu = { navController.navigate(Routes.SETTINGS) },
                onBack = {
                    appViewModel.refreshSaves()
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.JOURNAL) { entry ->
            val gameId = entry.arguments?.getString("gameId") ?: return@composable
            var journal by remember { mutableStateOf<Journal?>(null) }
            LaunchedEffect(gameId) {
                journal = runCatching { container.journal.build(gameId) }.getOrNull()
            }
            JournalScreen(journal = journal, onBack = { navController.popBackStack() })
        }
    }
}

/** Tiny factory so ViewModels can take constructor arguments without pulling in a DI framework. */
private inline fun <reified T : ViewModel> factoryFor(crossinline create: () -> T) =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
    }
