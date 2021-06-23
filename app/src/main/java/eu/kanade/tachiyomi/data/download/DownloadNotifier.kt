package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.notificationManager
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple chapters.
 *
 * @param context context of application
 */
internal class DownloadNotifier(private val context: Context) {

    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Notification builder.
     */
    private val notification by lazy {
        NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
    }

    /**
     * Status of download. Used for correct notification icon.
     */
    private var isDownloading = false

    /**
     * Updated when error is thrown
     */
    var errorThrown = false

    /**
     * Updated when paused
     */
    var paused = false

    /**
     * Shows a notification from this builder.
     *
     * @param id the id of the notification.
     */
    private fun NotificationCompat.Builder.show(id: Int = Notifications.ID_DOWNLOAD_CHAPTER) {
        context.notificationManager.notify(id, build())
    }

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    fun dismiss() {
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_CHAPTER)
    }

    fun setPlaceholder(download: Download?) {
        with(notification) {
            // Check if first call.
            if (!isDownloading) {
                setSmallIcon(android.R.drawable.stat_sys_download)
                setAutoCancel(false)
                clearActions()
                // Open download manager when clicked
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                color = ContextCompat.getColor(context, R.color.colorAccent)
                isDownloading = true
                // Pause action
                addAction(
                    R.drawable.ic_pause_24dp,
                    context.getString(R.string.pause),
                    NotificationReceiver.pauseDownloadsPendingBroadcast(context)
                )
            }

            if (download != null && !preferences.hideNotificationContent()) {
                val title = download.manga.title.chop(15)
                val quotedTitle = Pattern.quote(title)
                val chapter = download.chapter.name.replaceFirst(
                    "$quotedTitle[\\s]*[-]*[\\s]*"
                        .toRegex(RegexOption.IGNORE_CASE),
                    ""
                )
                setContentTitle("$title - $chapter".chop(30))
                setContentText(
                    context.getString(R.string.downloading)
                )
            } else {
                setContentTitle(
                    context.getString(
                        R.string.downloading
                    )
                )
                setContentText(null)
            }
            setProgress(0, 0, true)
            setStyle(null)
        }
        // Displays the progress bar on notification
        notification.show()
    }

    /**
     * Called when download progress changes.
     *
     * @param download download object containing download information.
     */
    fun onProgressChange(download: Download) {
        // Create notification
        with(notification) {
            // Check if first call.
            if (!isDownloading) {
                setSmallIcon(android.R.drawable.stat_sys_download)
                setAutoCancel(false)
                clearActions()
                // Open download manager when clicked
                color = ContextCompat.getColor(context, R.color.colorAccent)
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                isDownloading = true
                // Pause action
                addAction(
                    R.drawable.ic_pause_24dp,
                    context.getString(R.string.pause),
                    NotificationReceiver.pauseDownloadsPendingBroadcast(context)
                )
            }

            val downloadingProgressText =
                context.getString(R.string.downloading_progress)
                    .format(download.downloadedImages, download.pages!!.size)

            if (preferences.hideNotificationContent()) {
                setContentTitle(downloadingProgressText)
            } else {
                val title = download.manga.title.chop(15)
                val quotedTitle = Pattern.quote(title)
                val chapter = download.chapter.name.replaceFirst(
                    "$quotedTitle[\\s]*[-]*[\\s]*".toRegex(RegexOption.IGNORE_CASE),
                    ""
                )
                setContentTitle("$title - $chapter".chop(30))
                setContentText(downloadingProgressText)
            }
            setStyle(null)
            setProgress(download.pages!!.size, download.downloadedImages, false)
        }
        // Displays the progress bar on notification
        notification.show()
    }

    /**
     * Show notification when download is paused.
     */
    fun onDownloadPaused() {
        with(notification) {
            setContentTitle(context.getString(R.string.paused))
            setContentText(context.getString(R.string.download_paused))
            setSmallIcon(R.drawable.ic_pause_24dp)
            setAutoCancel(false)
            setProgress(0, 0, false)
            color = ContextCompat.getColor(context, R.color.colorAccent)
            clearActions()
            // Open download manager when clicked
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            // Resume action
            addAction(
                R.drawable.ic_play_arrow_24dp,
                context.getString(R.string.resume),
                NotificationReceiver.resumeDownloadsPendingBroadcast(context)
            )
            // Clear action
            addAction(
                R.drawable.ic_close_24dp,
                context.getString(R.string.cancel_all),
                NotificationReceiver.clearDownloadsPendingBroadcast(context)
            )
        }

        // Show notification.
        notification.show()

        // Reset initial values
        isDownloading = false
    }

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     */
    fun onWarning(reason: String) {
        with(notification) {
            setContentTitle(context.getString(R.string.downloads))
            setContentText(reason)
            color = ContextCompat.getColor(context, R.color.colorAccent)
            setSmallIcon(android.R.drawable.stat_sys_warning)
            setAutoCancel(true)
            clearActions()
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            setProgress(0, 0, false)
        }
        notification.show()

        // Reset download information
        isDownloading = false
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param chapter string containing chapter title.
     */
    fun onError(
        error: String? = null,
        chapter: String? = null,
        customIntent: Intent? = null
    ) {
        // Create notification
        with(notification) {
            setContentTitle(chapter ?: context.getString(R.string.download_error))
            setContentText(error ?: context.getString(R.string.could_not_download_unexpected_error))
            setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    error ?: context.getString(R.string.could_not_download_unexpected_error)
                )
            )
            setSmallIcon(android.R.drawable.stat_sys_warning)
            setCategory(NotificationCompat.CATEGORY_ERROR)
            clearActions()
            setAutoCancel(true)
            if (customIntent != null) {
                setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        customIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            }
            color = ContextCompat.getColor(context, R.color.colorAccent)
            setProgress(0, 0, false)
        }
        notification.show(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)

        // Reset download information
        errorThrown = true
        isDownloading = false
    }
}
