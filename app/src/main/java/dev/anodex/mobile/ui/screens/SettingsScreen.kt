package dev.anodex.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.LocalModel
import dev.anodex.mobile.chat.Personality
import dev.anodex.mobile.chat.detailLabel
import dev.anodex.mobile.notify.NotificationAccess
import dev.anodex.mobile.ui.components.Hairline
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.AnodexSpinner
import dev.anodex.mobile.ui.components.SpinnerVariant
import dev.anodex.mobile.ui.components.PersonalityAvatar
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.memory.MemoryEntry
import dev.anodex.mobile.profile.UserProfile
import dev.anodex.mobile.profile.UsageProfile
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Project
import dev.anodex.mobile.update.UpdateCheck
import dev.anodex.mobile.ui.theme.FontScale
import dev.anodex.mobile.ui.theme.UiFont
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.anodex.mobile.ui.theme.MotionPreference
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * Settings, divided the way the desktop divides them.
 *
 * An index of sections rather than one long scroll. The desktop already made this
 * split and people move between the two, so a setting filed under "AI & Models" on
 * the computer should not be three rows below the version number here.
 *
 * Two sections have nothing to change yet and say so rather than being hidden. A
 * section missing from the phone reads as the app being unfinished; one that says
 * where the setting lives, and why, is an answer. `settings:` and `memory:` are both
 * denied to a paired phone deliberately, so opening them is a decision about the
 * protocol rather than an afternoon of UI.
 *
 * Everything here except the theme belongs to the computer and moves for whoever is
 * sitting at it too, exactly like the active project does.
 */
