package dev.anodex.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anodex.mobile.AnodexViewModel
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.email.EmailAttachment
import androidx.compose.foundation.horizontalScroll
import dev.anodex.mobile.email.MailFolder
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.email.MailFlag
import dev.anodex.mobile.email.MailSwipeAction
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.style.TextAlign
import dev.anodex.mobile.email.EmailThread
import androidx.compose.ui.platform.LocalContext
import dev.anodex.mobile.ui.components.ConfirmDialog
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.EmptyState
import dev.anodex.mobile.ui.components.EmptyTone
import dev.anodex.mobile.ui.components.InlineProblem
import dev.anodex.mobile.ui.components.ListSkeleton
import dev.anodex.mobile.ui.components.ScreenScaffold
import dev.anodex.mobile.ui.components.SearchField
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.components.listPadding
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import androidx.compose.foundation.layout.wrapContentSize
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import java.util.concurrent.TimeUnit

/**
 * The Email tab: the inbox, and one thread when you open it.
 *
 * Read-only. Sending from here would mean composing a message on a phone that then
 * leaves someone else's machine under their name, with none of the desktop's
 * approval step — and the thing actually worth having away from the desk is seeing
 * what arrived, not answering it in a text field.
 */
@Composable
fun EmailPane(viewModel: AnodexViewModel, modifier: Modifier = Modifier) {
    val threads by viewModel.emailThreads.collectAsStateWithLifecycle()
    val loading by viewModel.emailLoading.collectAsStateWithLifecycle()
    val configured by viewModel.emailConfigured.collectAsStateWithLifecycle()
    val emailError by viewModel.emailError.collectAsStateWithLifecycle()
    val openThread by viewModel.openThread.collectAsStateWithLifecycle()
    val threadLoading by viewModel.threadLoading.collectAsStateWithLifecycle()
    val threadError by viewModel.threadError.collectAsStateWithLifecycle()
    val swipeRight by viewModel.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by viewModel.swipeLeft.collectAsStateWithLifecycle()
    val emailAddress by viewModel.emailAddress.collectAsStateWithLifecycle()

    // Fetched when the tab is opened rather than on connect: a user who never opens
    // Email should not be making the desktop hit their mail provider.
    //
    // Keyed on the connection as well as on arriving here, because opening this tab
    // during a reconnect finds no socket to ask and would otherwise sit on an empty
    // inbox until the user thought to leave the tab and come back.
    val connected by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(connected is ConnectionState.Connected) {
        if (connected is ConnectionState.Connected) {
            viewModel.refreshEmail()
            viewModel.refreshFolders()
        }
    }

    val draft by viewModel.mailDraft.collectAsStateWithLifecycle()
    val drafting by viewModel.mailDrafting.collectAsStateWithLifecycle()
    val drafted by viewModel.mailDrafted.collectAsStateWithLifecycle()
    val mailSending by viewModel.mailSending.collectAsStateWithLifecycle()
    val mailError by viewModel.mailError.collectAsStateWithLifecycle()
    val shownImages by viewModel.shownImages.collectAsStateWithLifecycle()
    val mailQuery by viewModel.mailQuery.collectAsStateWithLifecycle()
    val mailResults by viewModel.mailResults.collectAsStateWithLifecycle()
    val mailSearching by viewModel.mailSearching.collectAsStateWithLifecycle()
    val downloadingAttachment by viewModel.downloadingAttachment.collectAsStateWithLifecycle()
    val folders by viewModel.mailFolders.collectAsStateWithLifecycle()
    val openFolder by viewModel.openFolder.collectAsStateWithLifecycle()

    // Above the reader, so Back out of a half-written reply lands on the message it
    // answers rather than on the inbox.
    draft?.let { open ->
        BackHandler { viewModel.closeMail() }
        ComposeMailScreen(
            draft = open,
            onClose = viewModel::closeMail,
            onSend = viewModel::sendMail,
            onAskAnodex = viewModel::draftMail,
            drafting = drafting,
            drafted = drafted,
            sending = mailSending,
            error = mailError,
            fromAddress = emailAddress,
            modifier = modifier,
        )
        return
    }

    // Shown over whatever is beneath, because a link tapped in a message is a
    // question that has to be answered before anything else happens.
    val pendingLink by viewModel.pendingLink.collectAsStateWithLifecycle()
    val linkContext = LocalContext.current
    pendingLink?.let { url ->
        ConfirmDialog(
            title = "Open this link?",
            // The whole URL, not the text that was tapped. In mail those are
            // different strings more often than anywhere else, and the gap between
            // them is the entire trick.
            body = url,
            confirmLabel = "Open in browser",
            onConfirm = {
                viewModel.dismissLink()
                linkContext.startActivity(viewModel.linkIntent(url))
            },
            onDismiss = viewModel::dismissLink,
        )
    }

    if (openThread != null) {
        BackHandler { viewModel.closeEmailThread() }
        ThreadReader(
            notes = openThread.orEmpty(),
            loading = threadLoading,
            error = threadError,
            onClose = viewModel::closeEmailThread,
            onReply = { note, all ->
                viewModel.startMail(replyDraft(note, all))
            },
            onForward = { note -> viewModel.startMail(forwardDraft(note)) },
            shownImages = shownImages,
            onShowImages = viewModel::showRemoteImages,
            onLink = viewModel::openLink,
            onFlag = { action -> viewModel.flagOpenThread(action) },
            onTrash = viewModel::trashOpenThread,
            onDownload = viewModel::downloadAttachment,
            downloadingId = downloadingAttachment,
            canRestore = openFolder != null,
            modifier = modifier,
        )
        return
    }

    InboxList(
        threads = mailResults ?: threads,
        searching = mailSearching,
        // Null means the inbox is showing, which is a different sentence from a
        // search that matched nothing.
        isResults = mailResults != null,
        query = mailQuery,
        onQueryChange = viewModel::searchMail,
        loading = loading,
        configured = configured,
        error = emailError,
        onOpen = viewModel::openEmailThread,
        onCompose = { viewModel.startMail(MailDraft()) },
        onSetUnread = viewModel::setThreadUnread,
        onSetStarred = viewModel::setThreadStarred,
        folders = folders,
        openFolder = openFolder,
        onOpenFolder = viewModel::openMailFolder,
        onSwipe = { thread, action ->
            when (action) {
                MailSwipeAction.ARCHIVE -> viewModel.archiveThreadFromList(thread)
                MailSwipeAction.DELETE -> viewModel.deleteThreadFromList(thread)
                MailSwipeAction.NOTHING -> Unit
            }
        },
        swipeRight = swipeRight,
        swipeLeft = swipeLeft,
        modifier = modifier,
    )
}

