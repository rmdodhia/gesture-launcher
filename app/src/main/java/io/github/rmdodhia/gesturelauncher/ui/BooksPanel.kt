package io.github.rmdodhia.gesturelauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.books.BookSource
import io.github.rmdodhia.gesturelauncher.books.Library
import io.github.rmdodhia.gesturelauncher.books.SyncState
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.Book
import io.github.rmdodhia.gesturelauncher.core.BookApp
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import kotlinx.coroutines.launch

/** "Book" tab of the action picker: one shelf across Kindle, Libby and Play Books. */
@Composable
internal fun BooksPanel(
    books: List<Book>,
    library: Library,
    runner: ActionRunner,
    current: OpenUri?,
    onPick: (Action) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val states = remember { mutableStateMapOf<BookApp, SyncState>() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(current?.let { Links.bookFrom(it) }) }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun sync(source: BookSource, interactive: Boolean) {
        val before = states[source.app] ?: SyncState.Idle
        states[source.app] = SyncState.Syncing
        scope.launch { states[source.app] = library.sync(source, interactive) ?: before }
    }

    LaunchedEffect(library) { library.sources.forEach { sync(it, interactive = false) } }

    fun persist(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                ErrorLog.record(what, e)
                message = "Couldn't save: ${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            library.sources.forEach { source ->
                SourceStatus(source.app, states[source.app] ?: SyncState.Idle, books.count { it.app == source.app }) { interactive ->
                    sync(source, interactive)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kindle & Libby: open the book's page, tap Share → Gesture Launcher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { adding = true }, modifier = Modifier.testTag("addBook")) { Text("Add by link") }
            }
            OutlinedTextField(
                query, { query = it }, label = { Text("Search books") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bookSearch"),
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("booksMessage")) }
        }

        val shown = books.filter {
            query.isBlank() || it.title.contains(query, true) || it.author?.contains(query, true) == true
        }
        val reading = shown.filter { it.reading }
        val shelf = shown.filterNot { it.reading }.sortedBy { it.title.lowercase() }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (books.isEmpty()) {
                item {
                    Text(
                        "No books yet. Connect Play Books above, or share a book from Kindle or Libby.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (shown.isEmpty()) {
                item { Text("No books match \"$query\".", modifier = Modifier.padding(16.dp)) }
            }
            if (reading.isNotEmpty()) {
                item { SectionHeader("Reading now") }
                items(reading, key = { "r_" + it.key }) { b ->
                    BookRow(b, selected?.key == b.key, onClick = { selected = b }, onReading = { r ->
                        persist("book reading") { library.setReading(b.key, r) }
                    }, onRemove = {
                        if (selected?.key == b.key) selected = null
                        persist("remove book") { library.remove(b.key) }
                    })
                }
            }
            if (shelf.isNotEmpty()) {
                item { SectionHeader("Library") }
                items(shelf, key = { "l_" + it.key }) { b ->
                    BookRow(b, selected?.key == b.key, onClick = { selected = b }, onReading = { r ->
                        persist("book reading") { library.setReading(b.key, r) }
                    }, onRemove = {
                        if (selected?.key == b.key) selected = null
                        persist("remove book") { library.remove(b.key) }
                    })
                }
            }
        }

        selected?.let { book -> SelectedBar(book, runner, onPick) }
    }

    if (adding) {
        AddBookDialog(
            onDismiss = { adding = false },
            onAdd = { book ->
                adding = false
                selected = book
                persist("add book") { library.add(book) }
            },
        )
    }
}

@Composable
private fun SourceStatus(app: BookApp, state: SyncState, count: Int, onSync: (interactive: Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("source_${app.name}")) {
        val text = when (state) {
            SyncState.Idle, is SyncState.Done -> "${app.label}: $count books"
            SyncState.Syncing -> "${app.label}: updating…"
            SyncState.NeedsConsent -> "${app.label}: not connected"
            is SyncState.Failed -> state.message
        }
        Text(
            text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            color = if (state is SyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        when (state) {
            SyncState.Syncing -> CircularProgressIndicator(Modifier.padding(8.dp))
            SyncState.NeedsConsent -> Button(onClick = { onSync(true) }, modifier = Modifier.testTag("connect_${app.name}")) {
                Text("Connect")
            }
            else -> TextButton(onClick = { onSync(true) }, modifier = Modifier.testTag("refresh_${app.name}")) {
                Text(if (state is SyncState.Failed) "Retry" else "Refresh")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun BookRow(
    b: Book,
    isSelected: Boolean,
    onClick: () -> Unit,
    onReading: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("book_${b.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(b.author, b.app.label).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Synced books follow the service's shelves, so only hand-added books can be edited here.
        if (!b.synced) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("bookMenu_${b.key}")) {
                    Icon(Icons.Filled.MoreVert, "Book options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (b.reading) "Move to Library" else "Mark as reading now") },
                        onClick = { menu = false; onReading(!b.reading) },
                    )
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SelectedBar(book: Book, runner: ActionRunner, onPick: (Action) -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember(book.key) { mutableStateOf<String?>(null) }
    val action = Links.actionFor(book)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${book.title} (${book.app.label})", style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    result = null
                    scope.launch { result = runner.run(action) ?: "Opened — check it's the right book." }
                }) { Text("Test") }
                Button(onClick = { onPick(action) }, modifier = Modifier.testTag("useAction")) { Text("Use this action") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("testResult")) }
        }
    }
}

/** Parses a pasted Kindle/Amazon, Libby or Play Books link (or bare ASIN) into a [Book]. */
internal fun bookFromInput(link: String, title: String, reading: Boolean): Book? {
    val t = title.trim().takeIf { it.isNotEmpty() } ?: return null
    val s = link.trim()
    val book = if (!s.contains("://")) {
        Links.extractAsin(s)?.let { Book(BookApp.KINDLE, it, t, uri = Links.kindleUri(it)) }
    } else {
        Links.actionFromShare(s, t, null)?.let { Links.bookFrom(it) }
    }
    return book?.copy(title = t, reading = reading, synced = false)
}

@Composable
private fun AddBookDialog(onDismiss: () -> Unit, onAdd: (Book) -> Unit) {
    var link by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(true) }
    val book = bookFromInput(link, title, reading)
    val linkError = link.isNotBlank() && bookFromInput(link, "x", reading) == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title, { title = it }, label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("addBookTitle"),
                )
                OutlinedTextField(
                    link, { link = it }, label = { Text("Link") }, singleLine = true,
                    isError = linkError,
                    supportingText = {
                        Text(if (linkError) "Not a Kindle/Amazon, Libby or Play Books link" else "Amazon/Kindle link or ASIN, Libby link, or Play Books link")
                    },
                    modifier = Modifier.fillMaxWidth().testTag("addBookLink"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = reading, onCheckedChange = { reading = it })
                    Text("Reading now")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = book != null, onClick = { book?.let(onAdd) }, modifier = Modifier.testTag("addBookConfirm")) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
