package com.slukhayka.audiobooks.data.identity

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-40 #275 (t1) — the Firebase implementation of [ListenerIdentity]:
 * on first launch it signs in ANONYMOUSLY and immediately elevates the
 * session with GENERATED permanent credentials
 * (`linkWithCredential(EmailAuthProvider.getCredential(genEmail, genPassword))`,
 * public API only) so the account survives past Firebase's anonymous-account
 * lifetime and can be re-entered from the stored pair. Thin glue (like the
 * Firestore stores — no unit tests here); the decision tree's contract is
 * pinned by the fake-based tests over the seam.
 *
 * Degrade-never-throw: every failure path returns a usable profile — a live
 * anonymous session without credentials, or the local-only identity when
 * Firebase is unreachable/not configured. The real account bootstrap simply
 * retries on a later launch.
 *
 * The Firestore handle is injected for spec-40 #276 (`device_bindings` —
 * silent same-phone restore); unused until that ticket lands.
 */
class FirebaseListenerIdentity(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore?,
    private val local: LocalCredentialStore,
    private val fallback: LocalOnlyIdentity,
    private val random: Random = Random.Default
) : ListenerIdentity {

    override suspend fun ensure(): ListenerProfile {
        // 1. A live session (this launch or a previous one) — adopt it.
        val user = runCatching { auth.currentUser }.getOrNull()
        if (user != null) {
            val stored = local.load()
            val hasCredentials = stored?.uid == user.uid &&
                !stored.email.isNullOrBlank() && !stored.password.isNullOrBlank()
            if (!hasCredentials) {
                // A bare anonymous session: elevate it now, otherwise the
                // account would silently expire under the listener.
                linkGenerated(user.uid)?.let { return it }
            }
            return profileFor(user.uid)
        }

        // 2. Credentials persisted by an earlier install (Android Auto Backup
        //    restore or the same-phone device binding) — sign back in with them.
        if (signInFromLocalStore()) return currentOrBootstrap()

        // 3. Fresh install: anonymous auth immediately elevated to a
        //    permanent generated email/password pair.
        return try {
            auth.signInAnonymously().await().user
                ?: error("signInAnonymously returned no user")
            val elevated = linkGenerated(freshNickname = null)
            val uid = runCatching { auth.currentUser?.uid }.getOrNull()
            when {
                elevated != null -> elevated
                uid != null -> profileFor(uid)
                else -> fallback.ensure()
            }
        } catch (e: Exception) {
            // Firebase unreachable / not configured at runtime — local-only
            // profile for this launch; the real bootstrap retries next time.
            fallback.ensure()
        }
    }

    override suspend fun current(): ListenerProfile? {
        val uid = runCatching { auth.currentUser?.uid }.getOrNull()
        if (uid != null) return profileFor(uid)
        // Before any network answer this launch: show what the store holds
        // (a restored backup or last session's record), never fabricate.
        return local.load()?.let { stored ->
            stored.nickname?.let { ListenerProfile(stored.uid, it) }
        }
    }

    override suspend fun setNickname(nickname: String) {
        val clean = nickname.trim().take(LocalOnlyIdentity.NICKNAME_MAX_LENGTH)
        if (clean.isEmpty()) return
        val uid = runCatching { auth.currentUser?.uid }.getOrNull()
            ?: local.load()?.uid
            ?: return
        val stored = local.load()
        val base = if (stored != null && stored.uid == uid) {
            stored.copy(nickname = clean)
        } else {
            StoredCredentials(uid = uid, email = null, password = null, nickname = clean)
        }
        runCatching { local.save(base) }
    }

    /** Spec-40 #276 fills this together with [RecoveryCodec]. */
    override suspend fun recoveryCode(): String? = null

    /** Spec-40 #276 fills this together with [RecoveryCodec]. */
    override suspend fun restoreFromCode(code: String): ListenerProfile? = null

    /**
     * Signs in with the locally persisted generated pair. Returns true when
     * a session is now live; false leaves everything untouched (wrong/
     * expired credentials, offline).
     */
    private suspend fun signInFromLocalStore(): Boolean {
        val stored = local.load() ?: return false
        val email = stored.email ?: return false
        val password = stored.password ?: return false
        return try {
            auth.signInWithCredential(EmailAuthProvider.getCredential(email, password))
                .await().user != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Elevates the current (anonymous) session with fresh generated
     * credentials, retrying with a new pair up to [LINK_ATTEMPTS] times
     * (an email collision is astronomically unlikely but free to absorb).
     * Returns the elevated profile, or null when the elevation failed — the
     * caller then keeps the bare anonymous session honestly (no fabricated
     * credentials are ever persisted).
     */
    private suspend fun linkGenerated(freshNickname: String?): ListenerProfile? {
        val anonUser = auth.currentUser ?: return null
        repeat(LINK_ATTEMPTS) {
            val generated = GeneratedCredentials.generate(random)
            try {
                val credential = EmailAuthProvider.getCredential(generated.email, generated.password)
                val linked = anonUser.linkWithCredential(credential).await().user
                    ?: return@repeat
                // A nickname already chosen on this device (e.g. under the
                // offline local-only identity) carries onto the new account.
                val previous = local.load()
                val nickname = freshNickname
                    ?: previous?.nickname
                    ?: Nicknames.generate(random)
                local.save(
                    StoredCredentials(
                        uid = linked.uid,
                        email = generated.email,
                        password = generated.password,
                        nickname = nickname
                    )
                )
                return ListenerProfile(linked.uid, nickname)
            } catch (e: Exception) {
                // Collision or transient failure → another pair.
            }
        }
        return null
    }

    /** Profile for a known-live uid; persists a default nickname once. */
    private suspend fun profileFor(uid: String): ListenerProfile {
        val stored = local.load()?.takeIf { it.uid == uid }
        val nickname = stored?.nickname ?: Nicknames.generate(random).also { picked ->
            runCatching {
                local.save(
                    StoredCredentials(
                        uid = uid,
                        email = stored?.email,
                        password = stored?.password,
                        nickname = picked
                    )
                )
            }
        }
        return ListenerProfile(uid, nickname)
    }

    /** Shown when neither Firebase nor the store yields anything. */
    private suspend fun currentOrBootstrap(): ListenerProfile =
        current() ?: fallback.ensure()

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    companion object {
        private const val LINK_ATTEMPTS = 3

        /**
         * The default Firebase app's handles, or null when Firebase is not
         * configured (no google-services.json — the same guard as
         * [com.slukhayka.audiobooks.data.metadata.FirestoreBookMetaStore.create]).
         */
        fun create(context: Context, random: Random = Random.Default): ListenerIdentity? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            val local = SharedPreferencesLocalCredentialStore(context)
            val firebase = FirebaseListenerIdentity(
                auth = FirebaseAuth.getInstance(app),
                firestore = runCatching { FirebaseFirestore.getInstance(app) }.getOrNull(),
                local = local,
                fallback = LocalOnlyIdentity(local, random),
                random = random
            )
            return firebase
        }
    }
}
