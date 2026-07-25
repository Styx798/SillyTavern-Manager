package io.github.styx798.sillytavernmanager.ui

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.settings.AppLanguage
import io.github.styx798.sillytavernmanager.core.settings.AppSettings
import io.github.styx798.sillytavernmanager.core.settings.ThemeMode
import io.github.styx798.sillytavernmanager.ui.screens.AppFilesScreen
import io.github.styx798.sillytavernmanager.ui.screens.DashboardScreen
import io.github.styx798.sillytavernmanager.ui.screens.LogsScreen
import io.github.styx798.sillytavernmanager.ui.screens.SettingsScreen
import io.github.styx798.sillytavernmanager.ui.screens.TavernScreen
import io.github.styx798.sillytavernmanager.ui.screens.VersionsScreen
import kotlinx.coroutines.launch

private enum class StmDestination(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val showInDrawer: Boolean = true,
) {
    DASHBOARD(R.string.nav_dashboard, Icons.Default.Home),
    TAVERN(R.string.nav_tavern, Icons.Default.PlayArrow),
    VERSIONS(R.string.nav_versions, Icons.Default.Refresh),
    LOGS(R.string.nav_logs, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.nav_settings, Icons.Default.Settings),
    FILES(R.string.files_title, Icons.AutoMirrored.Filled.List, false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StmApp(
    viewModel: StmViewModel,
    settings: AppSettings,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stmCoreState by viewModel.stmCoreState.collectAsStateWithLifecycle()
    val stmCoreConnectionState by viewModel.stmCoreConnectionState.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val appFilesState by viewModel.appFilesState.collectAsStateWithLifecycle()
    val diagnosticLogExportState by viewModel.diagnosticLogExportState
        .collectAsStateWithLifecycle()
    var destinationName by rememberSaveable { mutableStateOf(StmDestination.DASHBOARD.name) }
    val destination = StmDestination.entries
        .firstOrNull { it.name == destinationName }
        ?: StmDestination.DASHBOARD
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = destination == StmDestination.FILES) {
        if (appFilesState.editor != null) {
            viewModel.closeAppFileEditor()
        } else if (appFilesState.listing?.relativeDirectory?.isNotBlank() == true) {
            viewModel.navigateUpInAppFiles()
        } else {
            destinationName = StmDestination.SETTINGS.name
        }
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
                            if (destination == StmDestination.FILES) {
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
            when (destination) {
                StmDestination.DASHBOARD -> DashboardScreen(
                    coreState = stmCoreState,
                    connectionState = stmCoreConnectionState,
                    onStart = viewModel::startCore,
                    onStop = viewModel::stopCore,
                    onOpenTavern = { destinationName = StmDestination.TAVERN.name },
                    modifier = Modifier.padding(innerPadding),
                )

                StmDestination.TAVERN -> TavernScreen(
                    coreState = stmCoreState,
                    connectionState = stmCoreConnectionState,
                    modifier = Modifier.padding(innerPadding),
                )

                StmDestination.VERSIONS -> VersionsScreen(
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
                    modifier = Modifier.padding(innerPadding),
                )

                StmDestination.LOGS -> LogsScreen(
                    coreState = stmCoreState,
                    entries = logEntries,
                    exportState = diagnosticLogExportState,
                    onExport = viewModel::exportDiagnosticLogs,
                    onClearExportResult = viewModel::clearDiagnosticLogExportResult,
                    onCancelCoreJob = viewModel::cancelCoreJob,
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
        }
    }
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