@Composable
fun SettingsScreen(
    installedVersion: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    personalities: List<Personality> = emptyList(),
    activePersonalityId: String? = null,
    busy: Boolean = false,
    onSelectPersonality: (String?) -> Unit = {},
    /** The machine this phone is driving. Null when it has never been paired. */
    hostName: String? = null,
    hostStatus: String = "Not connected",
    onOpenHost: (() -> Unit)? = null,
    /** Whose Anodex this is, read from the computer. Null until it answers. */
    user: UserProfile? = null,
    /** Lifetime activity, as the computer counts it. Null until it answers. */
    usage: UsageProfile? = null,
    profileLoading: Boolean = false,
    profileError: String? = null,
    /** Chats the computer has archived. Restored or deleted from here only. */
    archivedChats: List<ConversationSummary> = emptyList(),
    archivedProjects: List<Project> = emptyList(),
    archiveLoading: Boolean = false,
    archiveError: String? = null,
    onRestoreArchived: (Archived) -> Unit = {},
    onDeleteArchived: (Archived) -> Unit = {},
    /** The computer's own explanation for the current connection state, if any. */
    connectionHint: String? = null,
    /** The last crash this app recorded, kept for reporting. Null if it never has. */
    lastCrash: String? = null,
    onCopyCrash: () -> Unit = {},
    onReportCrash: () -> Unit = {},
    onForgetCrash: () -> Unit = {},
    /** What a manual update check found, so the button can answer. */
    updateCheck: UpdateCheck = UpdateCheck.Idle,
    onCheckForUpdates: () -> Unit = {},
    /** The build the computer expects, when this phone is behind it. Null if not. */
    newerVersion: String? = null,
    /** What is already on the computer. Never what could be downloaded. */
    models: List<LocalModel> = emptyList(),
    /** The path of the model actually running, so the list can mark it. */
    activeModelPath: String? = null,
    /** The path being loaded right now, if any. */
    loadingModelPath: String? = null,
    onLoadModel: (String) -> Unit = {},
    /** How this app picks its palette \u2014 the one phone-local setting here. */
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onSelectTheme: (ThemeMode) -> Unit = {},
    /** How large the interface is set. Phone-local, like the theme. */
    fontScale: FontScale = FontScale.MEDIUM,
    onSelectFontScale: (FontScale) -> Unit = {},
    uiFont: UiFont = UiFont.SYSTEM,
    onSelectFont: (UiFont) -> Unit = {},
    motion: MotionPreference = MotionPreference.SYSTEM,
    onSelectMotion: (MotionPreference) -> Unit = {},
    keepAwake: Boolean = true,
    onSetKeepAwake: (Boolean) -> Unit = {},
    haptics: Boolean = true,
    onSetHaptics: (Boolean) -> Unit = {},
    streamOnMetered: Boolean = false,
    onSetStreamOnMetered: (Boolean) -> Unit = {},
    /** What the system will actually let through. */
    notificationAccess: NotificationAccess = NotificationAccess(true, true, true, true),
    onOpenNotificationSettings: () -> Unit = {},
    onAllowBackground: () -> Unit = {},
    /** What the computer remembers. Read and forget only; nothing here writes one. */
    memories: List<MemoryEntry> = emptyList(),
    memoryLoading: Boolean = false,
    memoryError: String? = null,
    onForgetMemory: ((MemoryEntry) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors

    // Which section is open, or null on the index. Saved, so rotating the phone in
    // the middle of choosing a model does not throw you back to the top.
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    // Back leaves the section first and the screen second, which is what the arrow
    // in the corner already implies.
    BackHandler(enabled = section != null) { section = null }

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Header(
            title = section?.label ?: "Settings",
            onBack = { if (section != null) section = null else onClose() },
        )

        when (section) {
            null -> SettingsIndex(
                hostName = hostName,
                hostStatus = hostStatus,
                installedVersion = installedVersion,
                themeMode = themeMode,
                updateAvailable = newerVersion != null,
                onOpen = { section = it },
            )

            // No longer "at the computer, and nothing else". Editing a profile is
            // still a setting and still unreachable — `settings:` carries the
            // permission mode and the model directory — but that was being used to
            // justify an empty screen, including the numbers the computer already
            // keeps. `settings:get-profile` is a read and nothing more.
            SettingsSection.PROFILE -> SectionBody(spacing = Spacing.x5) {
                ProfileScreen(
                    user = user,
                    usage = usage,
                    loading = profileLoading,
                    error = profileError,
                    modifier = Modifier.padding(vertical = Spacing.x4),
                )
            }

            // Reading was previously refused here on the grounds that it would put
            // the contents of every note on a device that gets left on tables. That
            // argument does not survive contact with the rest of the app: this phone
            // already shows whole conversations, the mailbox and the project's files,
            // all of which say considerably more than a memory note does. Memory was
            // being held to a standard nothing beside it meets.
            //
            // What genuinely does not belong here is *writing* one. A memory is fed
            // into later prompts, so adding one from a phone steers every future
            // conversation — which is why `memory:create` and `memory:update` are
            // still denied, and why this screen has no control that would call them.
            SettingsSection.MEMORY -> MemoryScreen(
                entries = memories,
                loading = memoryLoading,
                error = memoryError,
                onForget = onForgetMemory,
            )

            SettingsSection.APPEARANCE -> AppearanceSection(
                mode = themeMode,
                onSelect = onSelectTheme,
                fontScale = fontScale,
                onSelectFontScale = onSelectFontScale,
                uiFont = uiFont,
                onSelectFont = onSelectFont,
                motion = motion,
                onSelectMotion = onSelectMotion,
                keepAwake = keepAwake,
                onSetKeepAwake = onSetKeepAwake,
                haptics = haptics,
                onSetHaptics = onSetHaptics,
                streamOnMetered = streamOnMetered,
                onSetStreamOnMetered = onSetStreamOnMetered,
            )

            SettingsSection.AI_MODELS -> AiAndModelsSection(
                personalities = personalities,
                activePersonalityId = activePersonalityId,
                busy = busy,
                onSelectPersonality = onSelectPersonality,
                models = models,
                activeModelPath = activeModelPath,
                loadingModelPath = loadingModelPath,
                onLoadModel = onLoadModel,
            )

            SettingsSection.NOTIFICATIONS -> NotificationsSection(
                access = notificationAccess,
                onOpenSystemSettings = onOpenNotificationSettings,
                onAllowBackground = onAllowBackground,
            )

            SettingsSection.REMOTE -> RemoteSection(hostName, hostStatus, onOpenHost)

            SettingsSection.ARCHIVE -> SectionBody(spacing = Spacing.x4) {
                ArchiveScreen(
                    chats = archivedChats,
                    projects = archivedProjects,
                    loading = archiveLoading,
                    error = archiveError,
                    onRestore = onRestoreArchived,
                    onDelete = onDeleteArchived,
                    modifier = Modifier.padding(bottom = Spacing.x5),
                )
            }

            SettingsSection.DIAGNOSTICS -> SectionBody(spacing = Spacing.x4) {
                DiagnosticsScreen(
                    hostName = hostName,
                    connectionStatus = hostStatus,
                    connectionHint = connectionHint,
                    lastCrash = lastCrash,
                    onCopyCrash = onCopyCrash,
                    onReportCrash = onReportCrash,
                    onForgetCrash = onForgetCrash,
                    modifier = Modifier.padding(bottom = Spacing.x5),
                )
            }

            SettingsSection.ABOUT -> AboutSection(
                installedVersion = installedVersion,
                newerVersion = newerVersion,
                updateCheck = updateCheck,
                onCheckForUpdates = onCheckForUpdates,
            )
        }
    }
}

