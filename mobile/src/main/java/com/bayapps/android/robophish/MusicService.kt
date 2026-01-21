package com.bayapps.android.robophish

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class MusicService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibraryService.MediaLibrarySession
    private lateinit var notificationManager: PlayerNotificationManager

    private val deps by lazy { ServiceLocator.get(this) }
    private val musicProvider: MusicProvider by lazy { deps.musicProvider }
    private val notificationManagerCompat: NotificationManagerCompat by lazy { deps.notificationManager }

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()
            .apply { setHandleAudioBecomingNoisy(true) }

        mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            player,
            LibraryCallback()
        ).build()

        createNotificationChannel()
        notificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            MEDIA_PLAYER_NOTIFICATION
        )
            .setMediaDescriptionAdapter(DescriptionAdapter())
            .setNotificationListener(NotificationListener())
            .build()
        notificationManager.setPlayer(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        notificationManager.setPlayer(null)
        mediaLibrarySession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MEDIA_PLAYER_NOTIFICATION,
                MEDIA_PLAYER_NOTIFICATION,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManagerCompat.createNotificationChannel(channel)
        }
    }

    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): com.google.common.util.concurrent.ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration?.uri != null) {
                    item
                } else {
                    val trackId = MediaIDHelper.extractMusicIDFromMediaID(item.mediaId)
                    musicProvider.getTrackItem(trackId) ?: item
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration?.uri != null) {
                    item
                } else {
                    val trackId = MediaIDHelper.extractMusicIDFromMediaID(item.mediaId)
                    musicProvider.getTrackItem(trackId) ?: item
                }
            }
            val safeIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, safeIndex, startPositionMs)
            )
        }

        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (player.mediaItemCount == 0) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
            }
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ) = immediateResult(rootItem())

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ) = futureResultList {
            musicProvider.children(parentId)
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ) = futureResult {
            val trackId = MediaIDHelper.extractMusicIDFromMediaID(mediaId)
            musicProvider.getTrackItem(trackId) ?: rootItem()
        }

        override fun onSearch(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ) = immediateVoidResult()

        override fun onGetSearchResult(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ) = immediateResultList(emptyList())
    }

    private fun rootItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MediaIDHelper.MEDIA_ID_ROOT)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun immediateResult(item: MediaItem): com.google.common.util.concurrent.ListenableFuture<LibraryResult<MediaItem>> {
        return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(item, null))
    }

    private fun immediateResultList(
        items: List<MediaItem>
    ): com.google.common.util.concurrent.ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItemList(items, null))
    }

    private fun immediateVoidResult(): com.google.common.util.concurrent.ListenableFuture<LibraryResult<Void>> {
        return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofVoid())
    }

    private fun futureResult(
        block: suspend () -> MediaItem
    ): com.google.common.util.concurrent.ListenableFuture<LibraryResult<MediaItem>> {
        val future = SettableFuture.create<LibraryResult<MediaItem>>()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val item = block()
                future.set(LibraryResult.ofItem(item, null))
            } catch (e: Exception) {
                Timber.e(e, "Error loading item")
                future.setException(e)
            }
        }
        return future
    }

    private fun futureResultList(
        block: suspend () -> List<MediaItem>
    ): com.google.common.util.concurrent.ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val items = block()
                future.set(LibraryResult.ofItemList(items, null))
            } catch (e: Exception) {
                Timber.e(e, "Error loading children")
                future.setException(e)
            }
        }
        return future
    }

    private inner class DescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return player.mediaMetadata.title ?: getString(R.string.app_name)
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            val intent = Intent(this@MusicService, com.bayapps.android.robophish.ui.MusicPlayerActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                this@MusicService,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        override fun getCurrentContentText(player: Player): CharSequence? {
            return player.mediaMetadata.subtitle
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ) = null
    }

    private inner class NotificationListener : PlayerNotificationManager.NotificationListener {
        override fun onNotificationPosted(notificationId: Int, notification: android.app.Notification, ongoing: Boolean) {
            if (ongoing) {
                startForeground(notificationId, notification)
            } else {
                stopForegroundCompat(remove = false)
                notificationManagerCompat.notify(notificationId, notification)
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            stopForegroundCompat(remove = true)
            stopSelf()
        }
    }

    private fun stopForegroundCompat(remove: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(remove)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE = 100
    }
}
