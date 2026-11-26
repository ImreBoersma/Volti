package nl.imreboersma.volti.ui

import nl.imreboersma.volti.drive.SyncState

/** "just now", "5 minutes ago", "2 hours ago", "3 days ago". */
fun ago(time: Long, now: Long): String {
    val minutes = (now - time).coerceAtLeast(0) / 60_000
    fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> plural(minutes, "minute")
        minutes < 24 * 60 -> plural(minutes / 60, "hour")
        else -> plural(minutes / (24 * 60), "day")
    }
}

/**
 * What the sync is doing, or how fresh the copy on the tablet is. When it can't reach Drive, the copy that is here
 * still works: say from when it is.
 */
fun syncText(state: SyncState, lastSynced: Long?, now: Long): String = when (state) {
    SyncState.Listing -> "Checking Google Drive…"
    is SyncState.Downloading -> "Downloading ${state.done + 1} of ${state.total}…"
    is SyncState.Done -> buildString {
        append("Up to date")
        if (state.failed == 1) append(". 1 PDF couldn't be downloaded; it is tried again next time")
        if (state.failed > 1) append(". ${state.failed} PDFs couldn't be downloaded; they are tried again next time")
        if (state.stopped) append(". Stopped at 5,000 entries: the deepest files are left out")
    }
    SyncState.Idle -> if (lastSynced == null) "Not synced yet" else "Synced ${ago(lastSynced, now)}"
    is SyncState.Failed -> state.message + " " + when (lastSynced) {
        null -> "Nothing has been synced yet."
        else -> "What was synced ${ago(lastSynced, now)} is here."
    }
}