/** The six doors, each saying what is behind it. */
@Composable
private fun SettingsIndex(
    hostName: String?,
    hostStatus: String,
    installedVersion: String,
    themeMode: ThemeMode,
    updateAvailable: Boolean,
    onOpen: (SettingsSection) -> Unit,
) {
    SectionBody {
        Box(Modifier.heightIn(min = Spacing.x2, max = Spacing.x2))

        Group {
            SettingsSection.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                SettingsRow(
                    icon = entry.icon,
                    label = entry.label,
                    // The current value where there is one, so the question people
                    // actually open Settings to answer is answered on the index.
                    value = when (entry) {
                        SettingsSection.APPEARANCE -> themeMode.label
                        SettingsSection.REMOTE ->
                            hostName?.let { "$it \u00b7 $hostStatus" } ?: hostStatus
                        SettingsSection.ABOUT ->
                            if (updateAvailable) "Update available" else installedVersion
                        else -> entry.summary
                    },
                    onClick = { onOpen(entry) },
                )
            }
        }
    }
}

/**
 * A section that exists on the computer and is not reachable from here.
 *
 * Shown rather than hidden. A section missing from the phone reads as the app being
 * unfinished; one that says where the setting lives, and why, is an answer.
 */
@Composable
private fun AtTheComputer(what: String, why: String) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    SectionBody(spacing = Spacing.x3) {
        Box(Modifier.heightIn(min = Spacing.x4, max = Spacing.x4))
        Text(what, style = type.bodyEmphasis, color = colors.text)
        Text("Set it at your computer.", style = type.body, color = colors.textMuted)
        Text(why, style = type.meta, color = colors.textFaint)
    }
}

