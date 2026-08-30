package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncWorker
import com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_BAD_PASSWORD
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_SERVER
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_USER
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_USER_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.Session
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.util.CLEARTEXT_BLOCKED_MESSAGE
import com.gmail.volkovskiyda.jellyshelf.util.SESSION_EXPIRED_MESSAGE
import com.gmail.volkovskiyda.jellyshelf.util.isCleartextBlocked
import com.gmail.volkovskiyda.jellyshelf.util.isUnauthorized
import com.gmail.volkovskiyda.jellyshelf.util.normalizeServerUrl
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Sentinel for the "all collections" (root) scope. */
const val ROOT_SCOPE_ID = ""

/**
 * Persisted name of the root scope. Blank — the UI substitutes the localized "all collections"
 * label at render time, so the stored value can't freeze in whatever language it was saved in.
 */
const val ROOT_SCOPE_PATH = ""
private const val CRUMB_SEPARATOR = " › "

/** A folder in the Jellyfin item tree. */
data class FolderRef(val id: String, val name: String, val path: String?)

/**
 * One line of feedback, rendered under the control that produced it.
 *
 * The screen keeps four of these rather than one shared slot ([SettingsUiState.authStatus] and
 * friends). A single line had to arbitrate — "a local operation's message wins while it runs" —
 * and still lost: a sync finishing mid-sign-in replaced the sign-in error, and "Connected — 3
 * users" sitting under the sign-in button never said which button it was answering.
 */
data class StatusLine(val text: String, val isError: Boolean = false)

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val indexUrl: String = "",
    /** Base URL of the bot server's metadata API; blank when not configured. */
    val metadataApiUrl: String = "",
    /** Bearer token that API requires; blank when not configured. */
    val metadataApiToken: String = "",
    /**
     * The last sync's metadata API fetch came back 401. Set from the sync result and cleared the
     * moment either API field is edited — the red state exists to prompt exactly that edit.
     */
    val metadataApiAuthFailed: Boolean = false,
    /** The theme override and which way the next tap of the switch moves. */
    val themeState: ThemeState = ThemeState(),
    // Sign-in fields. The password lives here only until the token comes back — see [signIn].
    val username: String = "",
    val password: String = "",
    /** True once a user token is held: the default, user-scoped auth path. */
    val signedIn: Boolean = false,
    /** Advanced playback handoff: credential in the URL query rather than an intent header. */
    val tokenInQuery: Boolean = false,
    val users: List<User> = emptyList(),
    val selectedUserId: String = "",
    val selectedUserName: String = "",
    // Persisted sync scope. A blank path means the root ("all collections") scope.
    val selectedScopeId: String = ROOT_SCOPE_ID,
    val selectedScopePath: String = ROOT_SCOPE_PATH,
    // Ephemeral folder-browser state.
    val browserOpen: Boolean = false,
    val breadcrumb: List<FolderRef> = emptyList(),
    val childFolders: List<FolderRef> = emptyList(),
    val loadingFolders: Boolean = false,
    val busy: Boolean = false,
    /**
     * The sign-in form's line: signing in, signed in as, a rejected password, entering or leaving
     * the demo. Rendered directly above the button that produced it — an authentication error at
     * the far end of a scrolling column is an error nobody reads.
     */
    val authStatus: StatusLine? = null,
    /** The advanced API-key connect's line, under its own button. */
    val connectStatus: StatusLine? = null,
    /** The sync-scope section's line: folder-browser failures, and the "check the scope" nudge. */
    val scopeStatus: StatusLine? = null,
    /**
     * Sync's line, under **Sync now**. Fed by WorkManager rather than by this screen, so it can
     * describe a sync started on an earlier visit — which is exactly why it is not shared with the
     * three above.
     */
    val syncStatus: StatusLine? = null,
    val lastSyncAt: Long = 0L,
    /**
     * A sync is in flight. Distinct from [busy], which any settings operation raises — a sign-in
     * must not make the index URL look sync-protected.
     */
    val syncRunning: Boolean = false,
    /** The library holds seeded demo data rather than a real server's. */
    val demoMode: Boolean = false,
    /**
     * A server-wide API key is *persisted* — a Connect actually succeeded — as opposed to the
     * [apiKey] field merely holding text. What makes Sign out reachable on the advanced path,
     * where [signedIn] is false; reading the field instead would swap the button out from under
     * someone halfway through typing a key.
     */
    val apiKeyConnected: Boolean = false,
    /** Which channel the app watches for a newer build of itself; [UpdateSource.NONE] is off. */
    val updateSource: UpdateSource = UpdateSource.NONE,
    /** A check is in flight, from "Check now". */
    val checkingUpdate: Boolean = false,
    /** A tester sign-in is in flight, from selecting the App Distribution channel. */
    val signingInTester: Boolean = false,
    /** Why the last check or sign-in failed. Rendered as a sentence, never as a silent no-op. */
    val updateError: UpdateCheckError? = null,
    /**
     * The last "Check now" came back with nothing newer. Mutually exclusive with [updateError]:
     * a check either failed or produced an answer, and this is the answer nobody would otherwise
     * see — without it, a successful check that finds nothing looks exactly like one that silently
     * did nothing.
     */
    val upToDate: Boolean = false,
    val lastUpdateCheckAt: Long = 0L,
    /**
     * Whether this is a debug build, which hides the whole Updates section (decision 16).
     *
     * **Defaults to `false`, and that default is load-bearing.** Previews and screenshot tests
     * build the *debug* variant, so a `true` default would hide the section from every golden and
     * quietly delete its coverage. `false` means they render the shape that actually ships. The
     * real value is injected from `BuildInfo.isDebug` in the ViewModel — never read `BuildConfig`
     * from a composable.
     */
    val isDebugBuild: Boolean = false,
    /**
     * The installed build's `versionName`, printed as the last line of the screen.
     *
     * Blank by default and blank means "print nothing": a test or preview that never set it would
     * otherwise render "Version " with nothing after it. Injected from `BuildInfo.versionName` in
     * the ViewModel — never read `BuildConfig` from a composable, which builds debug for previews.
     */
    val versionName: String = "",
) {
    /** ParentId of the folder currently being browsed ("" == root). */
    val currentParentId: String get() = breadcrumb.lastOrNull()?.id ?: ROOT_SCOPE_ID

    /**
     * Whether the metadata index URL is offered at all: it is a power-user field whose value only
     * means anything once the app can reach the server, so it stays hidden until there are
     * credentials. Either path counts — a signed-in user token, or an API key from the advanced
     * section — because both leave the app able to sync.
     */
    val canEditIndex: Boolean get() = signedIn || apiKey.isNotBlank()

    /**
     * Whether to offer the demo. Only with nothing configured — either credential counts, on the
     * same reading as [canEditIndex] — and only when the library isn't already a demo, since the
     * way *out* of one is Sign out rather than a second tap of this.
     */
    val canTryDemo: Boolean get() = !demoMode && !signedIn && apiKey.isBlank()

    /**
     * Whether there is anything to sign out *of*: a user token, a demo library, or a persisted
     * API key. All three leave the install holding data, and Sign out is the one action that
     * clears it — which is why the API-key path counts even though it never "signed in".
     */
    val canSignOut: Boolean get() = signedIn || demoMode || apiKeyConnected

    /**
     * The picked user is the demo server's fake one. There is no item tree behind it, so the sync
     * scope section — whose every control is a request — has nothing to offer and stays hidden.
     */
    val demoUserSelected: Boolean get() = selectedUserId == DEMO_USER_ID

    /**
     * Whether editing the index URL now has something to break. Once a sync has run — or is
     * running — a mistyped URL means a half-populated library and no obvious cause, so the field
     * locks behind a deliberate unlock instead of staying open.
     */
    val indexProtected: Boolean get() = lastSyncAt != 0L || syncRunning

    /**
     * A metadata API URL without its token can only produce 401s, so the token field flags the
     * gap the moment the URL is set — before a sync has to fail to say the same thing.
     */
    val metadataApiTokenMissing: Boolean
        get() = metadataApiUrl.isNotBlank() && metadataApiToken.isBlank()

    /**
     * Path of the folder currently being browsed, from server-provided folder names — no
     * localized root prefix, so it is safe to persist. Blank at the root.
     */
    val currentPath: String get() = breadcrumb.joinToString(CRUMB_SEPARATOR) { it.name }
}

