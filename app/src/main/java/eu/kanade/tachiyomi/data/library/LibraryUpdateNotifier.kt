package eu.kanade.tachiyomi.data.library

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Parameters
import coil.transform.CircleCropTransformation
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.image.coil.MangaFetcher
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.notification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.internal.toImmutableMap
import uy.kohesive.injekt.injectLazy
import java.util.ArrayList

class LibraryUpdateNotifier(private val context: Context) {

    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Pending intent of action that cancels the library update
     */
    private val cancelIntent by lazy {
        NotificationReceiver.cancelLibraryUpdatePendingBroadcast(context)
    }

    /**
     * Bitmap of the app for notifications.
     */
    private val notificationBitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    /**
     * Cached progress notification to avoid creating a lot.
     */
    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY) {
            setContentTitle(context.getString(R.string.app_name))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setLargeIcon(notificationBitmap)
            setOngoing(true)
            setOnlyAlertOnce(true)
            color = ContextCompat.getColor(context, R.color.colorAccent)
            addAction(R.drawable.ic_close_24dp, context.getString(android.R.string.cancel), cancelIntent)
        }
    }

    /**
     * Shows the notification containing the currently updating manga and the progress.
     *
     * @param manga the manga that's being updated.
     * @param current the current progress.
     * @param total the total progress.
     */
    fun showProgressNotification(manga: Manga, current: Int, total: Int) {
        val title = if (preferences.hideNotificationContent()) {
            context.getString(R.string.checking_for_new_chapters)
        } else {
            manga.title
        }

        context.notificationManager.notify(
            Notifications.ID_LIBRARY_PROGRESS,
            progressNotificationBuilder
                .setContentTitle(title)
                .setProgress(total, current, false)
                .build()
        )
    }

    /**
     * Shows notification containing update entries that failed with action to open full log.
     *
     * @param errors List of entry titles that failed to update.
     * @param uri Uri for error log file containing all titles that failed.
     */
    fun showUpdateErrorNotification(errors: List<String>, uri: Uri) {
        if (errors.isEmpty()) {
            return
        }

        context.notificationManager.notify(
            Notifications.ID_LIBRARY_ERROR,
            context.notificationBuilder(Notifications.CHANNEL_LIBRARY) {
                setContentTitle(context.resources.getQuantityString(R.plurals.notification_update_failed, errors.size, errors.size))
                setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        errors.joinToString("\n") {
                            it.chop(TITLE_MAX_LEN)
                        }
                    )
                )
                setSmallIcon(R.drawable.ic_tachi)
                addAction(
                    R.drawable.nnf_ic_file_folder,
                    context.getString(R.string.view_all_errors),
                    NotificationReceiver.openErrorLogPendingActivity(context, uri)
                )
            }
                .build()
        )
    }

    /**
     * Shows the notification containing the result of the update done by the service.
     *
     * @param updates a list of manga with new updates.
     */
    fun showResultNotification(newUpdates: Map<LibraryManga, Array<Chapter>>) {
        // create a copy of the list since it will be cleared by the time it is used
        val updates = newUpdates.toImmutableMap()
        GlobalScope.launch {
            val notifications = ArrayList<Pair<Notification, Int>>()
            if (!preferences.hideNotificationContent()) {
                updates.forEach {
                    val manga = it.key
                    val chapters = it.value
                    val chapterNames = chapters.map { chapter -> chapter.name }
                    notifications.add(
                        Pair(
                            context.notification(Notifications.CHANNEL_NEW_CHAPTERS) {
                                setSmallIcon(R.drawable.ic_tachi)
                                try {
                                    val request = ImageRequest.Builder(context).data(manga)
                                        .parameters(
                                            Parameters.Builder().set(MangaFetcher.onlyCache, true)
                                                .build()
                                        )
                                        .networkCachePolicy(CachePolicy.READ_ONLY)
                                        .transformations(CircleCropTransformation())
                                        .size(width = ICON_SIZE, height = ICON_SIZE).build()

                                    Coil.imageLoader(context)
                                        .execute(request).drawable?.let { drawable ->
                                            setLargeIcon((drawable as BitmapDrawable).bitmap)
                                        }
                                } catch (e: Exception) {
                                }
                                setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                                setContentTitle(manga.title)
                                color = ContextCompat.getColor(context, R.color.colorAccent)
                                val chaptersNames = if (chapterNames.size > MAX_CHAPTERS) {
                                    "${chapterNames.take(MAX_CHAPTERS - 1).joinToString(", ")}, " +
                                        context.resources.getQuantityString(
                                            R.plurals.notification_and_n_more,
                                            (chapterNames.size - (MAX_CHAPTERS - 1)),
                                            (chapterNames.size - (MAX_CHAPTERS - 1))
                                        )
                                } else chapterNames.joinToString(", ")
                                setContentText(chaptersNames)
                                setStyle(NotificationCompat.BigTextStyle().bigText(chaptersNames))
                                priority = NotificationCompat.PRIORITY_HIGH
                                setGroup(Notifications.GROUP_NEW_CHAPTERS)
                                setContentIntent(
                                    NotificationReceiver.openChapterPendingActivity(
                                        context,
                                        manga,
                                        chapters.first()
                                    )
                                )
                                addAction(
                                    R.drawable.ic_eye_24dp,
                                    context.getString(R.string.mark_as_read),
                                    NotificationReceiver.markAsReadPendingBroadcast(
                                        context,
                                        manga,
                                        chapters,
                                        Notifications.ID_NEW_CHAPTERS
                                    )
                                )
                                addAction(
                                    R.drawable.ic_book_24dp,
                                    context.getString(R.string.view_chapters),
                                    NotificationReceiver.openChapterPendingActivity(
                                        context,
                                        manga,
                                        Notifications.ID_NEW_CHAPTERS
                                    )
                                )
                                setAutoCancel(true)
                            },
                            manga.id.hashCode()
                        )
                    )
                }
            }

            NotificationManagerCompat.from(context).apply {
                notify(
                    Notifications.ID_NEW_CHAPTERS,
                    context.notification(Notifications.CHANNEL_NEW_CHAPTERS) {
                        setSmallIcon(R.drawable.ic_tachi)
                        setLargeIcon(notificationBitmap)
                        setContentTitle(context.getString(R.string.new_chapters_found))
                        color = ContextCompat.getColor(context, R.color.colorAccent)
                        if (updates.size > 1) {
                            setContentText(
                                context.resources.getQuantityString(
                                    R.plurals.for_n_titles,
                                    updates.size,
                                    updates.size
                                )
                            )
                            if (!preferences.hideNotificationContent()) {
                                setStyle(
                                    NotificationCompat.BigTextStyle()
                                        .bigText(
                                            updates.keys.joinToString("\n") {
                                                it.title.chop(45)
                                            }
                                        )
                                )
                            }
                        } else if (!preferences.hideNotificationContent()) {
                            setContentText(updates.keys.first().title.chop(45))
                        }
                        priority = NotificationCompat.PRIORITY_HIGH
                        setGroup(Notifications.GROUP_NEW_CHAPTERS)
                        setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                        setGroupSummary(true)
                        setContentIntent(getNotificationIntent())
                        setAutoCancel(true)
                    }
                )

                notifications.forEach {
                    notify(it.second, it.first)
                }
            }
        }
    }

    /**
     * Cancels the progress notification.
     */
    fun cancelProgressNotification() {
        context.notificationManager.cancel(Notifications.ID_LIBRARY_PROGRESS)
    }

    /**
     * Returns an intent to open the main activity.
     */
    private fun getNotificationIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = MainActivity.SHORTCUT_RECENTLY_UPDATED
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val MAX_CHAPTERS = 5
        private const val TITLE_MAX_LEN = 45
        private const val ICON_SIZE = 192
    }
}
