package dev.sevenfgames.nakama

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val LTAG = "NakamaService"
const val CHANNEL_ID = "nakama_midi_service"
const val NOTIFICATION_ID = 1

class MidiForwarder: MidiReceiver() {
    private var output: MidiReceiver? = null

    // Keep this function as fast as possible
    override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
        if (output != null)
            output!!.onSend(msg, offset, count, timestamp)
    }

    fun setOutput(out: MidiReceiver) {
        Log.i(LTAG, "MidiForwarder setOutput")
        output = out
    }
}

class NakamaMidiService: MidiDeviceService() {
    private var appToNetForwarder = MidiForwarder()
    private var netToAppForwarder = MidiForwarder()

    override fun onCreate() {
        super.onCreate()
        Log.i(LTAG, "NakamaMidiService created")

        // Create a notification channel (idempotent, no-op if already created)
        val name = getString(R.string.channel_name)
        val desc = getString(R.string.channel_desc)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = desc
        }
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        appToNetForwarder.setOutput(outputPortReceivers[1])
        netToAppForwarder.setOutput(outputPortReceivers[0])

        // The Activity also need to be running to forward MIDI events
        if (!MainActivity.isRunning()) {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val runningTask = activityManager.appTasks.firstOrNull()?.taskInfo?.topActivity?.className
            if (runningTask != MainActivity::class.java.name) {
                showConnectNotification()
            }
        }
    }

    private fun showConnectNotification() {
        Log.i(LTAG, "showConnectNotification")

        // Check if we have permissions for posting notifications
        val grant = ActivityCompat.checkSelfPermission(
            this@NakamaMidiService, android.Manifest.permission.POST_NOTIFICATIONS
        )
        if (grant != PackageManager.PERMISSION_GRANTED) {
            Log.w(LTAG, "showConnectNotification: missing permission")
            return
        }

        // Create the notification and its related action
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notifyBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Network MIDI 2.0 Virtual Device")
            .setContentText("Did you connect to remote device?")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Actually send the notification
        with (NotificationManagerCompat.from(this)) {
            notify(NOTIFICATION_ID, notifyBuilder.build())
            Log.i(LTAG, "showConnectNotification: notification sent")
        }
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> {
        Log.i(LTAG, "onGetInputPortReceivers")
        return arrayOf(appToNetForwarder, netToAppForwarder)
    }
}