@Composable
private fun AppearanceSection(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    fontScale: FontScale,
    onSelectFontScale: (FontScale) -> Unit,
    uiFont: UiFont,
    onSelectFont: (UiFont) -> Unit,
    motion: MotionPreference,
    onSelectMotion: (MotionPreference) -> Unit,
    keepAwake: Boolean,
    onSetKeepAwake: (Boolean) -> Unit,
    haptics: Boolean,
    onSetHaptics: (Boolean) -> Unit,
    streamOnMetered: Boolean,
    onSetStreamOnMetered: (Boolean) -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    SectionBody {
        SectionLabel("Theme")

        Group {
            ThemeMode.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    label = entry.label,
                    detail = entry.description,
                    selected = entry == mode,
                    onClick = { onSelect(entry) },
                )
            }
        }

        SectionLabel("Text size")

        Group {
            FontScale.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    label = entry.label,
                    detail = entry.description,
                    selected = entry == fontScale,
                    onClick = { onSelectFontScale(entry) },
                )
            }
        }

        SectionLabel("Font")

        Group {
            UiFont.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    label = entry.label,
                    detail = entry.description,
                    selected = entry == uiFont,
                    onClick = { onSelectFont(entry) },
                )
            }
        }

        // A preview, so the choice is visible without leaving the screen to check.
        // These two lines are the ones that matter: a reply is read at length, and the
        // line under it is the smallest thing the app ever asks anyone to read. If
        // both are comfortable here, the setting is right.
        Column(
            modifier = Modifier
                .padding(top = Spacing.x4)
                .fillMaxWidth()
                .clip(Radii.lg)
                .background(colors.bgSurface)
                .padding(Spacing.x4),
        ) {
            Text("Preview", style = type.label, color = colors.textFaint)
            Spacer(Modifier.height(Spacing.x2))
            Text(
                text = "Anodex replies look like this — a paragraph or two, read rather " +
                    "than scanned, held at whatever distance you hold your phone.",
                style = type.chatBody,
                color = colors.text,
            )
            Spacer(Modifier.height(Spacing.x2))
            Text("Qwen3-30B · 12% of 32K", style = type.meta, color = colors.textMuted)
        }

        SectionLabel("Motion")

        Group {
            MotionPreference.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    label = entry.label,
                    detail = entry.description,
                    selected = entry == motion,
                    onClick = { onSelectMotion(entry) },
                )
            }
        }

        SectionLabel("While you are watching")

        Group {
            ToggleRow(
                label = "Keep the screen awake",
                detail = "While a reply is arriving, and only then",
                checked = keepAwake,
                onChange = onSetKeepAwake,
            )
            RowDivider()
            ToggleRow(
                label = "Haptics",
                detail = "A tap you can feel when something needs you",
                checked = haptics,
                onChange = onSetHaptics,
            )
            RowDivider()
            ToggleRow(
                label = "Live text on mobile data",
                detail = "Off, replies arrive whole when they finish. Wi-Fi is unaffected.",
                checked = streamOnMetered,
                onChange = onSetStreamOnMetered,
            )
        }

        Footnote(
            "These are only about this phone. Everything else in Settings is your " +
                "computer’s, and moves for whoever is sitting at it too.",
        )
    }
}

@Composable
private fun AiAndModelsSection(
    personalities: List<Personality>,
    activePersonalityId: String?,
    busy: Boolean,
    onSelectPersonality: (String?) -> Unit,
    models: List<LocalModel>,
    activeModelPath: String?,
    loadingModelPath: String?,
    onLoadModel: (String) -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    SectionBody {
        SectionLabel("How Anodex answers")

        Group {
            // Empty before the computer has answered, and after a connection that
            // dropped. Said plainly rather than shown as a card with no rows in it,
            // which reads as a broken setting instead of a pending one.
            if (personalities.isEmpty()) {
                Text(
                    text = "Waiting for your computer\u2026",
                    style = type.body,
                    color = colors.textFaint,
                    modifier = Modifier.padding(Spacing.x4),
                )
            }

            personalities.forEachIndexed { index, personality ->
                if (index > 0) RowDivider()
                PersonalityRow(
                    personality = personality,
                    selected = personality.id == activePersonalityId,
                    enabled = !busy,
                    onClick = { onSelectPersonality(personality.id) },
                )
            }
        }

        Footnote("Changing this changes how the computer replies, for both of you.")

        if (models.isNotEmpty()) {
            SectionLabel("Model")

            Group {
                models.forEachIndexed { index, model ->
                    if (index > 0) RowDivider()
                    ModelRow(
                        model = model,
                        active = model.path == activeModelPath,
                        loading = model.path == loadingModelPath,
                        // One load at a time. A second while the first is still going
                        // would queue a minutes-long job behind another.
                        enabled = loadingModelPath == null,
                        onClick = { onLoadModel(model.path) },
                    )
                }
            }

            Footnote(
                "Only what is already on your computer. Downloading a new model is done " +
                    "at the machine.",
            )
        }
    }
}

