package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.content.Context
import android.provider.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.ui.DestructiveButton
import com.gmail.volkovskiyda.jellyshelf.ui.formatSyncTime
import com.gmail.volkovskiyda.jellyshelf.ui.rememberNow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * Settings tab: binds [SettingsViewModel] to the stateless [SettingsContent] below, which
 * previews and tests can render without a ViewModel or a Koin container.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videoCount by viewModel.videoCount.collectAsStateWithLifecycle()
    val now by rememberNow()

    SettingsContent(
        state = state,
        videoCount = videoCount,
        actions = SettingsActions(
            onThemeModeClick = viewModel::onThemeModeClick,
            onServerUrlChange = viewModel::onServerUrlChange,
            onApiKeyChange = viewModel::onApiKeyChange,
            onIndexUrlChange = viewModel::onIndexUrlChange,
            onUsernameChange = viewModel::onUsernameChange,
            onPasswordChange = viewModel::onPasswordChange,
            onTokenInQueryChange = viewModel::onTokenInQueryChange,
            fillIndexUrlFromServer = viewModel::fillIndexUrlFromServer,
            signIn = viewModel::signIn,
            signOut = viewModel::signOut,
            connect = { viewModel.connect() },
            selectUser = viewModel::selectUser,
            openBrowser = viewModel::openBrowser,
            closeBrowser = viewModel::closeBrowser,
            enterFolder = viewModel::enterFolder,
            navigateTo = viewModel::navigateTo,
            useCurrentFolder = viewModel::useCurrentFolder,
            syncNow = viewModel::syncNow,
            resetLocalData = viewModel::resetLocalData,
        ),
        modifier = modifier,
        nudgeScope = viewModel.nudgeScope,
        now = now,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    videoCount: Int,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    // Whether the Advanced (API-key) section starts open. A parameter purely so previews and
    // screenshot tests can render it — collapsing it by default would otherwise hide that whole
    // fallback path from the goldens.
    advancedExpanded: Boolean = false,
    // Fired when a Sync now tap was spent pointing at the scope instead of syncing. A flow rather
    // than a flag: the shake happens once and is over, and a flag would have to be cleared.
    nudgeScope: Flow<Unit> = emptyFlow(),
    // Passed in rather than read here, so previews and tests can pin it: a relative label built
    // from the wall clock would make their output depend on when they ran.
    now: Long = 0L,
) {
    var showResetDialog by remember { mutableStateOf(false) }
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
                modifier = Modifier.fillMaxWidth(),
            )
            // The default auth path: a user-scoped token, so nothing the app holds or hands to an
            // external player is a full-server credential.
            OutlinedTextField(
                value = state.username,
                onValueChange = actions.onUsernameChange,
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                enabled = !state.signedIn,
                modifier = Modifier.fillMaxWidth(),
            )
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = if (state.signedIn) actions.signOut else actions.signIn,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (state.signedIn) R.string.sign_out else R.string.sign_in))
            }

            if (state.canEditIndex) IndexUrlField(state = state, actions = actions)

            AdvancedAuthSection(
                state = state,
                actions = actions,
                initiallyExpanded = advancedExpanded,
            )

            if (state.selectedUserId.isNotBlank()) {
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

            DestructiveButton(
                label = stringResource(R.string.reset_local_data),
                onClick = { showResetDialog = true },
                enabled = !state.busy,
            )

            if (state.busy) CircularProgressIndicator()

            state.status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

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
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_dialog_title)) },
            text = {
                Text(stringResource(R.string.reset_dialog_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        actions.resetLocalData()
                    },
                ) {
                    Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * The API-key fallback, collapsed behind "Advanced".
 *
 * A Jellyfin API key is server-wide and admin-scoped, so it is deliberately no longer the path of
 * least resistance — signing in is. It stays available for setups that can't use a password login
 * (and is what the app falls back to when no user is signed in), but a user has to go looking.
 *
 * The user picker lives here too, not in the main section: API keys are server-wide, so the app
 * has to ask *which* user's watch state to read and write. A token already answers that, which is
 * why the whole section is hidden once signed in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedAuthSection(
    state: SettingsUiState,
    actions: SettingsActions,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.hide_advanced else R.string.show_advanced,
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
        // Inert while a user token is held — [Settings.credential] prefers the token.
        enabled = !state.signedIn,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    // Playback handoff. Independent of which credential is in use, so it stays visible when
    // signed in — the API-key affordances below do not.
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

    if (state.signedIn) return

    OutlinedButton(
        onClick = actions.connect,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.connect_load_users)) }

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

/**
 * The metadata index URL, shown only once there are credentials to use it with
 * ([SettingsUiState.canEditIndex]).
 *
 * Its trailing control changes with what there is to lose. Before the first sync the useful action
 * is filling the field in, so **Fill** stays exactly as it was. Once a sync has run there is a
 * populated library to damage, and the field locks: a mistyped index URL produces a half-populated
 * library with no obvious cause. The unlock is deliberate and deliberately not remembered — it
 * resets every time this screen is composed, so an accidental unlock cannot follow the user around.
 */
@Composable
private fun IndexUrlField(state: SettingsUiState, actions: SettingsActions) {
    var unlocked by remember { mutableStateOf(false) }
    val locked = state.indexProtected && !unlocked
    OutlinedTextField(
        value = state.indexUrl,
        onValueChange = actions.onIndexUrlChange,
        label = { Text(stringResource(R.string.index_url_label)) },
        placeholder = { Text(stringResource(R.string.index_url_hint)) },
        singleLine = true,
        // readOnly, not enabled = false: a locked field still has to be *readable*, and the
        // disabled colours wash the URL out to the point of being hard to check at a glance.
        readOnly = locked,
        trailingIcon = {
            if (state.indexProtected) {
                LockToggle(unlocked = unlocked, onToggle = { unlocked = !unlocked })
            } else if (state.indexUrl.isBlank()) {
                TextButton(
                    onClick = actions.fillIndexUrlFromServer,
                    enabled = state.serverUrl.isNotBlank(),
                ) { Text(stringResource(R.string.fill)) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The lock/unlock button. A custom two-state control, so it carries a `stateDescription` the way
 * [ThemeModeSwitch] does — the icon alone tells a screen reader nothing about which state it is
 * in, only what tapping it would do.
 */
@Composable
private fun LockToggle(unlocked: Boolean, onToggle: () -> Unit) {
    val stateLabel = stringResource(
        if (unlocked) R.string.index_url_state_unlocked else R.string.index_url_state_locked,
    )
    IconButton(
        onClick = onToggle,
        modifier = Modifier.semantics { stateDescription = stateLabel },
    ) {
        Icon(
            if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
            contentDescription = stringResource(
                if (unlocked) R.string.lock_index_url else R.string.unlock_index_url,
            ),
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
