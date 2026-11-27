package nl.imreboersma.volti.setlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.imreboersma.volti.Reader
import nl.imreboersma.volti.db.EnsembleFile
import nl.imreboersma.volti.ui.AppBackButton
import nl.imreboersma.volti.ui.deviceName
import nl.imreboersma.volti.ui.MoreMenu
import nl.imreboersma.volti.ui.RenameFile
import nl.imreboersma.volti.ui.theme.MaxContentWidth
import java.text.DateFormat
import java.util.Date

/** An ensemble's setlists, the newest first, and a way to make one. */
@Composable
fun SetlistsScreen(onOpen: (Long) -> Unit, onBack: () -> Unit, viewModel: SetlistsViewModel = viewModel()) {
    val ensemble by viewModel.ensemble.collectAsStateWithLifecycle()
    val setlists by viewModel.setlists.collectAsStateWithLifecycle()
    var naming by rememberSaveable { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack, "Back to the ensemble")
                Column(modifier = Modifier.weight(1f)) {
                    Text("Setlists", style = MaterialTheme.typography.headlineLarge)
                    Text(ensemble?.name.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { naming = true }) { Text("New setlist") }
            }
            if (setlists?.isEmpty() == true) {
                Text(
                    "No setlists yet. Make one for a concert or a rehearsal, and put its pieces in the order you play them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(setlists.orEmpty(), key = { it.id }) { setlist ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(setlist.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(setlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Made " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(setlist.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (naming) {
        NameDialog(
            title = "New setlist",
            initial = "",
            confirm = "Make it",
            onDone = { name ->
                naming = false
                viewModel.create(name, onOpen)
            },
            onCancel = { naming = false },
        )
    }
}

/** The height of a piece's row: dragging moves a piece one place per row. */
private val ROW = 64.dp

/**
 * A setlist: its pieces in the order I play them. A piece is moved by dragging its handle (or, with TalkBack, by its
 * Move up and Move down actions); the order is saved when it is let go.
 */
@Composable
fun SetlistScreen(
    onPlay: suspend (Reader) -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: SetlistViewModel = viewModel(),
) {
    val setlist by viewModel.setlist.collectAsStateWithLifecycle()
    val pieces by viewModel.pieces.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var renamingFile by rememberSaveable { mutableStateOf<String?>(null) }

    // The order on screen: one state for the screen's life, so a drag always works on the current list. It follows
    // the stored order, except during a drag: an update then (a sync) mustn't move the piece under the finger.
    var order by remember { mutableStateOf(emptyList<SetlistPiece>()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pieces, dragging) { if (dragging == null) order = pieces.orEmpty() }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    fun save() = viewModel.order(order.map { it.fileId })

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack, "Back to the setlists")
                Text(
                    setlist?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                // From the first piece on, turning straight into the next.
                if (!pieces.isNullOrEmpty()) Button(onClick = { viewModel.play(onPlay) }) { Text("Play") }
                OutlinedButton(onClick = { adding = true }) { Text("Add pieces") }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (pieces?.isEmpty() == true) {
                Text("No pieces yet. Add them from your parts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                order.forEachIndexed { index, piece ->
                    key(piece.fileId) {
                        val dragged = dragging == piece.fileId
                        PieceRow(
                            downloading = piece.fileId in downloading,
                            number = index + 1,
                            piece = piece,
                            onRemove = { viewModel.remove(piece.fileId) },
                            onRename = { renamingFile = piece.fileId },
                            onMove = { to ->
                                order = order.moved(index, to)
                                save()
                            },
                            last = order.lastIndex,
                            index = index,
                            handle = { DragHandle(Modifier.pointerInput(piece.fileId) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = piece.fileId
                                        offset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        // Past half a row: the piece takes its neighbour's place.
                                        val at = order.indexOfFirst { it.fileId == piece.fileId }
                                        if (offset > rowPx / 2 && at < order.lastIndex) {
                                            order = order.moved(at, at + 1)
                                            offset -= rowPx
                                        } else if (offset < -rowPx / 2 && at > 0) {
                                            order = order.moved(at, at - 1)
                                            offset += rowPx
                                        }
                                    },
                                    onDragEnd = {
                                        dragging = null
                                        offset = 0f
                                        save()
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        offset = 0f
                                        save()
                                    },
                                )
                            }) },
                            modifier = Modifier
                                .zIndex(if (dragged) 1f else 0f)
                                .graphicsLayer { translationY = if (dragged) offset else 0f },
                        )
                    }
                }
            }
        }
    }
    renamingFile?.let { fileId ->
        // Only once the pieces are in: back after the process ended, they are still loading.
        pieces?.let { list ->
            val file = list.firstOrNull { it.fileId == fileId }?.file
            if (file == null) {
                LaunchedEffect(fileId) { renamingFile = null } // Gone meanwhile.
            } else {
                RenameFile(file, onSave = { name ->
                    viewModel.renameFile(fileId, name)
                    renamingFile = null
                }, onCancel = { renamingFile = null })
            }
        }
    }
    if (adding) {
        AddPieces(candidates, onAdd = { ids ->
            adding = false
            viewModel.add(ids)
        }, onCancel = { adding = false })
    }
    if (renaming) {
        NameDialog("Rename setlist", setlist?.name.orEmpty(), "Rename", onDone = {
            renaming = false
            viewModel.rename(it)
        }, onCancel = { renaming = false })
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${setlist?.name.orEmpty()}?") },
            text = { Text("Only the setlist goes; its pieces stay in the ensemble.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    viewModel.delete(onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PieceRow(
    downloading: Boolean,
    number: Int,
    piece: SetlistPiece,
    index: Int,
    last: Int,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    handle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = piece.file
    val device = deviceName()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(ROW)
            .semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Move up") { onMove(index - 1); true }.takeIf { index > 0 },
                    CustomAccessibilityAction("Move down") { onMove(index + 1); true }.takeIf { index < last },
                )
            },
    ) {
        handle()
        Text("$number", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file?.shownName ?: "No longer in Google Drive", maxLines = 1, overflow = TextOverflow.Ellipsis)
            val note = when {
                file == null -> null
                file.removed -> "Removed from Google Drive"
                file.localMd5 == null -> if (downloading) "Downloading…" else "Not on this $device"
                else -> file.folders.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        MoreMenu(
            "More for ${file?.shownName ?: "this piece"}",
            // A piece whose file is gone has no name left to give.
            listOfNotNull(("Rename" to onRename).takeIf { file != null }, "Remove from the setlist" to onRemove),
        )
    }
}

