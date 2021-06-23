package eu.kanade.tachiyomi.data.notification

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupRestoreService
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.updater.UpdaterService
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.AboutController
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID as ID

/**
 * Global [BroadcastReceiver] that runs on UI thread
 * Pending Broadcasts should be made from here.
 * NOTE: Use local broadcasts if possible.
 */
class NotificationReceiver : BroadcastReceiver() {
    /**
     * Download manager.
     */
    private val downloadManager: DownloadManager by injectLazy()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Dismiss notification
            ACTION_DISMISS_NOTIFICATION -> dismissNotification(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1))
            // Resume the download service
            ACTION_RESUME_DOWNLOADS -> DownloadService.start(context)
            // Pause the download service
            ACTION_PAUSE_DOWNLOADS -> {
                DownloadService.stop(context)
                downloadManager.pauseDownloads()
            }
            // Clear the download queue
            ACTION_CLEAR_DOWNLOADS -> downloadManager.clearQueue(true)
            // Launch share activity and dismiss notification
            ACTION_SHARE_IMAGE -> shareImage(
                context,
                intent.getStringExtra(EXTRA_FILE_LOCATION)!!,
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            )
            // Delete image from path and dismiss notification
            ACTION_DELETE_IMAGE -> deleteImage(
                context,
                intent.getStringExtra(EXTRA_FILE_LOCATION)!!,
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            )
            // Cancel library update and dismiss notification
            ACTION_CANCEL_LIBRARY_UPDATE -> cancelLibraryUpdate(context)
            ACTION_CANCEL_UPDATE_DOWNLOAD -> cancelDownloadUpdate(context)
            ACTION_CANCEL_RESTORE -> cancelRestoreUpdate(context)
            // Share backup file
            ACTION_SHARE_BACKUP ->
                shareBackup(
                    context,
                    intent.getParcelableExtra(EXTRA_URI)!!,
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                )
            // Open reader activity
            ACTION_OPEN_CHAPTER -> {
                openChapter(
                    context,
                    intent.getLongExtra(EXTRA_MANGA_ID, -1),
                    intent.getLongExtra(EXTRA_CHAPTER_ID, -1)
                )
            }
            ACTION_MARK_AS_READ -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId > -1) dismissNotification(
                    context,
                    notificationId,
                    intent.getIntExtra(EXTRA_GROUP_ID, 0)
                )
                val urls = intent.getStringArrayExtra(EXTRA_CHAPTER_URL) ?: return
                val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1)
                markAsRead(urls, mangaId)
            }

            // Share crash dump file
            ACTION_SHARE_CRASH_LOG ->
                shareFile(
                    context,
                    intent.getParcelableExtra(EXTRA_URI)!!,
                    "text/plain",
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                )
        }
    }

    /**
     * Dismiss the notification
     *
     * @param notificationId the id of the notification
     */
    private fun dismissNotification(context: Context, notificationId: Int) {
        context.notificationManager.cancel(notificationId)
    }

    /**
     * Called to start share intent to share image
     *
     * @param context context of application
     * @param path path of file
     * @param notificationId id of notification
     */
    private fun shareImage(context: Context, path: String, notificationId: Int) {
        val km = context.getSystemService(Activity.KEYGUARD_SERVICE) as KeyguardManager
        // Create intent
        val intent = Intent(Intent.ACTION_SEND).apply {
            val uri = File(path).getUriCompat(context)
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData = ClipData.newRawUri(null, uri)
            type = "image/*"
        }
        // Close Navigation Shade
    }

    /**
     * Called to start share intent to share backup file
     *
     * @param context context of application
     * @param path path of file
     * @param notificationId id of notification
     */
    private fun shareBackup(context: Context, uri: Uri, notificationId: Int) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/x-protobuf+gzip"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        // Dismiss notification
        dismissNotification(context, notificationId)
        // Launch share activity
        context.startActivity(sendIntent)
    }

    /**
     * Starts reader activity
     *
     * @param context context of application
     * @param mangaId id of manga
     * @param chapterId id of chapter
     */
    internal fun openChapter(context: Context, mangaId: Long, chapterId: Long) {
        dismissNotification(context, Notifications.ID_NEW_CHAPTERS)
        val db = DatabaseHelper(context)
        val manga = db.getManga(mangaId).executeAsBlocking()
        val chapter = db.getChapter(chapterId).executeAsBlocking()
        context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        if (manga != null && chapter != null) {
            val intent = ReaderActivity.newIntent(context, manga, chapter).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } else {
            context.toast(context.getString(R.string.next_chapter_not_found))
        }
    }

    /**
     * Called to start share intent to share backup file
     *
     * @param context context of application
     * @param path path of file
     * @param notificationId id of notification
     */
    private fun shareFile(context: Context, uri: Uri, fileMimeType: String, notificationId: Int) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            type = fileMimeType
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        // Dismiss notification
        dismissNotification(context, notificationId)
        // Launch share activity
        context.startActivity(sendIntent)
    }

    /**
     * Called to delete image
     *
     * @param path path of file
     * @param notificationId id of notification
     */
    private fun deleteImage(context: Context, path: String, notificationId: Int) {
        // Dismiss notification
        dismissNotification(context, notificationId)

        // Delete file
        val file = File(path)
        file.delete()

        DiskUtil.scanMedia(context, file)
    }

    /**
     * Method called when user wants to stop a library update
     *
     * @param context context of application
     * @param notificationId id of notification
     */
    private fun cancelLibraryUpdate(context: Context) {
        LibraryUpdateService.stop(context)
        Handler().post { dismissNotification(context, Notifications.ID_LIBRARY_PROGRESS) }
    }

    /**
     * Method called when user wants to mark as read
     *
     * @param context context of application
     * @param notificationId id of notification
     */
    private fun markAsRead(chapterUrls: Array<String>, mangaId: Long) {
        val db: DatabaseHelper = Injekt.get()
        chapterUrls.forEach {
            val chapter = db.getChapter(it, mangaId).executeAsBlocking() ?: return
            chapter.read = true
            db.updateChapterProgress(chapter).executeAsBlocking()
            val preferences: PreferencesHelper = Injekt.get()
            if (preferences.removeAfterMarkedAsRead()) {
                val manga = db.getManga(mangaId).executeAsBlocking() ?: return
                val sourceManager: SourceManager = Injekt.get()
                val source = sourceManager.get(manga.source) ?: return
                downloadManager.deleteChapters(listOf(chapter), manga, source)
            }
        }
    }

    /** Method called when user wants to stop a restore
     *
     * @param context context of application
     */
    private fun cancelRestoreUpdate(context: Context) {
        BackupRestoreService.stop(context)
        Handler().post { dismissNotification(context, Notifications.ID_RESTORE_PROGRESS) }
    }

    private fun cancelDownloadUpdate(context: Context) {
        UpdaterService.stop(context)
    }

    companion object {
        private const val NAME = "NotificationReceiver"

        // Called to launch share intent.
        private const val ACTION_SHARE_IMAGE = "$ID.$NAME.SHARE_IMAGE"

        // Called to delete image.
        private const val ACTION_DELETE_IMAGE = "$ID.$NAME.DELETE_IMAGE"

        // Called to launch send intent.
        private const val ACTION_SHARE_BACKUP = "$ID.$NAME.SEND_BACKUP"

        private const val ACTION_SHARE_CRASH_LOG = "$ID.$NAME.SEND_CRASH_LOG"

        // Called to cancel library update.
        private const val ACTION_CANCEL_LIBRARY_UPDATE = "$ID.$NAME.CANCEL_LIBRARY_UPDATE"

        private const val ACTION_CANCEL_UPDATE_DOWNLOAD = "$ID.$NAME.CANCEL_UPDATE_DOWNLOAD"

        // Called to mark as read
        private const val ACTION_MARK_AS_READ = "$ID.$NAME.MARK_AS_READ"

        // Called to cancel restore
        private const val ACTION_CANCEL_RESTORE = "$ID.$NAME.CANCEL_RESTORE"

        // Called to open chapter
        private const val ACTION_OPEN_CHAPTER = "$ID.$NAME.ACTION_OPEN_CHAPTER"

        // Value containing file location.
        private const val EXTRA_FILE_LOCATION = "$ID.$NAME.FILE_LOCATION"

        // Called to resume downloads.
        private const val ACTION_RESUME_DOWNLOADS = "$ID.$NAME.ACTION_RESUME_DOWNLOADS"

        // Called to pause downloads.
        private const val ACTION_PAUSE_DOWNLOADS = "$ID.$NAME.ACTION_PAUSE_DOWNLOADS"

        // Called to clear downloads.
        private const val ACTION_CLEAR_DOWNLOADS = "$ID.$NAME.ACTION_CLEAR_DOWNLOADS"

        // Called to dismiss notification.
        private const val ACTION_DISMISS_NOTIFICATION = "$ID.$NAME.ACTION_DISMISS_NOTIFICATION"

        // Value containing uri.
        private const val EXTRA_URI = "$ID.$NAME.URI"

        // Value containing notification id.
        private const val EXTRA_NOTIFICATION_ID = "$ID.$NAME.NOTIFICATION_ID"

        // Value containing group id.
        private const val EXTRA_GROUP_ID = "$ID.$NAME.EXTRA_GROUP_ID"

        // Value containing manga id.
        private const val EXTRA_MANGA_ID = "$ID.$NAME.EXTRA_MANGA_ID"

        // Value containing chapter id.
        private const val EXTRA_CHAPTER_ID = "$ID.$NAME.EXTRA_CHAPTER_ID"

        // Value containing chapter url.
        private const val EXTRA_CHAPTER_URL = "$ID.$NAME.EXTRA_CHAPTER_URL"

        /**
         * Returns a [PendingIntent] that resumes the download of a chapter
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun resumeDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_RESUME_DOWNLOADS
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that pauses the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun pauseDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_PAUSE_DOWNLOADS
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns a [PendingIntent] that clears the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun clearDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CLEAR_DOWNLOADS
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that starts a service which dismissed the notification
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun dismissNotificationPendingBroadcast(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_DISMISS_NOTIFICATION
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that starts a service which dismissed the notification
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun dismissNotification(
            context: Context,
            notificationId: Int,
            groupId: Int? =
                null
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val groupKey = context.notificationManager.activeNotifications.find {
                    it.id == notificationId
                }?.groupKey
                if (groupId != null && groupId != 0 && groupKey != null && groupKey.isNotEmpty()) {
                    val notifications = context.notificationManager.activeNotifications.filter {
                        it.groupKey == groupKey
                    }
                    if (notifications.size == 2) {
                        context.notificationManager.cancel(groupId)
                        return
                    }
                }
            }
            context.notificationManager.cancel(notificationId)
        }

        /**
         * Returns [PendingIntent] that starts a service which cancels the notification and starts a share activity
         *
         * @param context context of application
         * @param path location path of file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun shareImagePendingBroadcast(context: Context, path: String, notificationId: Int): PendingIntent {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                val uri = File(path).getUriCompat(context)
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
                clipData = ClipData.newRawUri(null, uri)
                type = "image/*"
            }
            return PendingIntent.getActivity(
                context,
                0,
                shareIntent,
                PendingIntent
                    .FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which removes an image from disk
         *
         * @param context context of application
         * @param path location path of file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun deleteImagePendingBroadcast(context: Context, path: String, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_DELETE_IMAGE
                putExtra(EXTRA_FILE_LOCATION, path)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that starts a reader activity containing chapter.
         *
         * @param context context of application
         * @param manga manga of chapter
         * @param chapter chapter that needs to be opened
         */
        internal fun openChapterPendingActivity(
            context: Context,
            manga: Manga,
            chapter:
                Chapter
        ): PendingIntent {
            val newIntent = ReaderActivity.newIntent(context, manga, chapter)
            return PendingIntent.getActivity(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent
                    .FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Returns [PendingIntent] that opens release notes for the next update.
         *
         * @param context context of application
         * @param notes notes of the release
         * @param downloadLink download link to the apk
         */
        internal fun openUpdatePendingActivity(context: Context, notes: String, downloadLink: String):
            PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setAction(MainActivity.SHORTCUT_UPDATE_NOTES)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(AboutController.NewUpdateDialogController.BODY_KEY, notes)
                    .putExtra(AboutController.NewUpdateDialogController.URL_KEY, downloadLink)
            return PendingIntent.getActivity(
                context,
                downloadLink.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Returns [PendingIntent] that opens the manga details controller.
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun openChapterPendingActivity(context: Context, manga: Manga, groupId: Int):
            PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setAction(MainActivity.SHORTCUT_MANGA)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MangaDetailsController.MANGA_EXTRA, manga.id)
                    .putExtra("notificationId", manga.id.hashCode())
                    .putExtra("groupId", groupId)
            return PendingIntent.getActivity(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Returns [PendingIntent] that opens the error log file in an external viewer
         *
         * @param context context of application
         * @param uri uri of error log file
         * @return [PendingIntent]
         */
        internal fun openErrorLogPendingActivity(context: Context, uri: Uri): PendingIntent {
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "text/plain")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that opens the extensions controller,
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun openExtensionsPendingActivity(context: Context): PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setAction(MainActivity.SHORTCUT_EXTENSIONS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**Returns the PendingIntent that will open the error log in an external text viewer
         *
         */
        internal fun openFileExplorerPendingActivity(context: Context, uri: Uri): PendingIntent {
            val toLaunch = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            return PendingIntent.getActivity(context, 0, toLaunch, PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that marks a chapter as read and deletes it if preferred
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun markAsReadPendingBroadcast(
            context: Context,
            manga: Manga,
            chapters:
                Array<Chapter>,
            groupId: Int
        ):
            PendingIntent {
            val newIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_MARK_AS_READ
                putExtra(EXTRA_CHAPTER_URL, chapters.map { it.url }.toTypedArray())
                putExtra(EXTRA_MANGA_ID, manga.id)
                putExtra(EXTRA_NOTIFICATION_ID, manga.id.hashCode())
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            return PendingIntent.getBroadcast(context, manga.id.hashCode(), newIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that starts a service which stops the library update
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun cancelLibraryUpdatePendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_LIBRARY_UPDATE
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that cancels the download for a Tachiyomi update
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun cancelUpdateDownloadPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_UPDATE_DOWNLOAD
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that starts a share activity for a backup file.
         *
         * @param context context of application
         * @param uri uri of backup file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun shareBackupPendingBroadcast(context: Context, uri: Uri, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SHARE_BACKUP
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that starts a share activity for a crash log dump file.
         *
         * @param context context of application
         * @param uri uri of file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun shareCrashLogPendingBroadcast(context: Context, uri: Uri, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SHARE_CRASH_LOG
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Returns [PendingIntent] that cancels a backup restore job.
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun cancelRestorePendingBroadcast(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_RESTORE
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
