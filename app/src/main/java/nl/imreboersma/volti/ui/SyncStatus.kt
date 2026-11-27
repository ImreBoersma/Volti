package nl.imreboersma.volti.ui

import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.drive.ImportProgress
import java.util.Locale

/** "just now", "5 min ago", "2 hours ago", "3 days ago". */
fun ago(time: Long, now: Long): String {
    val minutes = (now - time).coerceAtLeast(0) / 60_000
    fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
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
    is SyncState.Downloading -> "Downloading ${count(state.done + 1)} of ${count(state.total)}…"
    is SyncState.Done -> buildString {
        append("Up to date")
        if (state.failed == 1) append(". 1 PDF couldn't be downloaded; it is tried again next time")
        if (state.failed > 1) append(". ${state.failed} PDFs couldn't be downloaded; they are tried again next time")
        if (state.stopped) append(". Stopped at 5,000 entries: the deepest files are left out")
    }
    SyncState.Idle -> if (lastSynced == null) "Not synced yet" else "Synced ${ago(lastSynced, now)}"
    is SyncState.Failed -> syncProblemText(state.problem) + " " + when (lastSynced) {
        null -> "Nothing synced yet."
        else -> "Synced ${ago(lastSynced, now)}."
    }
}

/** What went wrong syncing an ensemble, in the musician's terms: one wording per situation. */
fun syncProblemText(problem: DriveProblem): String = when (problem) {
    DriveProblem.Unreachable -> "Couldn't reach Google Drive."
    DriveProblem.SignedOut -> SIGNED_OUT
    DriveProblem.NotShared -> "This Google account can't see that folder. Is it shared with you?"
    DriveProblem.NotAFolder -> "The link is to a file, not a folder."
    is DriveProblem.DriveError -> driveErrorText(problem.status)
    // Not from a sync, which handles folders; worded as for a file.
    DriveProblem.FileGone, DriveProblem.ImportGone -> fileProblemText(problem)
}

/** Why a file I tapped (or a setlist reached) couldn't be opened. */
fun fileProblemText(problem: DriveProblem): String = when (problem) {
    DriveProblem.Unreachable -> "Couldn't reach Google Drive to download this file."
    DriveProblem.SignedOut -> SIGNED_OUT
    DriveProblem.FileGone -> "This file isn't in Google Drive any more."
    DriveProblem.ImportGone -> "This imported file is gone. Import it again."
    is DriveProblem.DriveError -> driveErrorText(problem.status)
    DriveProblem.NotShared, DriveProblem.NotAFolder -> syncProblemText(problem)
}

/**
 * Why a setlist piece was passed over, said in the reader while another piece shows: "it", not "this file", and
 * nothing to do mid-concert.
 */
fun skippedText(problem: DriveProblem): String = when (problem) {
    DriveProblem.Unreachable -> "It couldn't be downloaded: no connection to Google Drive."
    DriveProblem.SignedOut -> "It couldn't be downloaded: signed out of Google Drive."
    DriveProblem.FileGone -> "It isn't in Google Drive any more."
    DriveProblem.ImportGone -> "Its imported PDF is gone."
    is DriveProblem.DriveError -> "Google Drive answered with an error (${problem.status})."
    DriveProblem.NotShared, DriveProblem.NotAFolder -> "It couldn't be downloaded."
}

/** "Copying 12 of 1,300…" */
fun importProgressText(progress: ImportProgress): String =
    "${progress.step.name} ${count(progress.done + 1)} of ${count(progress.total)}…"

/** A count as messages.md writes numbers: digits, thousands with a comma ("1,300"). */
fun count(n: Int): String = "%,d".format(Locale.ENGLISH, n)

/** No access to Google Drive, wherever it shows: the sign-in button goes beside it. */
const val SIGNED_OUT = "Signed out of Google Drive."

private fun driveErrorText(status: Int) = "Google Drive answered with an error ($status). Try again later."
