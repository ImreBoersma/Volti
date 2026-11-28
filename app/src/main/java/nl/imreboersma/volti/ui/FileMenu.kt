package nl.imreboersma.volti.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.imreboersma.volti.R
import nl.imreboersma.volti.data.cleanName
import nl.imreboersma.volti.db.EnsembleFile

/**
 * The most lines a name from outside Volti takes (a Drive file or folder, a shared PDF): one with line breaks or
 * thousands of characters mustn't push the parts I need off the stand. The Rename dialogue shows the whole file name.
 */
const val NAME_LINES = 2

/** The most characters of a name from outside inside a sentence ("Bolero.pdf wasn't imported: …"). */
const val NAME_CHARS = 80

/** A name from outside as part of a sentence: on one line, and cut off after [NAME_CHARS]. */
fun inSentence(name: String): String =
    name.replace(Regex("""\s+"""), " ").trim().let { if (it.length > NAME_CHARS) it.take(NAME_CHARS - 1) + "…" else it }

/**
 * The ⋮ at the end of a file's row: what I can do with it besides opening it, so the row keeps room for its name.
 * [items]: label and action. Drawn, as there is no icon library.
 */
@Composable
fun MoreMenu(description: String, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(modifier = Modifier.size(24.dp)) {
                listOf(0.2f, 0.5f, 0.8f).forEach { y -> drawCircle(ink, radius = 2.dp.toPx(), center = Offset(size.width / 2, size.height * y)) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    action()
                })
            }
        }
    }
}

/**
 * My name for [file], one line in my own convention, starting from the one it has or its cleaned file name. Saving
 * it empty, or "Use the file name", goes back to the file's name: [onSave] gets null then.
 */
@Composable
fun RenameFile(file: EnsembleFile, onSave: (String?) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable(file.fileId) { mutableStateOf(file.displayName ?: cleanName(file.name)) }
    fun save() = onSave(chosenName(file, name))
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            // Scrolls: a file name of thousands of characters mustn't push the buttons below out of reach.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rename_part_label)) },
                    singleLine = true,
                    // Enter does what Save does (android.md).
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                // What it is called in Google Drive or where it came from: that stays as it is.
                Text(stringResource(R.string.file_name, file.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (file.displayName != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.use_file_name)) }
            }
        },
        confirmButton = { TextButton(onClick = ::save) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The name [typed] for [file] means: null (its file name) when empty, or when it is only the file name or its cleaned
 * form while I had no name of my own: saving the start unchanged mustn't stop the file following renames in Drive.
 */
fun chosenName(file: EnsembleFile, typed: String): String? {
    val name = typed.trim()
    val unchanged = file.displayName == null && (name == cleanName(file.name) || name == file.name)
    return name.takeUnless { it.isEmpty() || unchanged }
}