@Composable
private fun NotificationsSection(
    access: NotificationAccess,
    onOpenSystemSettings: () -> Unit,
    onAllowBackground: () -> Unit,
) {
    SectionBody {
        SectionLabel("What may reach you")

        Group {
            SettingsRow(
                icon = AnodexIcon.INFO,
                label = "Waiting for you",
                value = if (access.approvals) "On" else "Off",
                onClick = onOpenSystemSettings,
            )
            RowDivider()
            SettingsRow(
                icon = AnodexIcon.CLOCK,
                label = "Finished work",
                value = if (access.activity) "On" else "Off",
                onClick = onOpenSystemSettings,
            )
        }

        // Said plainly, because until now there was nowhere in the app that could
        // say it. A phone that has refused the permission — or been refused it twice,
        // after which Android stops delivering the request at all — simply went quiet,
        // and looked identical to a computer with nothing to report.
        if (!access.allowed) {
            Footnote(
                "Notifications are off, so nothing here can reach you. A run stopped " +
                    "waiting for an approval will wait until you open the app and look.",
            )
            SecondaryButton(
                label = "Turn them on",
                onClick = onOpenSystemSettings,
                modifier = Modifier.padding(top = Spacing.x3),
            )
        } else if (!access.approvals) {
            // The one worth calling out separately. Silencing the quiet channel is a
            // reasonable choice; silencing this one stops the phone being able to do
            // the thing it is for.
            Footnote(
                "“Waiting for you” is switched off. That is the one that " +
                    "matters: a run stopped until somebody answers cannot tell you, and " +
                    "will stay stopped.",
            )
            SecondaryButton(
                label = "Open notification settings",
                onClick = onOpenSystemSettings,
                modifier = Modifier.padding(top = Spacing.x3),
            )
        } else {
            Footnote(
                "Split by urgency rather than by feature, so an approval can interrupt " +
                    "and a finished run cannot. Both are yours to change above — they " +
                    "are ordinary Android channels.",
            )
        }

        SectionLabel("Running in the background")

        Group {
            SettingsRow(
                icon = AnodexIcon.SMARTPHONE,
                label = "Keep the link alive",
                value = if (access.background) "Allowed" else "Restricted",
                onClick = onAllowBackground,
            )
        }

        if (!access.background) {
            // The half that is invisible until it bites. Notifications can be fully
            // granted and still never arrive, because every one of them comes over
            // the live link and the battery manager kills the service holding it.
            // On some manufacturers that is the default.
            Footnote(
                "Your phone is allowed to stop Anodex in the background, which stops " +
                    "the connection carrying these. An approval waiting on you would " +
                    "not arrive until you opened the app.",
            )
            SecondaryButton(
                label = "Allow it to keep running",
                onClick = onAllowBackground,
                modifier = Modifier.padding(top = Spacing.x3),
            )
        } else {
            Footnote(
                "These arrive over the link to your computer, so they reach you while " +
                    "Anodex is in the background — and not at all while the phone " +
                    "cannot see the computer.",
            )
        }
    }
}

@Composable
private fun RemoteSection(hostName: String?, hostStatus: String, onOpenHost: (() -> Unit)?) {
    SectionBody {
        SectionLabel("Your computer")

        Group {
            SettingsRow(
                icon = AnodexIcon.MONITOR,
                label = hostName ?: "Not paired",
                value = hostStatus,
                onClick = onOpenHost,
            )
        }

        Footnote(
            "The full picture \u2014 model, context, project, and unpairing \u2014 is on the " +
                "computer\u2019s own screen.",
        )
    }
}

