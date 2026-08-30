package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.DestructiveButton
import com.gmail.volkovskiyda.jellyshelf.ui.formatSyncTime
import com.gmail.volkovskiyda.jellyshelf.ui.rememberNow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * Handles for the baseline-profile generator (`:baselineprofile`), which fills the sign-in form
 * through UiAutomator and so cannot match on Compose semantics the way the instrumented tests do.
 * Named in resource-id style because that is what they become: MainActivity's root Scaffold sets
 * `testTagsAsResourceId`, which republishes every tag below it as an Android resource id. They have
 * no other meaning — nothing in the app or the test suite reads them.
 */
internal const val SERVER_URL_FIELD_TAG = "server_url_field"
internal const val USERNAME_FIELD_TAG = "username_field"
internal const val PASSWORD_FIELD_TAG = "password_field"
internal const val INDEX_URL_FIELD_TAG = "index_url_field"
internal const val METADATA_API_URL_FIELD_TAG = "metadata_api_url_field"
internal const val METADATA_API_TOKEN_FIELD_TAG = "metadata_api_token_field"

/**
 * Settings tab: binds [SettingsViewModel] to the stateless [SettingsContent] below, which
 * previews and tests can render without a ViewModel or a Koin container.
 *
 * @param onDemoEntered the demo library has just been seeded — the host navigates to it. Handled
 *   there rather than here because where the app *goes* is the nav's business, not this screen's.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onDemoEntered: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videoCount by viewModel.videoCount.collectAsStateWithLifecycle()
    val now by rememberNow()

    // rememberUpdatedState so a recomposition with a new callback doesn't restart the collection
    // — and, more importantly, doesn't drop the emission that arrives during the restart.
    val demoEntered by rememberUpdatedState(onDemoEntered)
    LaunchedEffect(viewModel) {
        viewModel.demoEntered.collect { demoEntered() }
    }

    // Committing is what produces the "save this password?" offer: until the form says it is done,
    // the password manager has a fill session open and nothing to save. Hosted here rather than in
    // [SettingsContent], which stays free of platform services so previews and goldens can render
    // it. Null wherever no autofill service is configured — including the emulators the goldens
    // run on.
    val autofillManager by rememberUpdatedState(LocalAutofillManager.current)
    LaunchedEffect(viewModel) {
        viewModel.credentialAccepted.collect { autofillManager?.commit() }
    }

    SettingsContent(
        state = state,
        videoCount = videoCount,
        actions = SettingsActions(
            onThemeModeClick = viewModel::onThemeModeClick,
            onServerUrlChange = viewModel::onServerUrlChange,
            onApiKeyChange = viewModel::onApiKeyChange,
            onIndexUrlChange = viewModel::onIndexUrlChange,
            onMetadataApiUrlChange = viewModel::onMetadataApiUrlChange,
            onMetadataApiTokenChange = viewModel::onMetadataApiTokenChange,
            onUsernameChange = viewModel::onUsernameChange,
            onPasswordChange = viewModel::onPasswordChange,
            onTokenInQueryChange = viewModel::onTokenInQueryChange,
            fillIndexUrlFromServer = viewModel::fillIndexUrlFromServer,
            fillMetadataApiUrlFromServer = viewModel::fillMetadataApiUrlFromServer,
            signIn = viewModel::signIn,
            signOut = viewModel::signOut,
            tryDemo = viewModel::tryDemo,
            connect = { viewModel.connect() },
            selectUser = viewModel::selectUser,
            openBrowser = viewModel::openBrowser,
            closeBrowser = viewModel::closeBrowser,
            enterFolder = viewModel::enterFolder,
            navigateTo = viewModel::navigateTo,
            useCurrentFolder = viewModel::useCurrentFolder,
            syncNow = viewModel::syncNow,
            onUpdateSourceChange = viewModel::onUpdateSourceChange,
            checkForUpdates = viewModel::checkForUpdates,
        ),
        modifier = modifier,
        nudgeScope = viewModel.nudgeScope,
        now = now,
    )

    // Hosted out here rather than inside [SettingsContent] so the content stays stateless and its
    // screenshot goldens keep rendering a screen with nothing on top of it — the same arrangement
    // LibraryScreen uses for the notification prompt. Passed nothing from this screen's state on
    // purpose: who is asked is [LocalNetworkPrompt]'s decision, and reading a state that loads
    // asynchronously is what used to flash the dialog over the library and close it again.
    LocalNetworkPermissionPrompt()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    videoCount: Int,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    // Whether the Advanced and Alternative-sign-in sections start open. Parameters purely so
    // previews and screenshot tests can render them — collapsing them by default would otherwise
    // hide those whole paths from the goldens.
    advancedExpanded: Boolean = false,
    alternativeSignInExpanded: Boolean = false,
    // Fired when a Sync now tap was spent pointing at the scope instead of syncing. A flow rather
    // than a flag: the shake happens once and is over, and a flag would have to be cleared.
    nudgeScope: Flow<Unit> = emptyFlow(),
    // Passed in rather than read here, so previews and tests can pin it: a relative label built
    // from the wall clock would make their output depend on when they ran.
    now: Long = 0L,
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    val scopeShake = rememberScopeShake(nudgeScope)

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.theme))
                    Text(
                        stringResource(state.themeState.mode.labelRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ThemeModeSwitch(mode = state.themeState.mode, onClick = actions.onThemeModeClick)
            }

            HorizontalDivider()

            Text(stringResource(R.string.jellyfin_connection), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = actions.onServerUrlChange,
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text(stringResource(R.string.server_url_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // Every text field is autofillable by default, and an unlabelled one sitting
                    // directly above a username and a password is one the provider will guess at:
                    // Google's filled the saved *password* into this box. [ContentDataType.None]
                    // is how a Compose field says it holds nothing a password manager wants —
                    // there is no content type for a server address to declare instead.
                    .semantics { contentDataType = ContentDataType.None }
                    .testTag(SERVER_URL_FIELD_TAG),
            )
            // The default auth path: a user-scoped token, so nothing the app holds or hands to an
            // external player is a full-server credential.
            //
            // [ContentType] on both fields is what makes the password manager offer the *saved*
            // credential for this app. Without it Compose publishes no autofill hint, the provider
            // falls back to guessing from the masked field alone, and a lone password field with no
            // declared username beside it reads as a sign-up form — so the offer is "generate a new
            // password" instead of the one already stored.
            //
            // Keyed on [SettingsUiState.signedIn] so signing in builds a *new* field rather than
            // re-labelling this one. A filled field paints itself with the autofill highlight and
            // only ever drops it on an edit the user makes; the sign-in that replaces the typed
            // username with the server's is not one, so without the key the field stays washed
            // yellow for as long as the screen lives.
            key(state.signedIn) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = actions.onUsernameChange,
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    enabled = !state.signedIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Username }
                        .testTag(USERNAME_FIELD_TAG),
                )
            }
            if (!state.signedIn) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = actions.onPasswordChange,
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { actions.signIn() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        // Password, not NewPassword: this form only ever signs in to a server that
                        // already has the account.
                        .semantics { contentType = ContentType.Password }
                        .testTag(PASSWORD_FIELD_TAG),
                )
            }
            // Directly above the button that produces it: a rejected password reported at the far
            // end of a scrolling column is a rejection nobody sees.
            StatusText(state.authStatus)

            // Above the Sign in button, next to the form it is an alternative *to* — collapsed,
            // it costs the form one text-button row.
            AlternativeSignInSection(
                state = state,
                actions = actions,
                initiallyExpanded = alternativeSignInExpanded,
            )

            // Signed in, the two are one control in one slot — there is nothing to sign in *to*
            // while a token is held. Otherwise Sign in stays the primary action and Sign out is
            // offered underneath, because a demo (or API-key) install signing in to a real server
            // is a supported one-step move that must not become "sign out first".
            if (state.signedIn) {
                DestructiveButton(
                    label = stringResource(R.string.sign_out),
                    onClick = { showSignOutDialog = true },
                    enabled = !state.busy,
                )
            } else {
                Button(
                    onClick = actions.signIn,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sign_in)) }

                if (state.canSignOut) {
                    DestructiveButton(
                        label = stringResource(R.string.sign_out),
                        onClick = { showSignOutDialog = true },
                        enabled = !state.busy,
                    )
                }
            }

            // The one-tap way into the demo, next to the sign-in form it stands in for. (Typing
            // the demo credentials into that form works too — see SettingsViewModel.signIn.)
            if (state.canTryDemo) {
                OutlinedButton(
                    onClick = actions.tryDemo,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.try_demo)) }
            }
            // Says what the library is, and warns before the fact that connecting will replace it
            // — the auto-clear is silent by design, so this line is where it is announced.
            if (state.demoMode) {
                Text(
                    stringResource(R.string.demo_mode_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AdvancedSection(
                state = state,
                actions = actions,
                initiallyExpanded = advancedExpanded,
            )

            if (state.selectedUserId.isNotBlank() && !state.demoUserSelected) {
                // The wrapper exists only to carry the shake offset, so it has to repeat the
                // parent's spacing — ScopeSection emits several siblings and was relying on it.
                Column(
                    modifier = Modifier.offset { IntOffset(scopeShake.value.roundToInt(), 0) },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScopeSection(state = state, actions = actions)
                }
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = actions.syncNow,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sync_now)) }

            if (state.busy) CircularProgressIndicator()

            // Sync's own line, and the one slot fed from outside this screen: WorkManager replays
            // the last sync here, so it can be describing one started on an earlier visit.
            StatusText(state.syncStatus)

            Text(
                stringResource(
                    R.string.library_summary,
                    pluralStringResource(R.plurals.video_count, videoCount, videoCount),
                ) + "  •  " +
                    // The time itself, not just that one was recorded: relative while it is
                    // recent, exact once it is not (see formatSyncTime).
                    (
                        formatSyncTime(state.lastSyncAt, now)
                            ?.let { stringResource(R.string.synced_at, it) }
                            ?: stringResource(R.string.never_synced)
                        ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // Playback handoff. Out in the open rather than behind Advanced: it is the switch to
            // reach for when playback fails, and a fix hidden behind an expander is one the user
            // in that situation never finds. Independent of which credential is in use, so it is
            // not gated on being signed in either.
            TokenInQueryRow(state = state, actions = actions)

            // Last on the screen, below the library summary: updating the app is the rarest thing
            // anyone comes to Settings to do, and it has nothing to do with the server connection
            // and sync scope above it.
            //
            // Hidden outright in a debug build rather than shown and inert: a debug install is a
            // different package at versionCode 1, so there is nothing here that could work and
            // nothing to explain. Gated on state, never on BuildConfig — previews and screenshot
            // tests build the debug variant, and reading the flag here would blank every golden.
            // The divider goes inside, so a debug build ends the screen on the summary line rather
            // than on a rule with nothing under it.
            if (!state.isDebugBuild) {
                HorizontalDivider()

                UpdatesSection(state = state, actions = actions, now = now)
            }

            // Truly last, under the Updates section on a release build and under the library
            // summary on a debug one — it is reference material, not something anyone comes here
            // to act on. Drawn only when there is a version to name, so a preview or test that
            // never set one renders no line rather than a bare "Version ".
            if (state.versionName.isNotBlank()) {
                Text(
                    stringResource(R.string.app_version, state.versionName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showSignOutDialog) {
        // Two wordings for one action: a demo has no connection to retype and can be reloaded in a
        // tap, so warning it about server URLs and API keys would overstate what it costs.
        val demo = state.demoMode
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = {
                Text(
                    stringResource(
                        if (demo) R.string.sign_out_dialog_title_demo else R.string.sign_out_dialog_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (demo) R.string.sign_out_dialog_text_demo else R.string.sign_out_dialog_text,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        actions.signOut()
                    },
                ) {
                    Text(
                        stringResource(if (demo) R.string.leave_demo else R.string.sign_out),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * One status slot, rendered in the section that produced it — see [StatusLine] for why there are
 * four of them rather than one. Draws nothing when there is nothing to say, so an absent line
 * costs no vertical space (the parent Column's spacing would otherwise show as a gap).
 */