/** The handle: three lines, dragged to move the piece. */
@Composable
private fun DragHandle(modifier: Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(48.dp).semantics { contentDescription = "Drag to move" }) {
        val ink = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(modifier = Modifier.size(24.dp)) {
            listOf(0.3f, 0.5f, 0.7f).forEach { y ->
                drawLine(ink, Offset(0f, size.height * y), Offset(size.width, size.height * y), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

/** The ensemble's files to add: my parts, or everything; ticked ones go at the end, in the order shown. */
@Composable
private fun AddPieces(candidates: List<EnsembleFile>, onAdd: (List<String>) -> Unit, onCancel: () -> Unit) {
    // My parts unless I have none; until I pick, also while they load.
    var picked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val all = picked ?: candidates.none { it.mine }
    var ticked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val shown = if (all) candidates else candidates.filter { it.mine }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add pieces") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !all, onClick = { picked = false }, label = { Text("My parts") })
                    FilterChip(selected = all, onClick = { picked = true }, label = { Text("All files") })
                }
                if (shown.isEmpty()) Text("Nothing left to add.", modifier = Modifier.padding(top = 16.dp))
                LazyColumn {
                    items(shown, key = { it.fileId }) { file ->
                        val on = file.fileId in ticked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = on, role = Role.Checkbox) {
                                    ticked = if (it) ticked + file.fileId else ticked - file.fileId
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(file.shownName)
                                if (file.folders.isNotEmpty()) {
                                    Text(file.folders.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(shown.map { it.fileId }.filter { it in ticked }) }, enabled = ticked.isNotEmpty()) {
                Text(if (ticked.size == 1) "Add 1" else "Add ${ticked.size}")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name, e.g. Concert 14 December") },
                singleLine = true,
                // Enter does what the button does (android.md).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onDone(name) }),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
