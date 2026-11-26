package nl.imreboersma.volti.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read-only access to the files I can see, shared folders included. Nothing else is asked for. */
private const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"

sealed interface DriveAccess {
    data class Granted(val token: String) : DriveAccess

    /** Google wants me to pick an account or agree first: [intent] shows that, its result goes to [tokenFrom]. */
    data class NeedsConsent(val intent: PendingIntent) : DriveAccess
}

/**
 * An access token for Drive from Google Play services' Authorization API. Play services keeps the grant and
 * refreshes the token; Volti only ever holds a short-lived token in memory.
 */
class DriveAuth(context: Context) {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    suspend fun authorize(): DriveAccess {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_READONLY))).build()
        val result = client.authorize(request).await()
        val token = result.accessToken
        return when {
            result.hasResolution() -> DriveAccess.NeedsConsent(result.pendingIntent!!)
            token != null -> DriveAccess.Granted(token)
            else -> throw IllegalStateException("Authorization returned neither a token nor a resolution")
        }
    }

    /** The token after the consent screen, or null when it was cancelled. */
    fun tokenFrom(data: Intent?): String? =
        runCatching { client.getAuthorizationResultFromIntent(data).accessToken }.getOrNull()

    /** Drops a token Drive turned down (401), so the next [authorize] gets a fresh one. */
    suspend fun forget(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