@Composable
private fun StatusText(status: StatusLine?) {
    status ?: return
    Text(
        status.text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (status.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

/**
 * The in-app update check: which channel to watch, when it last ran, and a manual trigger.
 *
 * Only ever composed in a release build (its caller gates on `state.isDebugBuild`), but it reads
 * nothing about the build itself — it is a plain function of the state it is handed, which is what
 * keeps it renderable in previews and screenshot tests on the debug variant.
 *
 * "Check now" stays visible when the channel is Off, just disabled: the feature has to be
 * discoverable before opting in, and a control that appears only after you have already found the
 * setting explains nothing.
 */
@Composable
private fun UpdatesSection(
    state: SettingsUiState,
    actions: SettingsActions,
    now: Long,
) {
    Text(stringResource(R.string.updates), style = MaterialTheme.typography.titleMedium)

    UpdateSourceSelector(
        selected = state.updateSource,
        onSelect = actions.onUpdateSourceChange,
        signingIn = state.signingInTester,
    )

    Text(
        stringResource(state.updateSource.hintRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(
        onClick = actions.checkForUpdates,
        enabled = state.updateSource != UpdateSource.NONE &&
            !state.checkingUpdate &&
            !state.signingInTester,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state.signingInTester) R.string.signing_in else R.string.check_for_updates,
            ),
        )
    }

    // Reuses formatSyncTime so this reads like the "synced …" line below rather than inventing a
    // second time format on the same screen.
    Text(
        formatSyncTime(state.lastUpdateCheckAt, now)
            ?.let { stringResource(R.string.update_last_checked, it) }
            ?: stringResource(R.string.update_never_checked),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.updateError?.let {
        Text(
            stringResource(it.messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    // The answer a successful check produces when it finds nothing. Without it the only feedback is
    // the "checked just now" line above, which says the check *ran* — not what it concluded.
    if (state.upToDate) {
        Text(
            stringResource(R.string.update_up_to_date),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The API-key fallback, collapsed behind "Alternative sign in", directly above the Sign in button.
 *
 * A Jellyfin API key is server-wide and admin-scoped, so it is deliberately no longer the path of
 * least resistance — signing in is. It stays available for setups that can't use a password login
 * (and is what the app falls back to when no user is signed in), but a user has to go looking.
 *
 * The user picker lives here too, not in the main section: API keys are server-wide, so the app
 * has to ask *which* user's watch state to read and write. A token already answers that, which is
 * why the whole section is gone once signed in — there is nothing here a signed-in user can do,
 * and [Settings.credential] would ignore the key anyway.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlternativeSignInSection(
    state: SettingsUiState,
    actions: SettingsActions,
    initiallyExpanded: Boolean = false,
) {
    if (state.signedIn) return

    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.hide_alternative_sign_in else R.string.show_alternative_sign_in,
            ),
        )
    }
    if (!expanded) return

    Text(
        stringResource(R.string.api_key_explained),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.apiKey,
        onValueChange = actions.onApiKeyChange,
        label = { Text(stringResource(R.string.api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            // Opted out like the two addresses, though this one *is* a secret: a manager offered
            // a server-wide admin key as this app's password would be offering the wrong
            // credential, and expanding this section would put a second password-shaped field
            // beside the real one for the provider to choose between.
            .semantics { contentDataType = ContentDataType.None },
    )

    OutlinedButton(
        onClick = actions.connect,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.connect_load_users)) }

    // Under the button it answers: "Connected — 3 users" sitting by the sign-in form used to read
    // as a report on the sign-in.
    StatusText(state.connectStatus)

    if (state.users.isNotEmpty()) {
        Text(stringResource(R.string.user), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.users.forEach { user ->
                FilterChip(
                    selected = user.id == state.selectedUserId,
                    onClick = { actions.selectUser(user) },
                    label = { Text(user.name) },
                )
            }
        }
    }
}

/**
 * The power-user settings, collapsed behind "Advanced": the metadata feeds. The playback-handoff
 * switch used to live here too — it sits in the open above the Updates section now, where a user
 * whose playback just failed can actually find it. The API-key sign-in path is
 * [AlternativeSignInSection], up by the form it stands in for.
 *
 * Everything in here only exists once there are credentials ([SettingsUiState.canEditIndex] —
 * a sign-in or an API key), so until then the expander is disabled rather than opening onto
 * nothing. Disabled, not hidden: the section stays discoverable, and its unlocking on sign-in
 * says what it is waiting for.
 */
@Composable
private fun AdvancedSection(
    state: SettingsUiState,
    actions: SettingsActions,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    TextButton(onClick = { expanded = !expanded }, enabled = state.canEditIndex) {
        Text(
            stringResource(
                if (expanded) R.string.hide_advanced else R.string.show_advanced,
            ),
        )
    }
    // The second gate matters on its own: a sign-out while the section is open must take the
    // fields with it, not leave them editable under a disabled expander.
    if (!expanded || !state.canEditIndex) return

    IndexUrlField(state = state, actions = actions)
    MetadataApiUrlField(state = state, actions = actions)
    MetadataApiTokenField(state = state, actions = actions)
}

/**
 * The playback-handoff switch: whether the token rides in the playback URL instead of a header.
 * Independent of which credential is in use, so it is live signed in or out.
 */
@Composable
private fun TokenInQueryRow(state: SettingsUiState, actions: SettingsActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.token_in_query))
            Text(
                stringResource(R.string.token_in_query_explained),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.tokenInQuery, onCheckedChange = actions.onTokenInQueryChange)
    }
}

/**
 * The horizontal offset the sync-scope section is drawn at: zero, except for three quick
 * oscillations each time [nudge] fires.
 *
 * The shake is the *only* motion form of the nudge, so it has to survive the accessibility
 * "remove animations" setting — where [MotionDurationScale] reports a scale of zero, the animation
 * would complete instantly and the user would see nothing at all. That is not a silent no-op here:
 * the same nudge always writes "Check the sync scope first" to the status line, which is the form
 * a screen reader and a stopped animation both still get. This function simply skips the motion.
 */
@Composable
private fun rememberScopeShake(nudge: Flow<Unit>): Animatable<Float, AnimationVector1D> {
    val offset = remember { Animatable(0f) }
    val shakePx = with(LocalDensity.current) { SHAKE_DISTANCE.toPx() }
    val motionScale = LocalContext.current.animationsEnabled()
    LaunchedEffect(nudge, shakePx, motionScale) {
        nudge.collect {
            if (!motionScale) return@collect
            repeat(SHAKE_CYCLES) {
                offset.animateTo(shakePx, tween(SHAKE_STEP_MS))
                offset.animateTo(-shakePx, tween(SHAKE_STEP_MS))
            }
            offset.animateTo(0f, tween(SHAKE_STEP_MS))
        }
    }
    return offset
}

/** The system's "remove animations" accessibility setting, as a plain boolean. */
private fun Context.animationsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** The metadata index URL — a [LockableField] like the API URL below it. */
@Composable
private fun IndexUrlField(state: SettingsUiState, actions: SettingsActions) {
    LockableField(
        value = state.indexUrl,
        onValueChange = actions.onIndexUrlChange,
        labelRes = R.string.index_url_label,
        hintRes = R.string.index_url_hint,
        protected = state.indexProtected,
        fillEnabled = state.serverUrl.isNotBlank(),
        onFill = actions.fillIndexUrlFromServer,
        lockRes = R.string.lock_index_url,
        unlockRes = R.string.unlock_index_url,
        testTag = INDEX_URL_FIELD_TAG,
        errorRes = R.string.metadata_index_unavailable.takeIf { state.indexUnavailable },
    )
}

/** The metadata API base URL — same protection rules as the index URL. */
@Composable
private fun MetadataApiUrlField(state: SettingsUiState, actions: SettingsActions) {
    LockableField(
        value = state.metadataApiUrl,
        onValueChange = actions.onMetadataApiUrlChange,
        labelRes = R.string.metadata_api_url_label,
        hintRes = R.string.metadata_api_url_hint,
        protected = state.indexProtected,
        fillEnabled = state.serverUrl.isNotBlank(),
        onFill = actions.fillMetadataApiUrlFromServer,
        lockRes = R.string.lock_api_url,
        unlockRes = R.string.unlock_api_url,
        testTag = METADATA_API_URL_FIELD_TAG,
        errorRes = R.string.metadata_api_unavailable.takeIf { state.metadataApiUnavailable },
    )
}

/**
 * The metadata API's bearer token. A secret, presented like the Jellyfin API key above it —
 * password-masked, opted out of autofill for the same reason: a password manager offering to
 * store a server token as this app's password would be remembering the wrong credential. Locked
 * like the URLs once a sync has run, so all three feed inputs share one protection story and the
 * same deliberate, unremembered unlock — but with no **Fill**, because a secret has nothing safe
 * to fill from.
 */
@Composable
private fun MetadataApiTokenField(state: SettingsUiState, actions: SettingsActions) {
    // The two error states the token can be in, most actionable first: a gap the user can see
    // before syncing, then the server's verdict on the token a sync actually sent.
    val errorRes = when {
        state.metadataApiTokenMissing -> R.string.metadata_api_token_required
        state.metadataApiAuthFailed -> R.string.metadata_api_auth_failed
        else -> null
    }
    LockableField(
        value = state.metadataApiToken,
        onValueChange = actions.onMetadataApiTokenChange,
        labelRes = R.string.metadata_api_token,
        protected = state.indexProtected,
        lockRes = R.string.lock_api_token,
        unlockRes = R.string.unlock_api_token,
        testTag = METADATA_API_TOKEN_FIELD_TAG,
        errorRes = errorRes,
        visualTransformation = PasswordVisualTransformation(),
    )
}

/**
 * A metadata feed input — the two URLs and the API token — shown only once there are credentials
 * to use it with ([SettingsUiState.canEditIndex]).
 *
 * Its trailing control changes with what there is to lose. Before the first sync the useful action
 * is filling the field in, so **Fill** stays exactly as it was — but only where an [onFill] source
 * exists; the token has none. Once a sync has run there is a populated library to damage, and the
 * field locks: a mistyped feed URL produces a half-populated library with no obvious cause. The
 * unlock is deliberate and deliberately not remembered — it resets every time this screen is
 * composed, so an accidental unlock cannot follow the user around.
 */
@Composable
private fun LockableField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
    protected: Boolean,
    @StringRes lockRes: Int,
    @StringRes unlockRes: Int,
    testTag: String,
    @StringRes hintRes: Int? = null,
    fillEnabled: Boolean = false,
    onFill: (() -> Unit)? = null,
    @StringRes errorRes: Int? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var unlocked by remember { mutableStateOf(false) }
    val locked = protected && !unlocked
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        placeholder = hintRes?.let { { Text(stringResource(it)) } },
        singleLine = true,
        visualTransformation = visualTransformation,
        isError = errorRes != null,
        supportingText = errorRes?.let { { Text(stringResource(it)) } },
        // readOnly, not enabled = false: a locked field still has to be *readable*, and the
        // disabled colours wash the URL out to the point of being hard to check at a glance.
        readOnly = locked,
        trailingIcon = {
            if (protected) {
                LockToggle(
                    unlocked = unlocked,
                    onToggle = { unlocked = !unlocked },
                    lockRes = lockRes,
                    unlockRes = unlockRes,
                )
            } else if (onFill != null && value.isBlank()) {
                TextButton(
                    onClick = onFill,
                    enabled = fillEnabled,
                ) { Text(stringResource(R.string.fill)) }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            // Not a credential either — same reasoning as the server URL above.
            .semantics { contentDataType = ContentDataType.None }
            .testTag(testTag),
    )
}

/**
 * The lock/unlock button. A custom two-state control, so it carries a `stateDescription` the way
 * [ThemeModeSwitch] does — the icon alone tells a screen reader nothing about which state it is
 * in, only what tapping it would do.
 */
@Composable
private fun LockToggle(
    unlocked: Boolean,
    onToggle: () -> Unit,
    @StringRes lockRes: Int,
    @StringRes unlockRes: Int,
) {
    val stateLabel = stringResource(
        if (unlocked) R.string.index_url_state_unlocked else R.string.index_url_state_locked,
    )
    IconButton(
        onClick = onToggle,
        modifier = Modifier.semantics { stateDescription = stateLabel },
    ) {
        Icon(
            if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
            contentDescription = stringResource(if (unlocked) lockRes else unlockRes),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScopeSection(state: SettingsUiState, actions: SettingsActions) {
    Text(stringResource(R.string.sync_scope), style = MaterialTheme.typography.titleSmall)
    Text(
        // A blank persisted path is the root scope; the label is resolved here so it follows
        // the device language instead of freezing in the language it was saved in.
        state.selectedScopePath.ifBlank { stringResource(R.string.all_collections) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )

    // The section's own line: folder-browser failures, and the "check the sync scope" nudge that
    // the shake below points at. Above the controls, so the nudge is read before the tap it wants.
    StatusText(state.scopeStatus)

    if (!state.browserOpen) {
        TextButton(onClick = actions.openBrowser) { Text(stringResource(R.string.change_folder)) }
        return
    }

    // Breadcrumb: All collections › folder › subfolder …
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(onClick = { actions.navigateTo(-1) }, label = { Text(stringResource(R.string.all_collections)) })
        state.breadcrumb.forEachIndexed { index, folder ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            AssistChip(onClick = { actions.navigateTo(index) }, label = { Text(folder.name) })
        }
    }

    // Action row kept ABOVE the folder list so it stays reachable when a folder
    // has many children (the whole screen scrolls; the list can be very long).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.useCurrentFolder) { Text(stringResource(R.string.use_this_folder)) }
        TextButton(onClick = actions.closeBrowser) { Text(stringResource(R.string.cancel)) }
    }

    when {
        state.loadingFolders -> CircularProgressIndicator()
        state.childFolders.isEmpty() ->
            Text(
                stringResource(R.string.no_subfolders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else -> state.childFolders.forEach { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.enterFolder(folder) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(folder.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.open))
            }
        }
    }
}

/** How far the scope section swings either way when the nudge fires. */
private val SHAKE_DISTANCE = 8.dp

/** Three there-and-back swings plus the settle, at [SHAKE_STEP_MS] each — ~300 ms in total. */
private const val SHAKE_CYCLES = 3
private const val SHAKE_STEP_MS = 40
