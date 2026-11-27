package nl.imreboersma.volti.drive

/**
 * What went wrong reaching Google Drive, a folder or a file in it. `ui/` turns it into words (SyncStatus.kt), so
 * each situation has one wording and tests check the problem, not a sentence.
 */
sealed interface DriveProblem {
    /** No connection, or Google's services couldn't be reached. */
    data object Unreachable : DriveProblem

    /** Volti has no access to Google Drive (not signed in, or the access was declined). */
    data object SignedOut : DriveProblem

    /** The folder isn't visible to this Google account. */
    data object NotShared : DriveProblem

    /** The ensemble's link is to a file, not a folder. */
    data object NotAFolder : DriveProblem

    /** A file is gone from Google Drive, or from the ensemble. */
    data object FileGone : DriveProblem

    /** An imported file whose PDF isn't on the device any more. */
    data object ImportGone : DriveProblem

    /** Google Drive answered with HTTP [status] and nothing else is known. */
    data class DriveError(val status: Int) : DriveProblem
}