/** Sync progress as owned by WorkManager, merged into [SettingsUiState] for display. */
private data class SyncUi(val running: Boolean, val line: StatusLine?)

/** The update-check slice of [SettingsUiState], assembled from six independent flows. */
private data class UpdateUi(
    val source: UpdateSource,
    val checking: Boolean,
    val signingIn: Boolean,
    val error: UpdateCheckError?,
    val lastCheckAt: Long,
    val upToDate: Boolean = false,
)

@Suppress(
    "TooManyFunctions", // one handler per settings action — mirrors SettingsActions
    // Eight collaborators because the Settings screen really does coordinate eight things:
    // connection, library, preferences, the user cache, sync, updates, the local-network prompt
    // and string resources. Bundling any pair would be indirection invented to satisfy a counter,
    // and Koin builds this — there is no call site bearing the cost.
    "LongParameterList",
)
class SettingsViewModel(
    private val app: Application,
    private val settingsRepo: SettingsRepository,
    private val libraryRepo: LibraryRepository,
    private val jellyfin: JellyfinRepository,
    private val settingsCache: SettingsCache,
    private val syncScheduler: SyncScheduler,
    private val updateChecker: UpdateChecker,
    // Not for showing anything — the prompt hosts itself on this screen. This is the one collaborator
    // that knows a failed connection is evidence of a missing grant; see [rearmLocalNetworkPrompt].
    private val localNetworkPrompt: LocalNetworkPrompt,
) : ViewModel() {

    /**
     * Read off the checker rather than from `BuildConfig`, so nothing in the UI layer branches on
     * the build type directly — previews and screenshot tests build the debug variant and would
     * otherwise never render the Updates section at all.
     */
    private val isDebugBuild = updateChecker.isDebugBuild

    /** Likewise off the checker rather than `BuildConfig` — see [isDebugBuild]. */
    private val versionName = updateChecker.versionName

    /** Local operations (connect, reset) only — sync lives in [_sync], see [state]. */
    private val _state = MutableStateFlow(SettingsUiState(isDebugBuild = isDebugBuild, versionName = versionName))
    private val _sync = MutableStateFlow<SyncUi?>(null)

    /**
     * The update half of the screen's state, combined separately so [state] stays a three-way
     * merge — `combine` runs out of typed overloads at five, which is also why the sixth flow is
     * folded in with a trailing `combine` rather than joining the list.
     */
    private val updateUi: Flow<UpdateUi> = combine(
        settingsRepo.updateSource,
        updateChecker.checking,
        updateChecker.signingIn,
        updateChecker.error,
        settingsRepo.lastUpdateCheckAt,
    ) { source, checking, signingIn, error, lastCheckAt ->
        UpdateUi(source, checking, signingIn, error, lastCheckAt)
    }.combine(updateChecker.upToDate) { ui, upToDate -> ui.copy(upToDate = upToDate) }

    /**
     * "Look at the sync scope" — a one-shot event, not a state flag, because it fires and is over:
     * a flag would have to be cleared afterwards, and a stale one would shake the section again on
     * the next recomposition. Extra buffer capacity so an emission is never dropped while the
     * screen is between subscriptions (a tab switch clears the collector, not the ViewModel).
     */
    private val _nudgeScope = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val nudgeScope: SharedFlow<Unit> = _nudgeScope.asSharedFlow()

    /**
     * "The demo library is ready" — the screen's host turns it into a jump to Library. One-shot
     * for the same reason as [nudgeScope]: navigating is an event, and a flag would fire again on
     * the next recomposition (and would have to be cleared by whoever consumed it).
     */
    private val _demoEntered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val demoEntered: SharedFlow<Unit> = _demoEntered.asSharedFlow()

    /**
     * "The server accepted these credentials" — the screen turns it into an autofill commit, which
     * is what asks the password manager to save them. One-shot for the same reason as the two
     * above.
     *
     * Fired on acceptance rather than on the button tap, which is the other place a form could
     * commit: a rejected password would otherwise be offered for saving, and the whole point of
     * the saved credential is that it works. Only a real sign-in emits — the demo's magic
     * username and password are not a credential anyone should keep.
     */
    private val _credentialAccepted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val credentialAccepted: SharedFlow<Unit> = _credentialAccepted.asSharedFlow()

    /**
     * Sync no longer runs in this scope, so its progress can't be held in [_state]: the worker
     * outlives the ViewModel a tab switch clears. Merging the two here keeps the screen's
     * contract unchanged while a sync started on one visit still reports on the next.
     *
     * Sync owns [SettingsUiState.syncStatus] outright — no precedence rule, because it no longer
     * shares a line with anything a local operation writes.
     */
    val state: StateFlow<SettingsUiState> = combine(_state, _sync, updateUi) { local, sync, update ->
        val withSync = if (sync == null) {
            local
        } else {
            local.copy(
                busy = local.busy || sync.running,
                syncRunning = sync.running,
                syncStatus = sync.line,
            )
        }
        withSync.copy(
            updateSource = update.source,
            checkingUpdate = update.checking,
            signingInTester = update.signingIn,
            updateError = update.error,
            upToDate = update.upToDate,
            lastUpdateCheckAt = update.lastCheckAt,
        )
    }.stateIn(
        viewModelScope,
        WhileUiSubscribed,
        SettingsUiState(isDebugBuild = isDebugBuild, versionName = versionName),
    )

    val videoCount: StateFlow<Int> = libraryRepo.videoCount()
        .stateIn(viewModelScope, WhileUiSubscribed, 0)

    /** Set as soon as the user edits any connection field, see [init]. */
    private var fieldsEdited = false

    init {
        viewModelScope.launch {
            val s = settingsRepo.snapshot()
            val cur = _state.value
            // This snapshot loads asynchronously — don't clobber text the user managed to type
            // into the fields before it landed.
            val edited = fieldsEdited
            val serverUrl = if (edited) cur.serverUrl else s.serverUrl
            // Looked up by the URL the field actually shows, not by the persisted one: entries
            // are keyed by the server they came from, so an edited URL misses the cache and the
            // chips stay cleared — which is what onServerUrlChange asked for. Restoring the old
            // server's users here would let a tap persist a foreign user id under the new URL.
            val cachedUsers = settingsCache.usersFor(serverUrl.trim())
            _state.value = cur.copy(
                serverUrl = serverUrl,
                apiKey = if (edited) cur.apiKey else s.apiKey,
                indexUrl = if (edited) cur.indexUrl else s.indexUrl,
                metadataApiUrl = if (edited) cur.metadataApiUrl else s.metadataApiUrl,
                metadataApiToken = if (edited) cur.metadataApiToken else s.metadataApiToken,
                username = if (edited) cur.username else s.userName,
                signedIn = s.isSignedIn,
                // The *persisted* key, never the field — see [SettingsUiState.apiKeyConnected].
                apiKeyConnected = s.apiKey.isNotBlank(),
                tokenInQuery = s.tokenInQuery,
                // `edited` guards this for the same reason as the fields above, and for one more:
                // a Connect that finished while this read was still in flight has already put the
                // right users on screen, and the cache lookup — which misses on an edited URL —
                // would wipe them. onServerUrlChange clears the chips itself, so keeping what is
                // there cannot resurrect another server's users either.
                users = if (edited) cur.users else cachedUsers.orEmpty(),
                // Still the persisted user and scope: they are what sync uses until the next
                // Connect, so the screen would lie by blanking them over an unsaved edit.
                selectedUserId = s.userId,
                selectedUserName = s.userName,
                selectedScopeId = s.libraryId,
                // Legacy installs persisted the English root prefix; strip it so the UI can
                // localize the root label.
                selectedScopePath = s.libraryName
                    .removePrefix("All collections$CRUMB_SEPARATOR")
                    .removePrefix("All collections"),
                lastSyncAt = s.lastSyncAt,
            )
            // Only hit the server when this process hasn't loaded users yet — tab switches
            // recreate this ViewModel, and re-connecting on every visit is wasted work. A
            // user already editing the fields connects explicitly with what they typed.
            // Signed-in installs skip it entirely: the token already identifies the user, so
            // there is no server-wide user list to fetch or pick from.
            val needsUserList = s.hasCredentials && !s.isSignedIn
            if (needsUserList && cachedUsers == null && !edited) connect(silent = true)
        }
        // Collected rather than snapshotted: the theme is also the one setting a tab switch can
        // find already changed, since this ViewModel is recreated on every visit.
        settingsRepo.themeState
            .onEach { _state.value = _state.value.copy(themeState = it) }
            .launchIn(viewModelScope)
        // Collected too, and for a stronger reason: this screen is where demo mode is both entered
        // and left, so a snapshot taken in init would be stale before the user's next tap.
        settingsRepo.settings
            .map { it.demoMode }
            .distinctUntilChanged()
            .onEach { _state.value = _state.value.copy(demoMode = it) }
            .launchIn(viewModelScope)
        observeSync()
    }

    /**
     * Mirrors the manual sync worker's state into [_sync]. Re-attaching on every ViewModel
     * creation is what makes sync status survive a tab switch — WorkManager replays the current
     * state of the unique work, finished or not.
     */
    private fun observeSync() {
        syncScheduler.manualSyncInfo()
            .mapNotNull { it.lastOrNull() }
            .onEach { info ->
                _sync.value = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                        SyncUi(running = true, line = StatusLine(app.getString(R.string.syncing)))
                    WorkInfo.State.SUCCEEDED -> {
                        val out = info.outputData
                        _state.value = _state.value.copy(
                            metadataApiAuthFailed =
                            out.getBoolean(SyncWorker.KEY_API_AUTH_FAILED, false),
                        )
                        // The worker exits early without output when credentials are missing;
                        // showing a 0/0 summary then would be a lie, so say nothing.
                        val message = if (out.keyValueMap.isEmpty()) {
                            null
                        } else {
                            syncSummary(
                                SyncResult.Success(
                                    itemCount = 0, // not carried in the output — nothing shows it
                                    matched = out.getInt(SyncWorker.KEY_MATCHED, 0),
                                    indexed = out.getInt(SyncWorker.KEY_INDEXED, 0),
                                    categories = out.getInt(SyncWorker.KEY_CATEGORIES, 0),
                                    indexDegraded = out.getBoolean(SyncWorker.KEY_INDEX_DEGRADED, false),
                                    apiDegraded = out.getBoolean(SyncWorker.KEY_API_DEGRADED, false),
                                    autoFilled = out.getInt(SyncWorker.KEY_AUTO_FILLED, 0),
                                    autoFillFailed = out.getInt(SyncWorker.KEY_AUTO_FILL_FAILED, 0),
                                ),
                            )
                        }
                        _state.value = _state.value.copy(lastSyncAt = settingsRepo.snapshot().lastSyncAt)
                        SyncUi(running = false, line = message?.let { StatusLine(it) })
                    }
                    WorkInfo.State.FAILED -> {
                        // A sync that hit a rejected token already cleared the session in the data
                        // layer; re-read it so the screen switches back to the sign-in form
                        // instead of still claiming to be signed in.
                        _state.value = _state.value.copy(signedIn = settingsRepo.snapshot().isSignedIn)
                        SyncUi(
                            running = false,
                            line = StatusLine(
                                info.outputData.getString(SyncWorker.KEY_ERROR)
                                    ?: app.getString(R.string.unknown_error),
                                isError = true,
                            ),
                        )
                    }
                    // Cancelled by Sign out, which clears this slot itself — leave it be.
                    WorkInfo.State.CANCELLED -> null
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * The status line for a successful sync. Shared by the worker's output data and by the demo
     * sync, which reports the same [SyncResult] without going through WorkManager at all — one
     * wording for both, so a demo sync reads exactly like a real one.
     */
    private fun syncSummary(result: SyncResult.Success): String {
        val summary = app.getString(
            R.string.sync_summary,
            result.indexed,
            result.matched,
            result.categories,
        )
        // The sync succeeded, but without its metadata feeds the counts are the reason "nothing
        // new gets categorized" — say so rather than reporting an unqualified success.
        var qualified = summary
        if (result.indexDegraded) {
            qualified = app.getString(R.string.sync_index_unavailable, qualified)
        }
        if (result.apiDegraded) {
            qualified = app.getString(R.string.sync_api_unavailable, qualified)
        }
        return appendAutoFill(qualified, result.autoFilled, result.autoFillFailed)
    }

    /**
     * Appends what the sync's yt-dlp auto-fill pass managed, when it ran at all. Silent on a
     * 0/0 pass — the common case is a library with no gaps, and reporting "filled 0" on every
     * sync would train the user to stop reading the line.
     */
    private fun appendAutoFill(summary: String, filled: Int, failed: Int): String = when {
        filled == 0 && failed == 0 -> summary
        failed == 0 -> app.getString(R.string.sync_auto_filled, summary, filled)
        else -> app.getString(R.string.sync_auto_filled_partial, summary, filled, failed)
    }

    /**
     * The half of a failure message that names the cause. A cleartext block gets an explanation
     * instead of its raw exception text — "CLEARTEXT communication to 192.168.1.10 not permitted
     * by network security policy" tells a user nothing about the `https://` they actually need.
     */
    private fun reason(e: Throwable): String = when {
        isCleartextBlocked(e) -> CLEARTEXT_BLOCKED_MESSAGE
        isUnauthorized(e) && _state.value.signedIn -> SESSION_EXPIRED_MESSAGE
        else -> e.message ?: app.getString(R.string.unknown_error)
    }

    /**
     * Puts the local-network rationale back on the table when a connection attempt failed the way
     * a missing `ACCESS_LOCAL_NETWORK` fails: Android drops the packets rather than refusing them,
     * so the request dies of its own timeout and the user is told the server is unreachable.
     *
     * Two failures are excluded because they are explained by something else, and re-arming on them
     * would ask for a permission that would change nothing: a 401 is a server that answered, and a
     * cleartext block is an `http://` URL that needs `https://`. Everything else — timeouts, DNS,
     * connection resets — is close enough to the symptom to be worth one dialog, and the prompt's
     * own gates drop it again for a user who already holds the permission or has locked it.
     *
     * [automatic] is the third exclusion, and the one that isn't about the exception: the silent
     * connect this ViewModel starts in `init` runs on *every* visit to this screen, unasked, and a
     * user whose server is simply unreachable — off the LAN, offline, wrong port — would have their
     * "Not now" cancelled by it seconds after they tapped it, then again on the next visit, and the
     * week's snooze would never hold. Only an attempt the user made themselves is evidence they are
     * trying to reach the server *now*; the automatic one leaves the snooze alone, and a user who
     * has never answered is still asked by [LocalNetworkPrompt.due] on its own schedule.
     *
     * Fire and forget: nothing on this screen changes as a result, and the prompt reads the store.
     */
    private fun rearmLocalNetworkPrompt(e: Throwable, automatic: Boolean = false) {
        if (automatic) return
        if (isUnauthorized(e) || isCleartextBlocked(e)) return
        localNetworkPrompt.rearm()
    }

    /**
     * Drops the session when the server rejected the token, so the screen asks for a sign-in
     * rather than leaving a dead credential in place. Never falls back to the advanced API key:
     * silently restoring full-server access is exactly what user login exists to avoid.
     */
    private suspend fun clearSessionIfRejected(e: Throwable) {
        if (!isUnauthorized(e) || !_state.value.signedIn) return
        settingsRepo.clearSession()
        _state.value = _state.value.copy(signedIn = false, selectedUserId = "", selectedUserName = "")
    }

    fun onServerUrlChange(value: String) {
        fieldsEdited = true
        // A different server has different users — drop the chips until the next connect so
        // a stale selection can't be persisted against the new URL.
        _state.value = _state.value.copy(serverUrl = value, users = emptyList())
    }
    fun onApiKeyChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(apiKey = value)
    }
    fun onIndexUrlChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(indexUrl = value)
    }
    fun onMetadataApiUrlChange(value: String) {
        fieldsEdited = true
        // Editing is the fix the auth-failed state asks for; a stale red field over a corrected
        // value would read as "still wrong" until the next sync.
        _state.value = _state.value.copy(metadataApiUrl = value, metadataApiAuthFailed = false)
    }
    fun onMetadataApiTokenChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(metadataApiToken = value, metadataApiAuthFailed = false)
    }

    /**
     * Advanced: force the credential into the stream URL for players that ignore the headers
     * extra. Persisted immediately — it isn't part of the connect/sign-in form.
     */
    fun onTokenInQueryChange(enabled: Boolean) {
        _state.value = _state.value.copy(tokenInQuery = enabled)
        viewModelScope.launch { settingsRepo.setTokenInQuery(enabled) }
    }

    /**
     * One step of the theme switch's ping-pong: light → auto → dark → auto → light. Persisted
     * immediately — like [onTokenInQueryChange], it is a standalone preference rather than part of
     * the connect/sign-in form.
     */
    fun onThemeModeClick() {
        val next = _state.value.themeState.next()
        _state.value = _state.value.copy(themeState = next)
        viewModelScope.launch { settingsRepo.setThemeState(next) }
    }

    fun onUsernameChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(username = value)
    }

    fun onPasswordChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(password = value)
    }

    /**
     * The primary connect flow: exchange username + password for a user-scoped token.
     *
     * The password is cleared from state the moment the call returns — success or failure — so it
     * never outlives the request that used it, and it is never written to DataStore at all.
     */
    fun signIn() {
        val s = _state.value
        // `busy` is a frame stale in the UI; guard here so two taps can't run concurrent sign-ins.
        if (s.busy) return
        // What the user typed, cleaned up to what they meant: a bare host gets its https://, the
        // username loses the trailing space a keyboard suggestion appends, and the password loses
        // only line breaks — a paste artifact; no field this feeds can legitimately contain one,
        // but a password may genuinely contain spaces, so those stay.
        val username = s.username.trim()
        val password = s.password.filterNot { it == '\n' || it == '\r' }
        // Before normalizeServerUrl, deliberately: "jellyfin" is a magic word, not a host, and
        // normalizing it would produce https://jellyfin and a doomed request to a real network.
        // A blank password still falls through to the field check below — "any password" is not
        // "no password", and the empty form should say so exactly as it does for a real server.
        if (isDemoSignIn(s.serverUrl, username) && password.isNotBlank()) {
            signInAsDemoUser(password)
            return
        }
        val serverUrl = normalizeServerUrl(s.serverUrl)
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _state.value = s.copy(
                authStatus = StatusLine(app.getString(R.string.enter_server_user_password), isError = true),
            )
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(
                busy = true,
                // The field shows the URL that is actually being tried.
                serverUrl = serverUrl,
                authStatus = StatusLine(app.getString(R.string.signing_in)),
            )
            clearDemoLibrary()
            runCatchingCancellable {
                persistSession(jellyfin.signIn(serverUrl, username, password), serverUrl)
            }.onFailure { e ->
                // 401 here is the server rejecting the username/password pair — verified against
                // Jellyfin 10.11: a malformed request 400s instead. Say so, rather than showing
                // the raw Ktor exception text, which reads like an app failure.
                val cause = if (isUnauthorized(e)) {
                    app.getString(R.string.invalid_username_or_password)
                } else {
                    reason(e)
                }
                _state.value = _state.value.copy(
                    busy = false,
                    password = "",
                    authStatus = StatusLine(app.getString(R.string.sign_in_failed, cause), isError = true),
                )
                rearmLocalNetworkPrompt(e)
            }
        }
    }

    /**
     * Records an accepted sign-in: the token, the user it names, and the connection fields the
     * form was holding. Only ever called after the server said yes, so a typo can never overwrite
     * a working configuration.
     */
    private suspend fun persistSession(session: Session, serverUrl: String) {
        val form = _state.value
        settingsRepo.setConnection(serverUrl, form.apiKey)
        settingsRepo.setIndexUrl(form.indexUrl)
        settingsRepo.setMetadataApi(form.metadataApiUrl, form.metadataApiToken)
        settingsRepo.setSession(session.accessToken, session.user.id, session.user.name)
        // A different user means a different item tree, so the old folder scope points at a parent
        // id that may not exist for them — same reset as switching users by hand.
        val userChanged = session.user.id != form.selectedUserId
        if (userChanged) settingsRepo.setLibrary(ROOT_SCOPE_ID, ROOT_SCOPE_PATH)
        // Re-armed here, with the rest of the post-sign-in state, so "once per sign-in" holds by
        // construction rather than by comparing timestamps.
        settingsRepo.setSyncScopeNudged(false)
        // Before the state write below, deliberately. That write blanks the password and takes its
        // field out of the form, and the save offer has to be asked for while the fields the
        // password manager is being asked to remember are still standing. Emitting first puts the
        // collector's commit on the main queue ahead of the recomposition this write schedules.
        _credentialAccepted.emit(Unit)
        _state.value = _state.value.copy(
            busy = false,
            password = "",
            signedIn = true,
            // setConnection just persisted whatever the advanced field held, so this follows it.
            apiKeyConnected = form.apiKey.isNotBlank(),
            username = session.user.name,
            selectedUserId = session.user.id,
            selectedUserName = session.user.name,
            selectedScopeId = if (userChanged) ROOT_SCOPE_ID else form.selectedScopeId,
            selectedScopePath = if (userChanged) ROOT_SCOPE_PATH else form.selectedScopePath,
            // The user picker is an API-key-mode affordance; a token identifies its user.
            users = emptyList(),
            // A new user means a new item tree, so whatever the scope section last said about the
            // old one — a failed folder load, or the nudge — no longer describes anything.
            scopeStatus = null,
            // Re-read, don't keep: clearDemoLibrary just zeroed the persisted marker when this
            // sign-in replaced a demo library, and the stale demo timestamp would otherwise leave
            // the index field locked (indexProtected) with nothing ever synced against this
            // server. A re-sign-in over an intact library reads its real value back unchanged.
            lastSyncAt = settingsRepo.snapshot().lastSyncAt,
            authStatus = StatusLine(app.getString(R.string.signed_in_as, session.user.name)),
        )
    }

    // --- Demo mode --------------------------------------------------------

    /** The Settings button: seed the demo library and go straight to it. */
    fun tryDemo() {
        if (_state.value.busy) return
        viewModelScope.launch { enterDemo() }
    }

    /**
     * The sign-in form's demo path, reached only with the magic credentials. [DEMO_BAD_PASSWORD]
     * routes through the *real* failure presentation — the same two strings a rejected 401
     * produces — so the error state is demonstrable without a server to reject anything. Any other
     * password enters the demo the button would have.
     *
     * Nothing here writes a connection: [enterDemo] seeds and marks the library, and the failure
     * branch writes only to the status line.
     */
    private fun signInAsDemoUser(password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                authStatus = StatusLine(app.getString(R.string.signing_in)),
            )
            if (isDemoAuthFailure(password)) {
                _state.value = _state.value.copy(
                    busy = false,
                    password = "",
                    authStatus = StatusLine(
                        app.getString(
                            R.string.sign_in_failed,
                            app.getString(R.string.invalid_username_or_password),
                        ),
                        isError = true,
                    ),
                )
                return@launch
            }
            enterDemo()
        }
    }

    /**
     * Seeds the demo library and announces it, for both entry points.
     *
     * `NonCancellable` around the seed: it writes the rows and the flag that describes them
     * together, and this ViewModel is cleared by a tab switch — which is exactly what the
     * navigation afterwards causes.
     */
    private suspend fun enterDemo() {
        _state.value = _state.value.copy(
            busy = true,
            authStatus = StatusLine(app.getString(R.string.loading_demo_library)),
        )
        withContext(NonCancellable) { libraryRepo.seedDemoLibrary() }
        _state.value = _state.value.copy(
            busy = false,
            // Whatever was typed to get here is not a credential and does not linger.
            password = "",
            authStatus = StatusLine(app.getString(R.string.demo_library_loaded)),
            lastSyncAt = settingsRepo.snapshot().lastSyncAt,
        )
        _demoEntered.emit(Unit)
    }

    /**
     * Drops a demo library before a *real* connection is made. Demo rows carry a sentinel item id
     * and belong to no server, so a sync would treat every one of them as missing and spend its
     * grace period deleting them — while they sat in the library looking like real videos in the
     * meantime. Silent, and announced in advance by the demo caption on this screen: seeding is
     * one tap away, so there is nothing here worth a confirmation dialog.
     */
    private suspend fun clearDemoLibrary() {
        if (settingsRepo.snapshot().demoMode) libraryRepo.clearLocalData()
    }

    // ----------------------------------------------------------------------

    /**
     * Sign out, and take with it everything the connection produced: the token, the server URL, the
     * API key, the index URL, the folder scope, and every locally cached video and category. Also
     * the way out of demo mode, which has no credential to drop but the same seeded library.
     *
     * The wipe used to be a separate "Reset local data" button. Folding it in here makes Sign out
     * the one destructive action on this screen: a signed-out install still holding a full library
     * is a state nothing else in the app expects, and clearing the rows while keeping the
     * credentials that produced them was never what anyone came to Settings to do. The confirmation
     * lives in the UI — [SettingsScreen] — because it is a presentation of the danger, not the rule.
     */
    fun signOut() {
        if (_state.value.busy) return
        // Raised here rather than inside the coroutine, unlike every other guard on this screen:
        // this one destroys data, and two taps in the same frame would both pass a check that the
        // launch has not run yet — twice through a wipe is survivable, twice through a *confirmed*
        // wipe is a second dialog the user never answered.
        _state.value = _state.value.copy(
            busy = true,
            authStatus = StatusLine(app.getString(R.string.signing_out)),
        )
        viewModelScope.launch {
            val leavingDemo = settingsRepo.snapshot().demoMode
            // Stop sync first, for the reason the old Reset local data did: a worker running
            // through the wipe would refill the tables, and the periodic one would go on doing it
            // against credentials that are about to be gone.
            syncScheduler.cancelAll()
            _sync.value = null
            // NonCancellable across both writes: a half-signed-out install — no token but a full
            // library, or a wiped library still holding a server URL — is a state no other screen
            // expects, and this ViewModel is cleared by the tab switch a sign-out invites.
            withContext(NonCancellable) {
                settingsRepo.clearConnection()
                libraryRepo.clearLocalData()
            }
            settingsCache.clear()
            // The fields are blank again, so nothing typed is being protected from the init load.
            fieldsEdited = false
            // Rebuilt rather than copied: this is a fresh install's state, and a `copy` would have
            // to remember to blank every field the connection filled. The theme is carried across
            // by hand because it is the one thing here that never came from a server; the update
            // slice is merged in by [state] from its own flows and needs no carrying.
            _state.value = SettingsUiState(
                isDebugBuild = isDebugBuild,
                versionName = versionName,
                themeState = _state.value.themeState,
                authStatus = StatusLine(
                    app.getString(if (leavingDemo) R.string.demo_left else R.string.signed_out),
                ),
            )
        }
    }

    /** Prefill the metadata index URL from the entered server URL. */
    fun fillIndexUrlFromServer() {
        val base = normalizeServerUrl(_state.value.serverUrl).trimEnd('/')
        if (base.isBlank()) return
        _state.value = _state.value.copy(indexUrl = "$base/jellyshelf-index.json")
    }

    /** Prefill the metadata API URL from the entered server URL. */
    fun fillMetadataApiUrlFromServer() {
        val base = normalizeServerUrl(_state.value.serverUrl).trimEnd('/')
        if (base.isBlank()) return
        _state.value = _state.value.copy(metadataApiUrl = "$base/api")
    }

    /**
     * The **API-key** connect path: save server + key, load the server-wide user list, auto-select
     * the saved/first user. Only reachable from the advanced section — a signed-in user has no use
     * for it, since their token already names them.
     */
    fun connect(silent: Boolean = false) {
        val s = _state.value
        // The screen disables buttons via `busy`, but that state is a frame stale — guard here
        // so two taps landing in the same frame can't run concurrent operations.
        if (s.busy) return
        // Ahead of both normalizeServerUrl and the api-key check: the demo server is a magic word
        // rather than a host, and someone trying the demo has no API key to type.
        if (isDemoServer(s.serverUrl)) {
            offerDemoUser()
            return
        }
        // Same courtesy as signIn: a bare host gets its https:// before anything is tried.
        val serverUrl = normalizeServerUrl(s.serverUrl)
        if (serverUrl.isBlank() || s.apiKey.isBlank()) {
            _state.value = s.copy(
                connectStatus = StatusLine(app.getString(R.string.enter_server_and_key), isError = true),
            )
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(
                busy = true,
                // The field shows the URL that is actually being tried.
                serverUrl = serverUrl,
                connectStatus = if (silent) s.connectStatus else StatusLine(app.getString(R.string.connecting)),
            )
            clearDemoLibrary()
            runCatchingCancellable {
                val users = jellyfin.getUsers(serverUrl, s.apiKey)
                // Persist only after the server accepted the credentials, so a typo can never
                // overwrite a previously working configuration.
                settingsRepo.setConnection(serverUrl, s.apiKey)
                settingsRepo.setIndexUrl(s.indexUrl)
                settingsRepo.setMetadataApi(s.metadataApiUrl, s.metadataApiToken)
                // Keyed by what setConnection persists, so the next init's lookup hits.
                settingsCache.store(serverUrl, users)
                val current = _state.value
                val selected = users.firstOrNull { it.id == current.selectedUserId } ?: users.firstOrNull()
                // Auto-selecting a *different* user (server changed, or the saved user is gone)
                // means the old folder scope belongs to another item tree — reset it to root,
                // exactly like a manual selectUser(), or the next sync queries the new server
                // with a nonexistent parent id.
                val userChanged = selected != null && selected.id != current.selectedUserId
                _state.value = current.copy(
                    busy = false,
                    users = users,
                    // The key has just been persisted, so the install can be signed out of it.
                    apiKeyConnected = true,
                    selectedUserId = selected?.id ?: current.selectedUserId,
                    selectedUserName = selected?.name ?: current.selectedUserName,
                    selectedScopeId = if (userChanged) ROOT_SCOPE_ID else current.selectedScopeId,
                    selectedScopePath = if (userChanged) ROOT_SCOPE_PATH else current.selectedScopePath,
                    connectStatus = StatusLine(connectedStatus(users.size)),
                )
                selected?.let {
                    settingsRepo.setUser(it.id, it.name)
                    if (userChanged) settingsRepo.setLibrary(ROOT_SCOPE_ID, ROOT_SCOPE_PATH)
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    connectStatus = StatusLine(
                        app.getString(R.string.connection_failed, reason(e)),
                        isError = true,
                    ),
                )
                rearmLocalNetworkPrompt(e, automatic = silent)
            }
        }
    }

    /**
     * The user list the demo server "returns": one fake user, in the same state slot the real one
     * fills, so the advanced flow is visibly the flow it demonstrates. Nothing is persisted and no
     * request is made — the chip is presentation; signing in as `demo` is what actually does
     * something.
     */
    private fun offerDemoUser() {
        _state.value = _state.value.copy(
            users = listOf(User(id = DEMO_USER_ID, name = DEMO_USER)),
            connectStatus = StatusLine(connectedStatus(1)),
        )
    }

    /** What the connect step reports: how many users came back, or that none did. */
    private fun connectedStatus(userCount: Int): String = if (userCount == 0) {
        app.getString(R.string.connected_no_users)
    } else {
        app.getString(
            R.string.connected_users,
            app.resources.getQuantityString(R.plurals.user_count, userCount, userCount),
        )
    }

    fun selectUser(user: User) {
        // The demo user has no server behind it: persisting it would write a user id under a
        // connection that doesn't exist, and browsing its folders would be a real request to
        // nowhere. Tapping the chip selects it on screen and stops there.
        if (user.id == DEMO_USER_ID) {
            _state.value = _state.value.copy(selectedUserId = user.id, selectedUserName = user.name)
            return
        }
        _state.value = _state.value.copy(
            selectedUserId = user.id,
            selectedUserName = user.name,
            // A different user has a different tree — reset scope and open the
            // folder browser so its collections are ready to pick from.
            selectedScopeId = ROOT_SCOPE_ID,
            selectedScopePath = ROOT_SCOPE_PATH,
            browserOpen = true,
            breadcrumb = emptyList(),
            childFolders = emptyList(),
        )
        viewModelScope.launch {
            settingsRepo.setUser(user.id, user.name)
            settingsRepo.setLibrary(ROOT_SCOPE_ID, ROOT_SCOPE_PATH)
        }
        loadChildren()
    }

    // --- Folder browser ---------------------------------------------------

    fun openBrowser() {
        if (_state.value.selectedUserId.isBlank()) return
        _state.value = _state.value.copy(browserOpen = true, breadcrumb = emptyList())
        loadChildren()
    }

    fun closeBrowser() {
        _state.value = _state.value.copy(browserOpen = false)
    }

    /** Drill into a subfolder. */
    fun enterFolder(folder: FolderRef) {
        _state.value = _state.value.copy(breadcrumb = _state.value.breadcrumb + folder)
        loadChildren()
    }

    /** Jump via the breadcrumb; index -1 == root. */
    fun navigateTo(index: Int) {
        val trimmed = if (index < 0) emptyList() else _state.value.breadcrumb.take(index + 1)
        _state.value = _state.value.copy(breadcrumb = trimmed)
        loadChildren()
    }

    /** Set the folder currently being browsed as the sync scope. */
    fun useCurrentFolder() {
        val id = _state.value.currentParentId
        val path = _state.value.currentPath
        // Ready-to-paste .test.env lines (see .example.test.env): picking a scope is the one
        // moment the folder's Jellyfin id is in hand, and logging beats fishing it out of the
        // web UI's URL. Not sensitive — a folder name and item id, no credential — and debug-only
        // anyway: release plants no Timber tree and R8 strips the call. The .test.env convention
        // joins nesting with "/", not the picker's " › ".
        Timber.tag("SyncScope").i(
            "JELLYFIN_SYNC_FOLDER=%s\nJELLYFIN_SYNC_FOLDER_ID=%s",
            _state.value.breadcrumb.joinToString("/") { it.name },
            id,
        )
        _state.value = _state.value.copy(
            selectedScopeId = id,
            selectedScopePath = path,
            browserOpen = false,
        )
        viewModelScope.launch { settingsRepo.setLibrary(id, path) }
    }

    private var loadChildrenJob: Job? = null

    private fun loadChildren() {
        val s = _state.value
        // Cancel any in-flight load: a slower earlier response must not overwrite the list for
        // the folder the user has since navigated to.
        loadChildrenJob?.cancel()
        loadChildrenJob = viewModelScope.launch {
            // The line describes *this* attempt, so the previous one's failure goes with the start
            // of it — a slot that only ever gains messages would keep showing a dead error while
            // the folders it complained about load successfully underneath.
            _state.value = _state.value.copy(loadingFolders = true, scopeStatus = null)
            runCatchingCancellable {
                // Browse with the credentials that are actually saved, not with whatever is
                // half-typed in the fields: the folder tree belongs to the connection the app is
                // configured with, and an edit that hasn't been through Connect isn't one. The
                // user and the folder being browsed do come from the screen — selectUser opens
                // the browser before its own persist has necessarily landed.
                val saved = settingsRepo.snapshot()
                val folders = jellyfin
                    .getChildFolders(saved.serverUrl, saved.credential, s.selectedUserId, s.currentParentId)
                    .map { FolderRef(it.id, it.name, it.path) }
                _state.value = _state.value.copy(childFolders = folders, loadingFolders = false)
            }.onFailure { e ->
                // Message first: [reason] reads the still-signed-in state that [clearSessionIfRejected]
                // is about to drop.
                val message = app.getString(R.string.folders_load_failed, reason(e))
                clearSessionIfRejected(e)
                _state.value = _state.value.copy(
                    childFolders = emptyList(),
                    loadingFolders = false,
                    scopeStatus = StatusLine(message, isError = true),
                )
            }
        }
    }

    // ----------------------------------------------------------------------

    /**
     * Syncs — unless this is the first sync since signing in and the scope is still the whole
     * server, in which case it points that out instead and lets the next tap through.
     *
     * The rule lives here rather than in the composable because it is a rule: what the screen does
     * with [nudgeScope] is a rendering choice, but *whether* a tap syncs is not. Arming is once per
     * sign-in and survives process death, so the nudge can never become a permanent extra tap.
     *
     * A sync that does go ahead is handed to [SyncScheduler], which enqueues it and (re)creates the
     * periodic worker. Deliberately not awaited: a tab switch clears this ViewModel, which used to
     * cancel the sync mid-flight.
     */
    fun syncNow() {
        val indexUrl = _state.value.indexUrl
        val metadataApiUrl = _state.value.metadataApiUrl
        val metadataApiToken = _state.value.metadataApiToken
        viewModelScope.launch {
            if (settingsRepo.snapshot().demoMode) {
                demoSync()
                return@launch
            }
            if (shouldNudgeScope()) {
                settingsRepo.setSyncScopeNudged(true)
                // On the scope section's own line, which is what the shake points at — a nudge
                // under Sync now would be an arrow pointing at itself.
                _state.value = _state.value.copy(
                    scopeStatus = StatusLine(app.getString(R.string.check_sync_scope_first)),
                )
                // Emitted as well as written to the status line: the shake is the eye-catching
                // half, the status text is the half a screen reader and a stopped animation
                // still have.
                _nudgeScope.emit(Unit)
                return@launch
            }
            // The worker reads the index and API URLs from settings, so persist before enqueueing
            // — and do all of it even if this ViewModel is cleared in between.
            withContext(NonCancellable) {
                settingsRepo.setIndexUrl(indexUrl)
                settingsRepo.setMetadataApi(metadataApiUrl, metadataApiToken)
                syncScheduler.syncNow()
            }
        }
    }

    /**
     * Switches the update channel. Selecting App Distribution is gated on a successful tester
     * sign-in, which [UpdateChecker.selectSource] owns — it is update policy, and the same place
     * that decides when a Custom Tab may open at all.
     *
     * Deliberately **not** wrapped in `viewModelScope.launch`: the sign-in leaves the app, and
     * coming back recreates the activity and clears this ViewModel, which used to cancel the write
     * mid-flight. The checker runs it on the application scope instead.
     */
    fun onUpdateSourceChange(source: UpdateSource) {
        updateChecker.selectSource(source)
    }

    /** The manual check. Skips every politeness window, and may sign a tester in — see [UpdateChecker]. */
    fun checkForUpdates() {
        viewModelScope.launch { updateChecker.checkNow() }
    }

    /**
     * A demo sync runs here rather than through [SyncScheduler], and reports into [_sync] itself.
     *
     * Two reasons, both about the worker rather than the sync: it is constrained to
     * `NetworkType.CONNECTED`, so on the offline device a demo is most likely to be shown on it
     * would sit enqueued and leave the screen saying "Syncing…" forever — and `syncNow` also
     * (re)creates the *periodic* worker, which a demo install has no business scheduling for a
     * server it does not have. What the sync itself does is the repository's business, and is the
     * same [LibraryRepository.sync] call the worker would have made.
     */
    private suspend fun demoSync() {
        _sync.value = SyncUi(running = true, line = StatusLine(app.getString(R.string.syncing)))
        // NonCancellable so a tab switch mid-sync can't abandon a half-applied one: this ViewModel
        // is cleared on every tab change, unlike the worker that normally owns this work.
        val result = withContext(NonCancellable) { libraryRepo.sync() }
        _state.value = _state.value.copy(lastSyncAt = settingsRepo.snapshot().lastSyncAt)
        _sync.value = when (result) {
            is SyncResult.Success -> SyncUi(false, StatusLine(syncSummary(result)))
            is SyncResult.Error -> SyncUi(false, StatusLine(result.message, isError = true))
        }
    }

    private suspend fun shouldNudgeScope(): Boolean =
        shouldNudgeSyncScope(_state.value.selectedScopeId, settingsRepo.syncScopeNudged.first())
}