@Composable
private fun AboutSection(
    installedVersion: String,
    newerVersion: String?,
    updateCheck: UpdateCheck,
    onCheckForUpdates: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val context = LocalContext.current

    /**
     * Open a page in the browser.
     *
     * Wrapped because a phone can be without a browser — rare, but the throw is an
     * `ActivityNotFoundException` that takes the app down, and a dead link is a much
     * smaller problem than a crash from Settings.
     */
    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    SectionBody {

        Group {
            SettingsRow(icon = AnodexIcon.INFO, label = "Version", trailing = installedVersion)

            if (newerVersion != null) {
                RowDivider()
                Column(Modifier.padding(Spacing.x4)) {
                    Text(
                        text = "$newerVersion is available",
                        style = type.bodyEmphasis,
                        color = colors.accentInk,
                    )
                    Text(
                        text = "The banner at the top of the app installs it, and your " +
                            "pairing is kept.",
                        style = type.meta,
                        color = colors.textMuted,
                    )
                }
            }
        }

        Group {
            SettingsRow(
                icon = AnodexIcon.REFRESH,
                label = "Check for updates",
                value = when (updateCheck) {
                    is UpdateCheck.Checking -> "Looking…"
                    is UpdateCheck.UpToDate -> "This is the newest build"
                    is UpdateCheck.Found -> "${updateCheck.version} is available"
                    is UpdateCheck.Failed -> updateCheck.message
                    else -> "Asks GitHub for the latest release"
                },
                onClick = onCheckForUpdates,
            )
        }

        SectionLabel("This phone")

        // What a bug report asks for, in the order the form asks for it, so it can be
        // read off rather than hunted down in the system settings.
        Group {
            SettingsRow(
                icon = AnodexIcon.SMARTPHONE,
                label = "Device",
                trailing = deviceName(),
            )
            RowDivider()
            SettingsRow(
                icon = AnodexIcon.CPU,
                label = "Android",
                trailing = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            )
        }

        SectionLabel("Something wrong, or something missing?")

        Group {
            SettingsRow(
                icon = AnodexIcon.CHAT,
                label = "Report a bug",
                value = "Opens the form, which asks for what is usually missing",
                onClick = { open("$REPOSITORY/issues/new?template=bug_report.yml") },
            )
            RowDivider()
            SettingsRow(
                icon = AnodexIcon.BOT,
                label = "Suggest an idea",
                value = "The problem you are trying to solve is the useful half",
                onClick = { open("$REPOSITORY/issues/new?template=feature_request.yml") },
            )
            RowDivider()
            SettingsRow(
                icon = AnodexIcon.SEARCH,
                label = "Ask a question",
                value = "Discussions, for anything that is not a defect",
                onClick = { open("$REPOSITORY/discussions") },
            )
        }

        Text(
            text = "Please leave keys, tokens and pairing codes out of anything you post.",
            style = type.meta,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        )
    }
}

/** Where a report goes. Public so that problems can be reported against it. */
private const val REPOSITORY = "https://github.com/Anodex/anodex-mobile"

/**
 * The phone, as a person would name it.
 *
 * `MODEL` alone reads as a part number on most devices — "SM-S911B" rather than
 * anything somebody would recognise — so the manufacturer goes in front of it,
 * unless the model already starts with it and would stutter.
 */
private fun deviceName(): String {
    val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = Build.MODEL.orEmpty()
    return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
}

/** The scrolling body every section shares, so they cannot drift apart. */
@Composable
private fun SectionBody(spacing: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
        // Clears the gesture bar, so the last row is reachable rather than sitting
        // under it.
        Box(Modifier.heightIn(min = Spacing.x8, max = Spacing.x8))
    }
}

/** One of a set, with a tick on the one in force. */
/**
 * A setting that is on or off.
 *
 * Styled as [ChoiceRow] rather than as a Material switch row, so a list mixing the two
 * does not look like two different settings screens stitched together. The whole row
 * is the target, which is the only sane size for one on a phone.
 */
@Composable
private fun ToggleRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .clickable { onChange(!checked) }
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = type.bodyEmphasis, color = colors.text)
            Text(detail, style = type.meta, color = colors.textMuted)
        }

        Switch(
            checked = checked,
            // Null, because the row above already handles the tap. A switch with its
            // own handler inside a clickable row toggles twice when the switch itself
            // is hit, which reads as the setting refusing to change.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.textOnAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.bgElevated,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}

@Composable
private fun ChoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = type.bodyEmphasis, color = colors.text)
            Text(detail, style = type.meta, color = colors.textMuted)
        }

        if (selected) Text("\u2713", style = type.body, color = colors.accentInk)
    }
}

/**
 * Back on the left, title in the middle.
 *
 * A chevron rather than a boxed "Back" button: the box drew as much weight as the
 * settings under it, and everything else on the phone leaves going back to a plain
 * affordance in the corner.
 *
 * The title names the open section, so the same arrow reads as "up one" rather
 * than "close" — which is what it now does.
 */
@Composable
private fun Header(title: String, onBack: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.x2)) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
        )

        Box(
            modifier = Modifier
                .size(Touch.minTarget)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            AnodexIcon(AnodexIcon.CHEVRON_LEFT, tint = colors.text, contentDescription = "Back")
        }
    }
}