@Composable
internal fun InboxList(
    threads: List<EmailThread>,
    loading: Boolean,
    configured: Boolean?,
    /** Why the mailbox could not be read. Outranks every other empty state below. */
    error: String? = null,
    onOpen: (EmailThread) -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = System.currentTimeMillis(),
    /** Start a new message. Null leaves the inbox read-only, as it was. */
    onCompose: (() -> Unit)? = null,
    /** Mark a thread read or unread from its dot. */
    onSetUnread: ((EmailThread, Boolean) -> Unit)? = null,
    /** Star a thread from the list. */
    onSetStarred: ((EmailThread, Boolean) -> Unit)? = null,
    /** What is being searched for, and how to change it. Null hides the field. */
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
    searching: Boolean = false,
    /** True when the list is search results rather than the inbox. */
    isResults: Boolean = false,
    /** Every mailbox on the account, for switching between them. */
    folders: List<MailFolder> = emptyList(),
    /** Which one is showing, or null for the inbox. */
    openFolder: MailFolder? = null,
    onOpenFolder: ((MailFolder?) -> Unit)? = null,
    /** What a swipe asked for. Null leaves the rows fixed. */
    onSwipe: ((EmailThread, MailSwipeAction) -> Unit)? = null,
    /** Which act each direction performs, as chosen in Settings. */
    swipeRight: MailSwipeAction = MailSwipeAction.DELETE,
    swipeLeft: MailSwipeAction = MailSwipeAction.ARCHIVE,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    ScreenScaffold(
        title = if (isResults) "Search" else openFolder?.label ?: "Inbox",
        modifier = modifier,
        beneath = if (onQueryChange == null) null else {
            {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "Search mail",
                    modifier = Modifier.padding(top = Spacing.x2),
                )

                // The folders, as a row that scrolls sideways. A phone has no
                // room for the sidebar the computer uses, and a dropdown hides
                // the one thing somebody opened this to change.
                if (onOpenFolder != null && folders.size > 1) {
                    FolderStrip(
                        folders = folders,
                        open = openFolder,
                        onOpen = onOpenFolder,
                        modifier = Modifier.padding(top = Spacing.x2),
                    )
                }
            }
        },
        // In the header rather than as a button floating over the list. The inbox
        // is read far more often than it is written to, and a control covering the
        // newest row sits on top of what people opened this for.
        trailing = onCompose?.let { { SecondaryButton(label = "Write", onClick = it) } },
    ) { topInset ->
        val emptyModifier = Modifier.padding(top = topInset)

        when {
            // First, because every state under this one is a statement about the
            // mailbox, and none of them can be made when the mailbox was not reached.
            // This used to fall through to "No email account is connected on your
            // computer" — the most confident wrong sentence in the app.
            error != null && threads.isEmpty() -> EmptyState(
                headline = "Could not read your mail",
                detail = error,
                tone = EmptyTone.PROBLEM,
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            // A search that matched nothing is not an empty mailbox, and it is
            // certainly not a missing account -- which is what the branches below
            // would otherwise say to somebody who mistyped a word. First, so it
            // wins over all of them.
            isResults && threads.isEmpty() && !searching -> EmptyState(
                headline = "Nothing matched",
                detail = "No message in this mailbox contains “$query”.",
                icon = AnodexIcon.SEARCH,
                modifier = emptyModifier,
            )

            (loading || searching) && threads.isEmpty() -> ListSkeleton(
                rows = 5,
                lines = 2,
                caption = if (searching) "Searching…" else "Reading your mail…",
                modifier = Modifier.padding(listPadding(topInset)),
            )

            // Not yet asked — the socket was down when this tab opened. Saying
            // "nothing in the inbox" here would be a claim the app has no basis for,
            // and the user would believe it.
            configured == null -> EmptyState(
                headline = "Waiting for your computer…",
                detail = "The mailbox has not been reached yet, so there is nothing to say about it.",
                tone = EmptyTone.WAITING,
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            // Told apart on purpose: an inbox with nothing in it and an inbox that
            // does not exist look identical in a list and need opposite words.
            configured == false -> EmptyState(
                headline = "No mail account connected",
                detail = "Connect one on your computer and it will show up here.",
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            threads.isEmpty() -> EmptyState(
                headline = "Nothing in the inbox",
                detail = "Read at the computer, never stored on the phone.",
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(topInset, 0.dp),
                contentPadding = listPadding(topInset, horizontal = Spacing.x4),
                verticalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                // A mailbox that failed to refresh while it still had threads to show
                // could say nothing at all before: the error state required an empty
                // list. Stale mail presented as current is the version of this bug
                // that actually costs somebody something.
                if (error != null) {
                    item(key = "read-failed") { InlineProblem(error) }
                }

                items(threads, key = { it.id }) { thread ->
                    SwipeableThreadRow(
                        thread = thread,
                        nowEpochMs = nowEpochMs,
                        onClick = { onOpen(thread) },
                        onSetUnread = onSetUnread,
                        onSetStarred = onSetStarred,
                        onSwipe = onSwipe,
                        swipeRight = swipeRight,
                        swipeLeft = swipeLeft,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * A row you can push out of the inbox with a thumb.
 *
 * The gesture every mail client on a phone has, and the reason it is worth
 * having is triage: most of an inbox is decided without being read, and a swipe
 * is the only control that costs nothing to reach.
 *
 * Each direction does what the reader chose in Settings — throw away one way,
 * file the other, by default. Both used to archive, which is what Gmail ships
 * and which wastes half the gesture. It is a setting rather than a fixed pair
 * because people disagree about which side means what, with feeling, and muscle
 * memory from another mail app beats any argument made here.
 *
 * Neither act is final. Archiving moves a message out of the inbox and deleting
 * moves it to the trash; both are undone from the strip that follows, and
 * nothing here expunges anything. That is the whole reason a gesture is allowed
 * to do either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableThreadRow(
    thread: EmailThread,
    nowEpochMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSetUnread: ((EmailThread, Boolean) -> Unit)? = null,
    onSetStarred: ((EmailThread, Boolean) -> Unit)? = null,
    onSwipe: ((EmailThread, MailSwipeAction) -> Unit)? = null,
    swipeRight: MailSwipeAction = MailSwipeAction.DELETE,
    swipeLeft: MailSwipeAction = MailSwipeAction.ARCHIVE,
) {
    val settled = swipeRight == MailSwipeAction.NOTHING && swipeLeft == MailSwipeAction.NOTHING
    if (onSwipe == null || settled) {
        ThreadRow(
            thread = thread,
            nowEpochMs = nowEpochMs,
            onClick = onClick,
            onSetUnread = onSetUnread,
            onSetStarred = onSetStarred,
            modifier = modifier,
        )
        return
    }

    val state = rememberSwipeToDismissBoxState(
        // The row leaves on the caller's say-so, not the gesture's: the list is
        // what removes it, and letting the box settle into a dismissed state as
        // well would leave a second copy of that decision to disagree.
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> swipeRight
                SwipeToDismissBoxValue.EndToStart -> swipeLeft
                SwipeToDismissBoxValue.Settled -> MailSwipeAction.NOTHING
            }
            if (action != MailSwipeAction.NOTHING) onSwipe(thread, action)
            false
        },
        // Most of the width, because a thumb travelling that far is not an
        // accident. The default third is easy to cross while scrolling.
        positionalThreshold = { width -> width * 0.5f },
    )

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = swipeRight != MailSwipeAction.NOTHING,
        enableDismissFromEndToStart = swipeLeft != MailSwipeAction.NOTHING,
        backgroundContent = {
            SwipeBackdrop(
                action = when (state.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> swipeRight
                    SwipeToDismissBoxValue.EndToStart -> swipeLeft
                    SwipeToDismissBoxValue.Settled -> null
                },
                fromStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd,
                // How far along the gesture is, against the point it commits at.
                // Past 1 the field is at full strength, which is the only signal
                // saying "let go now and it happens".
                progress = (state.progress / 0.5f).coerceIn(0f, 1f),
            )
        },
    ) {
        ThreadRow(
            thread = thread,
            nowEpochMs = nowEpochMs,
            onClick = onClick,
            onSetUnread = onSetUnread,
            onSetStarred = onSetStarred,
        )
    }
}

/**
 * What is revealed behind a row as it moves.
 *
 * The old one was a flat grey panel with the same icon at both ends, which said
 * nothing: not which act, not which direction, not whether letting go would do
 * it. This is the one place in the app where the interface has to answer a
 * question mid-gesture, and it had been answering none of them.
 *
 * So it is built out of the marque's own ramp. The field is the act's colour
 * over the page, deepening as the thumb travels — cyan-blue for filing,
 * the danger red for throwing away — and it arrives as a gradient running out
 * from the edge the finger came from, so the direction is legible without
 * reading the label. At the commit point the icon takes the full colour and
 * grows, which is the moment worth marking: before it, letting go does nothing.
 */
@Composable
private fun SwipeBackdrop(action: MailSwipeAction?, fromStart: Boolean, progress: Float) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    if (action == null || action == MailSwipeAction.NOTHING) return

    val committing = progress >= 1f
    val tint = if (action == MailSwipeAction.DELETE) colors.danger else colors.accent
    val ink = if (action == MailSwipeAction.DELETE) colors.dangerInk else colors.accentInk
    val icon = if (action == MailSwipeAction.DELETE) AnodexIcon.TRASH else AnodexIcon.ARCHIVE

    // Eased rather than linear: the colour should be barely there for the first
    // few millimetres, so a scroll that drifts sideways does not flash red.
    val depth = (progress * progress * 0.32f)
    val edge = tint.copy(alpha = depth)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(Radii.lg)
            .background(colors.bgSurface2)
            .background(
                Brush.horizontalGradient(
                    // Strongest at the edge the thumb came from, fading across —
                    // the same direction as the movement, so the field reads as
                    // something being pushed rather than a panel switching on.
                    colors = if (fromStart) {
                        listOf(edge, edge.copy(alpha = depth * 0.25f), Color.Transparent)
                    } else {
                        listOf(Color.Transparent, edge.copy(alpha = depth * 0.25f), edge)
                    },
                ),
            )
            .padding(horizontal = Spacing.x5),
        contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AnodexIcon(
                icon = icon,
                size = if (committing) 24.dp else 20.dp,
                tint = if (committing) ink else colors.textMuted,
            )
            // The word appears only once letting go would do something. Before
            // that it would be a label for an act that is not going to happen.
            if (committing) {
                Text(text = action.label, style = type.label, color = ink)
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: EmailThread,
    nowEpochMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Toggle read state. Null leaves the dot as an indicator only. */
    onSetUnread: ((EmailThread, Boolean) -> Unit)? = null,
    /** Toggle the star. Null hides it. */
    onSetStarred: ((EmailThread, Boolean) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // A card per message rather than rows separated by rules.
    //
    // The list used to be text on one flat plane, which reads as a document.
    // Every mail client on a phone draws a card, and the reason is the thumb: a
    // card says where one message ends and the next begins without a hairline
    // that disappears at arm's length, and it gives the tap a shape.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .background(if (thread.unread) colors.bgSurface2 else colors.bgSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        SenderAvatar(from = thread.from)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text(
                    text = senderName(thread.from),
                    style = type.body.copy(
                        fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = relativeTime(thread.updatedAtEpochMs, nowEpochMs),
                    style = type.meta,
                    color = if (thread.unread) colors.text else colors.textFaint,
                    maxLines = 1,
                )

                // The dot moved to the right, beside the time.
                //
                // On the left it was the first thing in the row and competed with
                // the avatar for the same job. Beside the time it sits with the
                // other thing that changes per message and leaves the left edge to
                // say who it is from. Still the control for read state, and still
                // with a finger's worth of target around six pixels of dot.
                Box(
                    modifier = Modifier
                        .size(Touch.minTarget / 2)
                        .clip(CircleShape)
                        .let { base ->
                            if (onSetUnread == null) base
                            else base.clickable { onSetUnread(thread, !thread.unread) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (thread.unread) colors.accent else Color.Transparent)
                    )
                }
            }

            Text(
                text = thread.subject,
                style = type.body.copy(
                    fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text(
                    text = thread.snippet,
                    style = type.meta,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (thread.attachmentCount > 0) {
                    AnodexIcon(AnodexIcon.PAPERCLIP, size = 14.dp, tint = colors.textFaint)
                }

                // Starring from the list, which is where somebody decides a
                // message matters -- after reading the subject and before opening
                // it. Filled and tinted when set, outline and faint when not, so
                // the state is legible without reading a label.
                if (onSetStarred != null) {
                    Text(
                        text = if (thread.starred) "★" else "☆",
                        style = type.body,
                        color = if (thread.starred) colors.warn else colors.textFaint,
                        modifier = Modifier
                            .size(Touch.minTarget / 2)
                            .wrapContentSize(Alignment.Center)
                            .clip(CircleShape)
                            .clickable { onSetStarred(thread, !thread.starred) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThreadReader(
    notes: List<EmailNote>,
    loading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Why there is nothing to read. Null when the thread simply has not arrived. */
    error: String? = null,
    /** Answer it. The boolean is reply-all. */
    onReply: ((EmailNote, Boolean) -> Unit)? = null,
    onForward: ((EmailNote) -> Unit)? = null,
    /** Remote images the reader has asked for, by message id then by original URL. */
    shownImages: Map<String, Map<String, String>> = emptyMap(),
    onShowImages: ((EmailNote, List<String>) -> Unit)? = null,
    onLink: ((String) -> Unit)? = null,
    /** Mark, star or archive this thread. Null hides the row. */
    onFlag: ((MailFlag) -> Unit)? = null,
    /** Move it to the computer's trash. Null hides the bin. */
    onTrash: (() -> Unit)? = null,
    /** Download an attachment onto this phone. Null leaves them listed only. */
    onDownload: ((EmailAttachment) -> Unit)? = null,
    /** Which attachment is being fetched, so two do not start at once. */
    downloadingId: String? = null,
    /**
     * True when this thread is being read somewhere other than the inbox, so the
     * action worth offering is putting it back rather than taking it away.
     */
    canRestore: Boolean = false,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Armed per thread, so opening a different message does not inherit a
    // half-pressed delete from the last one.
    var confirmingTrash by remember(notes.firstOrNull()?.threadId) { mutableStateOf(false) }

    ScreenScaffold(
        // The chrome says what you can do; the subject has moved into the message
        // itself, below, where every other mail client puts it. A subject in a
        // 20-character title bar is a subject nobody can read, and it was being
        // truncated on half the mail in this inbox.
        title = if (notes.size > 1) "${notes.size} messages" else "Message",
        modifier = modifier,
        leading = { SecondaryButton(label = "Back", onClick = onClose) },
        trailing = if (onFlag == null) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    // Archive and mark-unread, as icons, where the thumb reaches on
                    // the way out of a message. No bin: mail is archived here and on
                    // the computer, never deleted, and a bin beside an archive box
                    // would be two buttons that look alike and differ in whether
                    // anything can be undone.
                    // Archiving something already out of the inbox does nothing,
                    // and the thing that is wanted there is the opposite act. So
                    // the same position carries whichever of the two is
                    // available, rather than a fourth icon on a phone header or
                    // a button that quietly no-ops.
                    if (canRestore) {
                        HeaderAction(AnodexIcon.INBOX, "Move to inbox") {
                            onFlag(MailFlag.UNARCHIVE)
                        }
                    } else {
                        HeaderAction(AnodexIcon.ARCHIVE, "Archive") { onFlag(MailFlag.ARCHIVE) }
                    }
                    HeaderAction(AnodexIcon.MAIL, "Mark unread") { onFlag(MailFlag.UNREAD) }
                    // Delete last, furthest from the two that are undone by
                    // pressing them again. It asks once: the message is
                    // recoverable, but only from the trash on the computer, and
                    // "where did that go" is a worse afternoon than one more tap.
                    if (onTrash != null) {
                        HeaderAction(
                            icon = AnodexIcon.TRASH,
                            label = if (confirmingTrash) "Tap again to delete" else "Delete",
                            tint = if (confirmingTrash) colors.dangerInk else colors.textMuted,
                        ) {
                            if (confirmingTrash) onTrash() else confirmingTrash = true
                        }
                    }
                }
            }
        },
    ) { topInset ->
        if (loading && notes.isEmpty()) {
            EmptyState(
                headline = "Opening…",
                tone = EmptyTone.WAITING,
                icon = AnodexIcon.MAIL,
                modifier = Modifier.padding(top = topInset),
            )
            return@ScreenScaffold
        }

        // A thread with nothing in it drew nothing at all: a header bar, a back
        // button, and a screen of black under it. Two of the five conversations
        // in a real inbox did this, and the app's silence was the reason it went
        // unreported -- there was nothing to report but "it doesn't work".
        if (notes.isEmpty()) {
            EmptyState(
                headline = "This conversation would not open",
                detail = error
                    ?: "Your computer returned no messages for it.",
                tone = EmptyTone.PROBLEM,
                icon = AnodexIcon.MAIL,
                modifier = Modifier.padding(top = topInset),
            )
            return@ScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topInset, 0.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Spacing.x4,
                    end = Spacing.x4,
                    top = topInset + Spacing.x2,
                    bottom = Spacing.x6,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.x5),
        ) {
            // The subject, full width and wrapping, above the first message.
            // This is what a mail client leads with and what the header bar could
            // not hold: three lines of it here beats twenty characters up there.
            notes.firstOrNull()?.let { first ->
                Text(
                    text = first.subject.ifBlank { "No subject" },
                    style = type.heading,
                    color = colors.text,
                )
            }

            for (note in notes) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    // Who it is from, as a person: circle, name, when. The same
                    // three things the inbox row shows, so opening a message does
                    // not change what it is identified by.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
                    ) {
                        SenderAvatar(from = note.from, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = senderName(note.from),
                                style = type.bodyEmphasis,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Who else received it. "to me" is the ordinary case and
                            // worth saying, because the case it distinguishes -- a
                            // message that went to nine other people -- changes how
                            // somebody answers it.
                            Text(
                                text = recipientLine(note.to, note.cc),
                                style = type.meta,
                                color = colors.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = relativeTime(note.dateEpochMs, System.currentTimeMillis()),
                            style = type.meta,
                            color = colors.textFaint,
                        )
                    }
                    // Offered, not just counted. This used to say "2 attachments"
                    // and do nothing about them, which is a label rather than a
                    // feature -- the reader could see a file existed and had no way
                    // to reach it without walking to the computer.
                    for (attachment in note.attachments) {
                        val busy = downloadingId == attachment.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(Radii.md)
                                .background(colors.bgSurface)
                                .let { base ->
                                    if (onDownload == null || downloadingId != null) base
                                    else base.clickable { onDownload(attachment) }
                                }
                                .padding(Spacing.x3),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
                        ) {
                            AnodexIcon(
                                icon = AnodexIcon.PAPERCLIP,
                                size = 18.dp,
                                tint = colors.textMuted,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = attachment.filename,
                                    style = type.body,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // The size, because over mobile data it is the
                                    // whole of the decision.
                                    text = if (busy) "Downloading…" else fileSize(attachment.size),
                                    style = type.meta,
                                    color = colors.textFaint,
                                )
                            }
                            if (onDownload != null && !busy) {
                                Text("Save", style = type.label, color = colors.accentInk)
                            }
                        }
                    }
                    // The message as written, when the desktop sent one. `body` is
                    // the plain-text fallback and is what a message with no HTML
                    // part has always been.
                    val html = note.bodyHtml
                    if (html != null) {
                        val held = remoteImageCount(html)
                        val shown = shownImages[note.id].orEmpty()

                        MailBody(
                            html = html,
                            images = shown,
                            onLink = onLink,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Offered, never automatic. Fetching a remote image is how a
                        // sender learns the message was opened, when it was, and
                        // roughly from where -- so it is the reader's call, and the
                        // count is shown because "3 images" and "60 images" are
                        // different decisions.
                        if (held > 0 && shown.isEmpty() && onShowImages != null) {
                            SecondaryButton(
                                label = if (held == 1) "Show 1 image" else "Show $held images",
                                onClick = { onShowImages(note, remoteImageUrls(html)) },
                            )
                            Text(
                                text = "Loading them tells the sender you opened this.",
                                style = type.meta,
                                color = colors.textFaint,
                            )
                        }
                    } else {
                        Text(text = note.body, style = type.body, color = colors.textMuted)
                    }
                }
            }

            // Under the last message rather than floating over it. The actions
            // belong to the thread you have just finished reading, and a button
            // hovering above the text is one more thing covering the words.
            val last = notes.lastOrNull()
            if (last != null && (onReply != null || onForward != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                ) {
                    if (onReply != null) {
                        PrimaryButton(
                            label = "Reply",
                            onClick = { onReply(last, false) },
                            modifier = Modifier.weight(1f),
                        )
                        // Only where it means something. On a message with one
                        // recipient, reply-all and reply are the same act under two
                        // names, and offering both invites the wrong one.
                        if (last.to.size + last.cc.size > 1) {
                            SecondaryButton(
                                label = "Reply all",
                                onClick = { onReply(last, true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (onForward != null) {
                        SecondaryButton(
                            label = "Forward",
                            onClick = { onForward(last) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Tidying, on its own row and quieter than answering.
                //
                // Every one of these is undone by another one of them. The
                // desktop's `EmailFlagAction` is read/unread, star/unstar and
                // archive/unarchive, and its own comment says deleting mail is
                // deliberately absent -- which is the property that makes these
                // safe to put a finger's width apart on a phone.
                if (onFlag != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.x1),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                    ) {
                        for (action in listOf(MailFlag.STAR, MailFlag.ARCHIVE, MailFlag.UNREAD)) {
                            Text(
                                text = when (action) {
                                    MailFlag.STAR -> "Star"
                                    MailFlag.ARCHIVE -> "Archive"
                                    else -> "Mark unread"
                                },
                                style = type.label,
                                color = colors.textMuted,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = Touch.minTarget)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .clip(Radii.md)
                                    .clickable { onFlag(action) }
                                    .padding(vertical = Spacing.x2),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A reply, with the threading headers and the right people on it.
 *
 * Reply-all keeps everyone the message was addressed to, minus the sender, who is
 * already in To. Leaving them in puts somebody in both fields and some clients
 * then deliver it twice.
 */
private fun replyDraft(note: EmailNote, all: Boolean): MailDraft =
    MailDraft(
        to = note.from,
        cc = if (all) {
            (note.to + note.cc).filterNot { it.contains(addressOf(note.from), ignoreCase = true) }
                .joinToString(", ")
        } else {
            ""
        },
        subject = replySubject(note.subject),
        inReplyTo = note,
        // The message's own thread, read off the message. This briefly used the
        // message id instead, which is a different identifier of the same shape --
        // the far end would have filed every reply as a new conversation and
        // nothing on this phone would have looked wrong.
        threadId = note.threadId,
        kind = if (all) "Reply all" else "Reply",
    )

private fun forwardDraft(note: EmailNote): MailDraft =
    MailDraft(
        subject = forwardSubject(note.subject),
        body = forwardBody(note),
        // No `inReplyTo`: a forward starts a new conversation with somebody who was
        // not in the old one, and threading it onto the original would file it under
        // a subject they have never seen.
        kind = "Forward",
    )

/** The bare address out of `Ada Lovelace <ada@example.com>`. */
private fun addressOf(from: String): String =
    from.substringAfter('<').substringBefore('>').ifBlank { from }.trim()




/**
 * The account's mailboxes, as a row of chips.
 *
 * Sideways rather than a menu: the computer has a sidebar and a phone does not,
 * and a dropdown hides the thing somebody opened it to change. Inbox is first
 * and always present, because it is where this screen starts and the server does
 * not always list it the way it lists the others.
 */
@Composable
private fun FolderStrip(
    folders: List<MailFolder>,
    open: MailFolder?,
    onOpen: (MailFolder?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        // The inbox chip is this app's own, not a row from the server's list:
        // `openMailFolder(null)` is what tells the computer "your default", and
        // sending a name instead would be this phone deciding what INBOX is
        // called.
        FolderChip("Inbox", selected = open == null) { onOpen(null) }

        for (folder in folders) {
            if (folder.label.equals("inbox", ignoreCase = true)) continue
            FolderChip(folder.label, selected = open?.name == folder.name) { onOpen(folder) }
        }
    }
}

@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    Text(
        text = label,
        style = AnodexTheme.type.label,
        color = if (selected) colors.accentInk else colors.textMuted,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = Touch.minTarget)
            .wrapContentHeight(Alignment.CenterVertically)
            .clip(Radii.lg)
            .background(if (selected) colors.accentSoft else colors.bgSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x1),
    )
}

/**
 * An icon in the header bar, with a finger's worth of target around it.
 *
 * The actions a mail client keeps in the chrome: reachable on the way out of a
 * message, and small enough that two of them do not become the loudest thing on
 * the screen.
 */
@Composable
private fun HeaderAction(
    icon: AnodexIcon,
    label: String,
    tint: Color = AnodexTheme.colors.textMuted,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Touch.minTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnodexIcon(icon, size = 20.dp, tint = tint, contentDescription = label)
    }
}

/**
 * "to me", or who else was on it.
 *
 * The distinction worth drawing is between a message addressed to one person and
 * one addressed to a room, because it changes whether a reply should go to
 * everybody. Counted rather than listed: nine addresses do not fit on a phone
 * row and nobody reads them there anyway.
 */
internal fun recipientLine(to: List<String>, cc: List<String>): String {
    val others = to.size + cc.size
    return when {
        others <= 1 -> "to me"
        else -> "to me and ${others - 1} ${if (others == 2) "other" else "others"}"
    }
}

/**
 * "Ada Lovelace" out of `Ada Lovelace <ada@example.com>`.
 *
 * The display name is what a person recognises; the address is 40 characters of
 * noise that pushes everything else off a phone-width row. The address survives
 * when there is no name, because then it is all there is.
 */
internal fun senderName(from: String): String {
    val angle = from.indexOf('<')
    val name = if (angle > 0) from.substring(0, angle).trim().trim('"') else ""
    return name.ifBlank { from.substringAfter('<').substringBefore('>').ifBlank { from } }
}

/** Coarse on purpose: "2h" is what the reader wants, not a timestamp to the second. */
internal fun relativeTime(epochMs: Long, nowEpochMs: Long): String {
    if (epochMs <= 0) return ""
    val elapsed = (nowEpochMs - epochMs).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)

    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> "${days / 7}w"
    }
}

@Preview(name = "Inbox", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewInbox() {
    val now = 1_700_000_000_000L
    AnodexTheme(darkTheme = true) {
        InboxList(
            threads = listOf(
                EmailThread(
                    id = "1",
                    accountId = "a",
                    subject = "Re: collision LOD pass",
                    from = "Ada Lovelace <ada@example.com>",
                    snippet = "That looks right to me — ship it once the tests are green.",
                    updatedAtEpochMs = now - 20 * 60 * 1000,
                    unread = true,
                    starred = false,
                    messageCount = 4,
                    attachmentCount = 0,
                ),
                EmailThread(
                    id = "2",
                    accountId = "a",
                    subject = "Invoice 4471",
                    from = "billing@example.com",
                    snippet = "Your monthly statement is ready.",
                    updatedAtEpochMs = now - 3L * 24 * 60 * 60 * 1000,
                    unread = false,
                    starred = false,
                    messageCount = 1,
                    attachmentCount = 1,
                ),
            ),
            loading = false,
            configured = true,
            onOpen = {},
            nowEpochMs = now,
        )
    }
}

@Preview(name = "Inbox - not asked yet", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewInboxUnknown() {
    // The state that used to read "Nothing in the inbox" without having looked.
    AnodexTheme(darkTheme = true) {
        InboxList(threads = emptyList(), loading = false, configured = null, onOpen = {})
    }
}

@Preview(name = "Inbox - no account", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewInboxUnconfigured() {
    AnodexTheme(darkTheme = true) {
        InboxList(threads = emptyList(), loading = false, configured = false, onOpen = {})
    }
}

/**
 * "2.4 MB", the way a file manager says it.
 *
 * Rounded rather than exact: the reader is deciding whether to spend the data,
 * and no such decision turns on the difference between 2,411,724 bytes and 2.4
 * megabytes.
 */
internal fun fileSize(bytes: Long): String = when {
    bytes <= 0 -> "Unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
