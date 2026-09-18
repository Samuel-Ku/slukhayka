package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.social.Audience
import com.slukhayka.audiobooks.data.social.BlockState
import com.slukhayka.audiobooks.data.social.FriendsFeedPolicy
import com.slukhayka.audiobooks.ui.components.AppSettingsGear
import com.slukhayka.audiobooks.ui.components.AppTabHeader
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.theme.AppDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #898 — one row of the «Друзі» feed, shaped for display.
 *
 * The author is a **pseudonym**, never a uid (spec §5): the same hashed
 * identifier the curatorial collections already publish under. The book is
 * pointed at by [sourceId] and never copied — a post is not a review and does
 * not duplicate its fields.
 *
 * This is deliberately NOT `SocialModel.Post` (#897): that type owns the stored
 * shape, while the feed additionally needs the post's [audience] and the instant
 * it was [postedAtMs]. The field names are kept identical to `Post` so the
 * mapping is mechanical once #897 lands; nothing here is persisted.
 */
data class FriendsFeedRow(
    val id: String,
    val authorPseudonym: String,
    val audience: Audience,
    val text: String,
    /** The record this post shares (a review, a published collection), if any. */
    val sourceId: String? = null,
    /** When it was posted; `0` (unknown) renders no time rather than a fake one. */
    val postedAtMs: Long = 0L
)

/**
 * Everything the «Друзі» screen needs to render **honestly**.
 *
 * [friendsNow] is the accepted-friends set *at the moment of display* (§3), and
 * [blocks] carries both directions of the block (§2). The screen feeds both into
 * [FriendsFeedPolicy], so a post the listener may not see is never rendered —
 * and a feed emptied by those rules stays visibly empty instead of being padded
 * (§6.4).
 */
data class FriendsFeedState(
    val friendsNow: Set<String> = emptySet(),
    val posts: List<FriendsFeedRow> = emptyList(),
    val blocks: BlockState = BlockState(),
    /** `sourceId` → the book's title. An unresolved id renders no book line. */
    val bookTitles: Map<String, String> = emptyMap()
)

/**
 * #898 — the root «Друзі» section: the friends feed, and nothing else yet.
 *
 * First-version scope (the owner's decision on #898): posts whose audience is
 * **Друзі**. Reactions and comments are a *new social layer* (§7), not a current
 * contract, so no placeholder for them is drawn — a dead affordance would be
 * worse than an absent one. A reader's profile is a separate entity from «Люди»
 * (§6.3) and is not mixed in here either.
 *
 * The empty state distinguishes the two genuinely different situations — no
 * friends at all, versus friends who have not posted — and invents neither
 * acquaintances nor posts (§6.4). The direction doc's intended first actions
 * (find a reader by name, share one's own profile) need a data source this
 * version does not have, so they are described in the copy rather than offered
 * as buttons that would do nothing.
 */
@Composable
fun FriendsScreen(
    feed: FriendsFeedState,
    viewerPseudonym: String,
    onOpenBook: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The audience/block/friendship rule is applied HERE, at the moment of
    // display — so the empty state below describes what the listener can
    // actually see, not what happens to be stored.
    val visiblePosts = FriendsFeedPolicy.visibleFeed(
        posts = feed.posts,
        viewer = viewerPseudonym,
        friendsNow = feed.friendsNow,
        blocks = feed.blocks,
        authorOf = { it.authorPseudonym },
        audienceOf = { it.audience }
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("friends_screen")
            .accessibilityPane(stringResource(R.string.a11y_friends_pane)),
        contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer)
    ) {
        item {
            AppTabHeader(
                // The root's name is the ONE resource the bottom bar also uses
                // (spec-54 T06 / #873): a literal here could drift from the tab.
                title = stringResource(R.string.nav_friends),
                headingTestTag = "friends_heading",
                actions = { AppSettingsGear(onClick = onOpenSettings) }
            )
        }

        when {
            // Visible content wins over any empty state: if the listener can
            // actually see a post (their own, or a public one), the feed is not
            // empty and must not claim to be.
            visiblePosts.isNotEmpty() -> items(visiblePosts, key = { it.id }) { post ->
                FriendsPostCard(
                    post = post,
                    bookTitle = post.sourceId?.let { feed.bookTitles[it] },
                    onOpenBook = onOpenBook
                )
            }

            // §6.4 — no friends is a different fact from no posts, and the two
            // states must not be merged into one vague "nothing here".
            feed.friendsNow.isEmpty() -> item {
                EmptyState(
                    icon = Icons.Default.Group,
                    title = stringResource(R.string.friends_empty_no_friends_title),
                    body = stringResource(R.string.friends_empty_no_friends_body),
                    modifier = Modifier.testTag("friends_empty_no_friends")
                )
            }

            else -> item {
                EmptyState(
                    icon = Icons.Default.Group,
                    title = stringResource(R.string.friends_empty_no_posts_title),
                    body = stringResource(R.string.friends_empty_no_posts_body),
                    modifier = Modifier.testTag("friends_empty_no_posts")
                )
            }
        }
    }
}

/**
 * One post: the author's pseudonym, the text, the book it shares and the time.
 *
 * When the book resolves, the whole card is the tap target — the direction doc
 * makes touching the book open that Work's page, and one target is friendlier
 * to TalkBack and to a thumb than a small chip. An unresolved book renders no
 * book line at all (ADR-0014: no filler) and leaves the card inert.
 */
@Composable
private fun FriendsPostCard(
    post: FriendsFeedRow,
    bookTitle: String?,
    onOpenBook: (String) -> Unit
) {
    val time = formatFriendsPostTime(post.postedAtMs)
    val sourceId = post.sourceId
    val openable = sourceId != null && bookTitle != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppDimens.SpaceLg, vertical = 6.dp)
            .testTag("friends_post_${post.id}")
            .then(
                if (openable) {
                    Modifier
                        .semantics(mergeDescendants = true) {}
                        .clickable(
                            onClickLabel = stringResource(R.string.a11y_library_open_book, bookTitle!!)
                        ) { onOpenBook(sourceId!!) }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = post.authorPseudonym,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("friends_post_author_${post.id}")
            )

            if (post.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppDimens.SpaceXs))
                Text(
                    text = post.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (bookTitle != null) {
                Spacer(modifier = Modifier.height(AppDimens.SpaceSm))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag("friends_post_book_${post.id}")
                ) {
                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (time.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppDimens.SpaceXs))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The post's time, in the device's locale. Deliberately absolute and derived
 * from one stored instant: a "5 minutes ago" label needs a ticking clock and a
 * refresh trigger the feed does not have, and a stale relative label lies.
 * An unknown instant renders nothing.
 */
internal fun formatFriendsPostTime(postedAtMs: Long): String =
    if (postedAtMs <= 0L) {
        ""
    } else {
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(postedAtMs))
    }