/**
 * Whether a **Sync now** tap should point at the sync scope instead of syncing: only when the
 * scope is still the whole server *and* the user has not already been told so since signing in.
 *
 * A free function so the rule is testable on its own — [SettingsViewModel] needs an `Application`
 * to build, which puts it out of reach of a host-side test in a codebase that deliberately has no
 * Robolectric.
 */
internal fun shouldNudgeSyncScope(scopeId: String, alreadyNudged: Boolean): Boolean =
    scopeId == ROOT_SCOPE_ID && !alreadyNudged

/**
 * Whether the server field names the demo rather than a server. Matched against the **raw** field:
 * `normalizeServerUrl` would turn it into `https://jellyfin`, and by then it is indistinguishable
 * from a real host on someone's LAN — one that a connect attempt would genuinely try to reach.
 *
 * Trimmed and case-insensitive because it is typed by hand, on a keyboard that capitalizes.
 *
 * Free functions, like [shouldNudgeSyncScope], so the rules are testable without an `Application`.
 */
internal fun isDemoServer(serverUrl: String): Boolean =
    serverUrl.trim().equals(DEMO_SERVER, ignoreCase = true)

/** Whether this sign-in is the magic demo one: the demo server *and* the demo user. */
internal fun isDemoSignIn(serverUrl: String, username: String): Boolean =
    isDemoServer(serverUrl) && username.trim().equals(DEMO_USER, ignoreCase = true)

/** Whether a demo password asks for the authentication-failure demonstration instead of the demo. */
internal fun isDemoAuthFailure(password: String): Boolean =
    password.trim().equals(DEMO_BAD_PASSWORD, ignoreCase = true)
