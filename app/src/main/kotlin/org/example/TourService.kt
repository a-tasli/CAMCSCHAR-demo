package org.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service keeping the tour alive while the screen is off (§7.3):
 * - media notification with play/pause / next / previous actions
 * - MediaSession so headset / lock-screen media buttons control the tour
 * - partial wake lock while playing so the 1 s analysis loop, BLE notifications and
 *   TTS synthesis keep running with the phone in the pocket
 *
 * Tour state is reached through [TourControlBridge]; MainActivity registers the
 * callbacks when the [TourManager] is created.
 */
class TourService : Service() {

    companion object {
        const val CHANNEL_ID = "TourServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "org.example.action.PLAY_PAUSE"
        const val ACTION_NEXT = "org.example.action.NEXT"
        const val ACTION_PREVIOUS = "org.example.action.PREVIOUS"

        fun startService(context: Context) {
            val startIntent = Intent(context, TourService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }

        fun stopService(context: Context) {
            val stopIntent = Intent(context, TourService::class.java)
            context.stopService(stopIntent)
        }
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Refreshes notification + MediaSession playback state roughly once a second. */
    private val stateRefresher = object : Runnable {
        override fun run() {
            refreshMediaState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> { TourControlBridge.playPause?.invoke() }
            ACTION_NEXT -> { TourControlBridge.next?.invoke() }
            ACTION_PREVIOUS -> { TourControlBridge.previous?.invoke() }
        }

        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == null) {
            // Regular start (not a notification action): become a foreground service.
            try {
                setUpMediaSession()
                val notification = createNotification(TourControlBridge.isPlaying())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                handler.removeCallbacks(stateRefresher)
                handler.postDelayed(stateRefresher, 1000)
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        } else {
            refreshMediaState()
        }

        // Hold the wake lock only while audio is actually playing.
        setWakeLock(TourControlBridge.isPlaying())
        return START_NOT_STICKY
    }

    private fun setUpMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSession(this, "TourSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { TourControlBridge.playPause?.invoke() }
                override fun onPause() { TourControlBridge.playPause?.invoke() }
                override fun onSkipToNext() { TourControlBridge.next?.invoke() }
                override fun onSkipToPrevious() { TourControlBridge.previous?.invoke() }
            })
            isActive = true
        }
    }

    private fun refreshMediaState() {
        val playing = TourControlBridge.isPlaying()

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1.0f
                )
                .build()
        )

        setWakeLock(playing)

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(playing))
    }

    private fun setWakeLock(hold: Boolean) {
        val current = wakeLock
        if (hold) {
            if (current?.isHeld != true) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CAMCSCHAR:TourPlayback").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }
        } else {
            current?.let { if (it.isHeld) it.release() }
        }
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TourService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotification(isPlaying: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Campus Audio Tour")
            .setContentText(if (isPlaying) "Playing — adapting narration to your cognitive load" else "Paused")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                servicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                playPauseIcon,
                if (isPlaying) "Pause" else "Play",
                servicePendingIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                servicePendingIntent(ACTION_NEXT)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Tour Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stateRefresher)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
