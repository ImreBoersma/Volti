package nl.imreboersma.volti.ui

import android.content.res.Resources
import nl.imreboersma.volti.R
import nl.imreboersma.volti.drive.SyncState
import nl.imreboersma.volti.drive.DriveProblem
import nl.imreboersma.volti.drive.DRIVE_WALK_LIMIT
import nl.imreboersma.volti.drive.ImportProgress
import nl.imreboersma.volti.drive.ImportStep
import nl.imreboersma.volti.drive.MAX_PDF_BYTES
import nl.imreboersma.volti.drive.Rejection
import nl.imreboersma.volti.setlist.Skip
import nl.imreboersma.volti.setlist.SkipReason
import java.text.NumberFormat

/**
 * "Synced just now", "Synced 5 min ago", "Synced 2 hours ago", "Synced 3 days ago"; with a full stop as a [sentence]
 * after a problem, without as a label.
 */
fun synced(res: Resources, time: Long, now: Long, sentence: Boolean): String {
    val minutes = (now - time).coerceAtLeast(0) / 60_000
    fun plural(id: Int, n: Long) = res.getQuantityString(id, n.toInt(), count(res, n.toInt()))
    return when {
        minutes < 1 -> res.getString(if (sentence) R.string.synced_just_now_sentence else R.string.synced_just_now)
        minutes < 60 -> plural(if (sentence) R.plurals.synced_minutes_ago_sentence else R.plurals.synced_minutes_ago, minutes)
        minutes < 24 * 60 -> plural(if (sentence) R.plurals.synced_hours_ago_sentence else R.plurals.synced_hours_ago, minutes / 60)
        else -> plural(if (sentence) R.plurals.synced_days_ago_sentence else R.plurals.synced_days_ago, minutes / (24 * 60))
    }
}

/**
 * What the sync is doing, or how fresh the copy on the tablet is. When it can't reach Drive, the copy that is here
 * still works: say from when it is.
 */
fun syncText(res: Resources, state: SyncState, lastSynced: Long?, now: Long): String = when (state) {
    SyncState.Listing -> res.getString(R.string.sync_checking)
    // Checked, waiting for the other ensembles' checks before its downloads start.
    is SyncState.Downloading -> if (state.now == null) {
        res.getQuantityString(R.plurals.sync_to_download, state.total, count(res, state.total))
    } else {
        res.getString(R.string.sync_downloading, count(res, state.done + 1), count(res, state.total))
    }
    is SyncState.Done -> when {
        state.failed > 0 && state.stopped ->
            res.getQuantityString(R.plurals.sync_up_to_date_failed_stopped, state.failed, count(res, state.failed), count(res, DRIVE_WALK_LIMIT))
        state.failed > 0 -> res.getQuantityString(R.plurals.sync_up_to_date_failed, state.failed, count(res, state.failed))
        state.stopped -> res.getString(R.string.sync_up_to_date_stopped, count(res, DRIVE_WALK_LIMIT))
        else -> res.getString(R.string.sync_up_to_date)
    }
    SyncState.Idle -> if (lastSynced == null) res.getString(R.string.sync_not_yet) else synced(res, lastSynced, now, sentence = false)
    // Two sentences: what went wrong, then how fresh the copy here is.
    is SyncState.Failed -> syncProblemText(res, state.problem) + " " + when (lastSynced) {
        null -> res.getString(R.string.sync_nothing_yet_sentence)
        else -> synced(res, lastSynced, now, sentence = true)
    }
}

/** What went wrong syncing an ensemble, in the musician's terms: one wording per situation. */
fun syncProblemText(res: Resources, problem: DriveProblem): String = when (problem) {
    DriveProblem.Unreachable -> res.getString(R.string.drive_unreachable)
    DriveProblem.SignedOut -> res.getString(R.string.drive_signed_out)
    DriveProblem.NotShared -> res.getString(R.string.drive_not_shared)
    DriveProblem.NotAFolder -> res.getString(R.string.drive_not_a_folder)
    is DriveProblem.DriveError -> res.getString(R.string.drive_error, problem.status)
    // Not from a sync, which handles folders; worded as for a file.
    DriveProblem.FileGone, DriveProblem.ImportGone -> fileProblemText(res, problem)
}

/** Why a file I tapped (or a setlist reached) couldn't be opened. */
fun fileProblemText(res: Resources, problem: DriveProblem): String = when (problem) {
    DriveProblem.Unreachable -> res.getString(R.string.file_unreachable)
    DriveProblem.SignedOut -> res.getString(R.string.drive_signed_out)
    DriveProblem.FileGone -> res.getString(R.string.file_gone)
    DriveProblem.ImportGone -> res.getString(R.string.file_import_gone)
    is DriveProblem.DriveError -> res.getString(R.string.drive_error, problem.status)
    DriveProblem.NotShared, DriveProblem.NotAFolder -> syncProblemText(res, problem)
}

/**
 * A setlist piece passed over, said in the reader while another piece shows: which piece, then why. "It", not "this
 * part", and nothing to do mid-concert.
 */
fun skippedText(res: Resources, skip: Skip): String {
    val which = if (skip.name == null) res.getString(R.string.skipped_unnamed) else res.getString(R.string.skipped_named, skip.name)
    val why = when (skip.why) {
        SkipReason.Unreachable -> res.getString(R.string.skipped_unreachable)
        SkipReason.SignedOut -> res.getString(R.string.skipped_signed_out)
        SkipReason.Gone -> res.getString(R.string.skipped_gone)
        SkipReason.ImportGone -> res.getString(R.string.skipped_import_gone)
        SkipReason.DriveError -> res.getString(R.string.skipped_drive_error, skip.status)
        SkipReason.NotDownloaded -> res.getString(R.string.skipped_not_downloaded)
        SkipReason.TooSlow -> res.getString(R.string.skipped_too_long)
        SkipReason.NotOpened -> res.getString(R.string.skipped_not_opened)
    }
    return "$which $why" // Two sentences.
}

/** "Copying 12 of 1,300…"; "Copying…" while the total isn't known yet (0). */
fun importProgressText(res: Resources, progress: ImportProgress): String = when {
    progress.total == 0 && progress.step == ImportStep.Copying -> res.getString(R.string.copying)
    progress.total == 0 -> res.getString(R.string.importing)
    progress.step == ImportStep.Copying -> res.getString(R.string.import_copying, count(res, progress.done + 1), count(res, progress.total))
    else -> res.getString(R.string.import_importing, count(res, progress.done + 1), count(res, progress.total))
}

/** Per PDF that an import couldn't take in, why, in my terms: one sentence each. */
fun notImportedText(res: Resources, problems: List<Pair<String, Rejection>>, device: Device) = problems.joinToString("\n") { (name, why) ->
    when (why) {
        Rejection.TooLarge -> res.getString(R.string.not_imported_too_large, name, count(res, (MAX_PDF_BYTES / 1024 / 1024).toInt()))
        Rejection.NotAPdf, Rejection.WrongChecksum -> res.getString(R.string.not_imported_not_a_pdf, name)
        Rejection.NoSpace -> res.getString(
            device.pick(R.string.not_imported_no_space_tablet, R.string.not_imported_no_space_phone, R.string.not_imported_no_space_device),
            name,
        )
        Rejection.Unreadable -> res.getString(R.string.not_imported_unreadable, name)
    }
}

/** A count as messages.md writes numbers: digits, thousands in the language's format ("1,300", "1.300"). */
fun count(res: Resources, n: Int): String = NumberFormat.getIntegerInstance(res.configuration.locales[0]).format(n)
