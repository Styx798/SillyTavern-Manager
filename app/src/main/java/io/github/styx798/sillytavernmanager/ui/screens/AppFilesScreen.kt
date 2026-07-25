package io.github.styx798.sillytavernmanager.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.files.AppFileEntry
import io.github.styx798.sillytavernmanager.core.files.AppFileError
import io.github.styx798.sillytavernmanager.core.files.AppFileRoot
import io.github.styx798.sillytavernmanager.core.files.AppFilesState
import io.github.styx798.sillytavernmanager.core.files.AppTextEditor

@Composable
internal fun AppFilesScreen(
    state: AppFilesState,
    onRootSelected: (AppFileRoot) -> Unit,
    onOpenEntry: (AppFileEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onSaveEditor: (String) -> Unit,
    onCloseEditor: () -> Unit,
    onDelete: (AppFileEntry) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<AppFileEntry?>(null) }

    state.editor?.let { editor ->
        TextEditorDialog(
            editor = editor,
            onDismiss = onCloseEditor,
            onSave = onSaveEditor,
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.files_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        if (entry.isDirectory) {
                            R.string.files_delete_directory_body
                        } else {
                            R.string.files_delete_file_body
                        },
                        entry.name,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDelete(entry)
                    },
                ) {
                    Text(text = stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text(text = stringResource(R.string.files_error_title)) },
            text = { Text(text = fileErrorText(error)) },
            confirmButton = {
                TextButton(onClick = onClearError) {
                    Text(text = stringResource(R.string.action_dismiss))
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.files_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.files_intro),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            ) {
                Text(
                    text = stringResource(R.string.files_warning),
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppFileRoot.entries.forEach { root ->
                    FilterChip(
                        selected = state.listing?.root == root,
                        onClick = { onRootSelected(root) },
                        label = {
                            Text(
                                text = stringResource(
                                    if (root == AppFileRoot.INTERNAL) {
                                        R.string.files_root_internal
                                    } else {
                                        R.string.files_root_external
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }

        state.listing?.let { listing ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = stringResource(R.string.files_current_path),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(
                                text = listing.rootPath +
                                    listing.relativeDirectory.takeIf { it.isNotBlank() }
                                        ?.let { "/$it" }.orEmpty(),
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (listing.relativeDirectory.isNotBlank()) {
                                OutlinedButton(onClick = onNavigateUp) {
                                    Text(text = stringResource(R.string.files_up))
                                }
                            }
                            OutlinedButton(onClick = onRefresh) {
                                Text(text = stringResource(R.string.files_refresh))
                            }
                        }
                    }
                }
            }

            if (listing.entries.isEmpty() && !state.loading) {
                item {
                    Text(
                        text = stringResource(R.string.files_empty),
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(listing.entries, key = { "${listing.root}:${it.relativePath}" }) { entry ->
                AppFileRow(
                    entry = entry,
                    onOpen = { onOpenEntry(entry) },
                    onDelete = { pendingDelete = entry },
                )
            }
        }

        if (state.loading) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun AppFileRow(
    entry: AppFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDirectory || entry.editable, onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(
                        if (entry.isDirectory) R.string.files_type_directory else R.string.files_type_file,
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when {
                        entry.isDirectory -> stringResource(R.string.files_directory_detail)
                        entry.editable -> stringResource(
                            R.string.files_editable_detail,
                            Formatter.formatShortFileSize(context, entry.sizeBytes),
                        )

                        else -> Formatter.formatShortFileSize(context, entry.sizeBytes)
                    },
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.editable) {
                TextButton(onClick = onOpen) {
                    Text(text = stringResource(R.string.files_edit))
                }
            }
            TextButton(onClick = onDelete) {
                Text(text = stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun TextEditorDialog(
    editor: AppTextEditor,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(editor.root, editor.relativePath, editor.text) {
        mutableStateOf(editor.text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.files_editor_title, editor.name)) },
        text = {
            Column {
                Text(
                    text = editor.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp)
                        .padding(top = 10.dp),
                    label = { Text(text = stringResource(R.string.files_editor_content)) },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text) }) {
                Text(text = stringResource(R.string.files_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun fileErrorText(error: AppFileError): String = stringResource(
    when (error) {
        AppFileError.ROOT_UNAVAILABLE -> R.string.files_error_root
        AppFileError.PATH_OUTSIDE_ROOT -> R.string.files_error_path
        AppFileError.RESERVED_CORE_PATH -> R.string.files_error_reserved_core
        AppFileError.READ_FAILED -> R.string.files_error_read
        AppFileError.WRITE_FAILED -> R.string.files_error_write
        AppFileError.DELETE_FAILED -> R.string.files_error_delete
        AppFileError.FILE_TOO_LARGE -> R.string.files_error_too_large
        AppFileError.UNSUPPORTED_FILE -> R.string.files_error_unsupported
    },
)