/** The quiet all-caps label a group sits under. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = AnodexTheme.type.badge,
        color = AnodexTheme.colors.textFaint,
        modifier = Modifier.padding(
            start = Spacing.x2,
            top = Spacing.x5,
            bottom = Spacing.x2,
        ),
    )
}

/** A rounded card of rows, with the app's own ground showing between groups. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.xl)
            .background(AnodexTheme.colors.bgSurface),
        content = content,
    )
}

/** Inset, so it reads as separating two rows rather than cutting the card in half. */
@Composable
private fun RowDivider() {
    Hairline(Modifier.padding(horizontal = Spacing.x4))
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        style = AnodexTheme.type.meta,
        color = AnodexTheme.colors.textFaint,
        modifier = Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x2),
    )
}

/**
 * An icon, a label, and either a value to read or somewhere to go.
 *
 * A row with an `onClick` gets the chevron; one without does not. That is the whole
 * distinction between the two kinds of row, and it has to be legible before the row
 * is tapped rather than after.
 */
@Composable
private fun SettingsRow(
    icon: AnodexIcon,
    label: String,
    value: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Touch.minTarget)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        AnodexIcon(icon, tint = colors.textMuted)

        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) Text(value, style = type.meta, color = colors.textFaint)
        }

        if (trailing != null) Text(trailing, style = type.meta, color = colors.textFaint)

        if (onClick != null) {
            AnodexIcon(AnodexIcon.CHEVRON_RIGHT, size = 16.dp, tint = colors.textFaint)
        }
    }
}

@Composable
private fun PersonalityRow(
    personality: Personality,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .heightIn(min = Touch.minTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        // The face the desktop gives them, so the same personality is recognisable
        // on both screens before a word is read.
        PersonalityAvatar(
            id = personality.id,
            name = personality.name,
            tint = personality.tint,
            size = 30.dp,
        )

        Column(Modifier.weight(1f)) {
            Text(personality.name, style = type.bodyEmphasis, color = colors.text)

            // A personality somebody wrote themselves need not have a one-liner — but a
            // row with a name and nothing under it read as one that had failed to
            // load. Saying where it came from is true and fills the line.
            if (personality.role.isNotBlank()) {
                Text(personality.role, style = type.meta, color = colors.textMuted)
            } else if (!personality.id.startsWith("builtin:")) {
                Text("Your own personality", style = type.meta, color = colors.textFaint)
            }
        }

        if (selected) Text("✓", style = type.body, color = colors.accentInk)
    }
}

/**
 * One model on the computer, and whether it is the one running.
 *
 * Loading is minutes rather than a moment, so the row says "Loading…" for the
 * whole of it. A tap that appears to do nothing for two minutes is indistinguishable
 * from a tap that missed.
 */
@Composable
private fun ModelRow(
    model: LocalModel,
    active: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) colors.accentSoft else Color.Transparent)
            .heightIn(min = Touch.minTarget)
            .clickable(enabled = enabled && !active, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = type.bodyEmphasis,
                color = if (enabled || active) colors.text else colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val detail = model.detailLabel()
            if (detail.isNotBlank()) {
                Text(detail, style = type.meta, color = colors.textFaint)
            }
        }

        when {
            // A model load is minutes, not a moment. The hexagon is the desktop's
            // brand loader and this is the kind of wait it is kept for — spent on
            // every spinner it would stop meaning anything.
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                AnodexSpinner(
                    size = 16.dp,
                    thickness = 1.5.dp,
                    variant = SpinnerVariant.HEX,
                    tint = colors.accentInk,
                )
                Text("Loading…", style = type.meta, color = colors.accentInk)
            }

            active -> Text("✓", style = type.body, color = colors.accentInk)
        }
    }
}

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewSettings() {
    AnodexTheme(darkTheme = true) {
        SettingsScreen(
            installedVersion = "0.26.0",
            onClose = {},
            personalities = listOf(
                Personality("p1", "Anodex", "The default voice.", "accent"),
                Personality("p2", "Vale", "Direct. Answer first, reasoning after.", "series-2"),
            ),
            activePersonalityId = "p1",
            models = listOf(
                LocalModel("/m/qwen.gguf", "Qwen3 30B A3B", 18_500_000_000, "Q4_K_M"),
            ),
            activeModelPath = "/m/qwen.gguf",
            hostName = "Gort",
            hostStatus = "Connected",
            onOpenHost = {},
            newerVersion = "0.27.0",
        )
    }
}
