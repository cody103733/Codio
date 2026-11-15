package com.codio.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.codio.tv.ui.Net;
import com.codio.tv.ui.NewEpisodes;

import java.util.List;

/**
 * Phones: looks for new episodes of the shows you're watching a few times a day while Codio is closed, and sends a
 * notification for each (Settings, Playback, New episode alerts). Tapping one opens the show.
 */
public final class NewEpisodesJob extends JobService {
    private static final String TAG = "CodioNewEpisodes";
    private static final int JOB = 4107;
    private static final String CHANNEL = "new_episodes";
    static final String EXTRA_SHOW = "open_show", EXTRA_TYPE = "open_type", EXTRA_NAME = "open_name";

    /** Turns the background check on or off to match the setting. */
    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!BuildConfig.PHONE || !NewEpisodes.enabled(c)) {
                js.cancel(JOB);
                return;
            }
            if (js.getPendingJob(JOB) != null) return;
            js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, NewEpisodesJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 3600_000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception e) {
            Log.w(TAG, "background check not scheduled: " + e);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context c = getApplicationContext();
        if (!NewEpisodes.enabled(c)) return false;
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    NewEpisodes.load(c);
                    List<NewEpisodes.Alert> fresh = NewEpisodes.check(c);
                    for (NewEpisodes.Alert a : fresh) post(c, a);
                } catch (Exception e) {
                    Log.w(TAG, "background check failed: " + e);
                }
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    /** "Show name" / "New episode: S2 E5 · Title". Silently does nothing when notifications aren't allowed. */
    static void post(Context c, NewEpisodes.Alert a) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("When a new episode of a show you're watching is out");
            nm.createNotificationChannel(ch);
            Intent open = new Intent(c, MainActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(android.net.Uri.parse("codio://show/" + android.net.Uri.encode(a.showId)))
                    .putExtra(EXTRA_SHOW, a.showId).putExtra(EXTRA_TYPE, a.type).putExtra(EXTRA_NAME, a.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent tap = PendingIntent.getActivity(c, a.showId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text = (a.count > 1 ? a.count + " new episodes, the latest: " : "New episode: ") + a.episodeLine();
            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setColor(0xFFE50914)
                    .setContentTitle(a.name == null || a.name.isEmpty() ? "New episode" : a.name)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(tap);
            if (a.poster != null) {
                try {
                    byte[] bytes = Net.getBytes(a.poster);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    Bitmap poster = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    if (poster != null) b.setLargeIcon(poster);
                } catch (Exception ignored) {
                    // No picture, then.
                }
            }
            nm.notify(a.showId.hashCode(), b.build());
        } catch (Exception e) {
            Log.w(TAG, "notification not shown: " + e);
        }
    }
}
