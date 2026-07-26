package io.github.styx798.sillytavernmanager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.settings.AppLanguage
import io.github.styx798.sillytavernmanager.core.settings.AppSettings
import io.github.styx798.sillytavernmanager.core.settings.ThemeMode
import io.github.styx798.sillytavernmanager.ui.screens.AppFilesScreen
import io.github.styx798.sillytavernmanager.ui.screens.DashboardScreen
import io.github.styx798.sillytavernmanager.ui.screens.LogsScreen
import io.github.styx798.sillytavernmanager.ui.screens.SettingsScreen
import io.github.styx798.sillytavernmanager.ui.screens.SillyTavernLogsScreen
import io.github.styx798.sillytavernmanager.ui.screens.TavernScreen
import io.github.styx798.sillytavernmanager.ui.screens.StManagementScreen
import io.github.styx798.sillytavernmanager.ui.screens.VersionsScreen
import io.github.styx798.sillytavernmanager.stmcore.StmCoreWaitKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import kotlinx.coroutines.launch

private enum class StmDestination(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val showInDrawer: Boolean = true,
) {
    DASHBOARD(R.string.nav_dashboard, Icons.Default.Home),
    TAVERN(R.string.nav_tavern, Icons.Default.PlayArrow),
    VERSIONS(R.string.nav_versions, Icons.Default.Refresh),
    ST_LOGS(R.string.nav_st_logs, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.nav_settings, Icons.Default.Settings),
    DIAGNOSTICS(R.string.nav_logs, Icons.AutoMirrored.Filled.List, false),
    ADVANCED_ST(R.string.settings_advanced_st_entry_title, Icons.Default.Settings, false),
    FILES(R.string.files_title, Icons.AutoMirrored.Filled.List, false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StmApp(
    viewModel: StmViewModel,
    settings: AppSettings,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    startInTavern: Boolean = false,
    startInVersions: Boolean = false,
    startInSettings: Boolean = false,
    onOpenCompleteRemoval: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val stmCoreState by viewModel.stmCoreState.collectAsStateWithLifecycle()
    val stmCoreConnectionState by viewModel.stmCoreConnectionState.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val appFilesState by viewModel.appFilesState.collectAsStateWithLifecycle()
    val diagnosticLogExportState by viewModel.diagnosticLogExportState
        .collectAsStateWithLifecycle()
    val sillyTavernLogSnapshot by viewModel.sillyTavernLogSnapshot.collectAsStateWithLifecycle()
    val instanceState by viewModel.instanceState.collectAsStateWithLifecycle()
    val instanceInstallState by viewModel.instanceInstallState.collectAsStateWithLifecycle()
    var destinationName by rememberSaveable(
        startInTavern,
        startInVersions,
        startInSettings,
    ) {
        mutableStateOf(
            when {
                startInTavern -> StmDestination.TAVERN.name
                startInVersions -> StmDestination.VERSIONS.name
                startInSettings -> StmDestination.SETTINGS.name
                else -> StmDestination.DASHBOARD.name
            },
        )
    }
    val destination = StmDestination.entries
        .firstOrNull { it.name == destinationName }
        ?: StmDestination.DASHBOARD
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var openTavernWhenReady by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(destination) {
        val logsVisible = destination == StmDestination.ST_LOGS
        viewModel.setSillyTavernLogsVisible(logsVisible)
        onDispose {
            if (logsVisible) viewModel.setSillyTavernLogsVisible(false)
        }
    }
    var showNotificationPermissionExplanation by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.startCore()
    }
    val startSillyTavern = {
        openTavernWhenReady = true
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            showNotificationPermissionExplanation = true
        } else {
            viewModel.startCore()
        }
    }
    LaunchedEffect(
        openTavernWhenReady,
        stmCoreState.canOpenTavern,
        stmCoreState.runState,
    ) {
        if (openTavernWhenReady && stmCoreState.canOpenTavern) {
            openTavernWhenReady = false
            destinationName = StmDestination.TAVERN.name
        } else if (openTavernWhenReady && stmCoreState.runState == StmCoreRunState.CRASHED) {
            openTavernWhenReady = false
        }
    }

    if (showNotificationPermissionExplanation) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionExplanation = false },
            title = { Text(stringResource(R.string.st_notification_permission_title)) },
            text = { Text(stringResource(R.string.st_notification_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionExplanation = false
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                ) {
                    Text(stringResource(R.string.st_notification_permission_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionExplanation = false
                        viewModel.startCore()
                    },
                ) {
                    Text(stringResource(R.string.st_notification_permission_without))
                }
            },
        )
    }

    stmCoreState.waitPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(prompt.kind.waitTitleRes())) },
            text = { Text(stringResource(prompt.kind.waitBodyRes())) },
            confirmButton = {
                TextButton(onClick = { viewModel.continueWaiting(prompt.operationId) }) {
                    Text(stringResource(R.string.wait_continue))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelWait(prompt.operationId, prompt.kind) },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    BackHandler(enabled = destination == StmDestination.FILES) {
        if (appFilesState.editor != null) {
            viewModel.closeAppFileEditor()
        } else if (appFilesState.listing?.relativeDirectory?.isNotBlank() == true) {
            viewModel.navigateUpInAppFiles()
        } else {
            destinationName = StmDestination.SETTINGS.name
        }
    }

    BackHandler(enabled = destination == StmDestination.DIAGNOSTICS) {
        destinationName = StmDestination.SETTINGS.name
    }

    BackHandler(enabled = destination == StmDestination.ADVANCED_ST) {
        destinationName = StmDestination.SETTINGS.name
    }

    BackHandler(enabled = destination == StmDestination.TAVERN) {
        destinationName = StmDestination.DASHBOARD.name
    }

    ModalNavigationDrawer(
        modifier = modifier.fillMaxSize(),
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                DrawerHeader()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(8.dp))
                StmDestination.entries.filter { it.showInDrawer }.forEach { item ->
                    NavigationDrawerItem(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        selected = destination == item,
                        onClick = {
                            destinationName = item.name
                            coroutineScope.launch { drawerState.close() }
                        },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(text = stringResource(item.labelRes)) },
                    )
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (destination != StmDestination.TAVERN) {
                    TopAppBar(
                        navigationIcon = {
                            if (destination in setOf(
                                    StmDestination.FILES,
                                    StmDestination.DIAGNOSTICS,
                                    StmDestination.ADVANCED_ST,
                                )
                            ) {
                                IconButton(
                                    onClick = { destinationName = StmDestination.SETTINGS.name },
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.files_back_to_settings),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { coroutineScope.launch { drawerState.open() } },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = stringResource(R.string.open_navigation_menu),
                                    )
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = stringResource(destination.labelRes),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (destination) {
                    StmDestination.DASHBOARD -> DashboardScreen(
                        coreState = stmCoreState,
                        connectionState = stmCoreConnectionState,
                        activeInstance = instanceState.activeInstance,
                        onStartSillyTavern = startSillyTavern,
                        onStopSillyTavern = {
                            openTavernWhenReady = false
                            viewModel.stopCore()
                        },
                        onOpenTavern = { destinationName = StmDestination.TAVERN.name },
                        onOpenCore = viewModel::openCore,
                        onRestartCore = {
                            openTavernWhenReady = false
                            viewModel.restartCore()
                        },
                        onCloseCore = {
                            openTavernWhenReady = false
                            viewModel.closeCore()
                        },
                        modifier = Modifier.padding(innerPadding),
                    )

                    StmDestination.TAVERN -> Unit

                    StmDestination.VERSIONS -> StManagementScreen(
                        coreState = stmCoreState,
                        connectionState = stmCoreConnectionState,
                        instanceState = instanceState,
                        installState = instanceInstallState,
                        onInstallStable = { name -> viewModel.installNewInstance(name) },
                        onCancelInstall = viewModel::cancelInstanceInstall,
                        onDismissInstall = viewModel::dismissInstanceInstall,
                        onRenameInstance = viewModel::renameInstance,
                        onSelectInstance = viewModel::selectInstance,
                        onClearInstanceError = viewModel::clearInstanceError,
                        modifier = Modifier.padding(innerPadding),
                    )

                    StmDestination.ST_LOGS -> SillyTavernLogsScreen(
                        snapshot = sillyTavernLogSnapshot,
                        modifier = Modifier.padding(innerPadding),
                    )

                    StmDestination.DIAGNOSTICS -> LogsScreen(
                        coreState = stmCoreState,
                        entries = logEntries,
                        exportState = diagnosticLogExportState,
                        onExport = viewModel::exportDiagnosticLogs,
                        onClearExportResult = viewModel::clearDiagnosticLogExportResult,
                        onCancelCoreJob = viewModel::cancelCoreJob,
                        modifier = Modifier.padding(innerPadding),
                    )

                    StmDestination.ADVANCED_ST -> VersionsScreen(
                        coreState = stmCoreState,
                        connectionState = stmCoreConnectionState,
                        downloadState = downloadState,
                        onStartDownload = viewModel::startDownload,
                        onCancelDownload = viewModel::cancelDownload,
                        onDeleteDownload = viewModel::deleteDownload,
                        onDeleteAllDownloads = viewModel::deleteAllDownloads,
                        onClearDownloadFailure = viewModel::clearDownloadFailure,
                        onImportDownloadedArchive = viewModel::importDownloadedArchive,
                        onInstallDownloadedArchive = viewModel::installDownloadedArchive,
                        onActivateSlot = viewModel::activateSlot,
                        onRollback = viewModel::rollbackActiveSlot,
                        onRemoveSlot = viewModel::removeSlot,
                        onVerifySlot = viewModel::verifySlot,
                        modifier = Modifier.padding(innerPadding),
                    )

                    StmDestination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onThemeModeSelected = onThemeModeSelected,
                        onLanguageSelected = onLanguageSelected,
                        onOpenFiles = {
                            viewModel.openAppFiles()
                            destinationName = StmDestination.FILES.name
                        },
                        onOpenDiagnostics = {
                            destinationName = StmDestination.DIAGNOSTICS.name
                        },
                        onOpenAdvancedSt = {
                            destinationName = StmDestination.ADVANCED_ST.name
                        },
                        onOpenCompleteRemoval = onOpenCompleteRemoval,
                        modifier = Modifier.padding(innerPadding),
                    )

                    StmDestination.FILES -> AppFilesScreen(
                        state = appFilesState,
                        onRootSelected = viewModel::selectAppFileRoot,
                        onOpenEntry = viewModel::openAppFile,
                        onNavigateUp = viewModel::navigateUpInAppFiles,
                        onRefresh = viewModel::refreshAppFiles,
                        onSaveEditor = viewModel::saveAppFile,
                        onCloseEditor = viewModel::closeAppFileEditor,
                        onDelete = viewModel::deleteAppFile,
                        onClearError = viewModel::clearAppFileError,
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                TavernScreen(
                    coreState = stmCoreState,
                    connectionState = stmCoreConnectionState,
                    modifier = if (destination == StmDestination.TAVERN) {
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    } else {
                        Modifier.size(0.dp)
                    },
                )
            }
        }
    }
}

@StringRes
private fun StmCoreWaitKind.waitTitleRes(): Int = when (this) {
    StmCoreWaitKind.NPM_INSTALL -> R.string.wait_npm_title
    StmCoreWaitKind.BUNDLE_BUILD -> R.string.wait_bundle_title
    StmCoreWaitKind.RUNNABLE_ACCEPTANCE -> R.string.wait_acceptance_title
    StmCoreWaitKind.SILLY_TAVERN_START -> R.string.wait_start_title
}

@StringRes
private fun StmCoreWaitKind.waitBodyRes(): Int = when (this) {
    StmCoreWaitKind.NPM_INSTALL -> R.string.wait_npm_body
    StmCoreWaitKind.BUNDLE_BUILD -> R.string.wait_bundle_body
    StmCoreWaitKind.RUNNABLE_ACCEPTANCE -> R.string.wait_acceptance_body
    StmCoreWaitKind.SILLY_TAVERN_START -> R.string.wait_start_body
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "STM",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
