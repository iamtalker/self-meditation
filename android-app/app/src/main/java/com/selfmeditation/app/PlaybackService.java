package com.selfmeditation.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

// Plays a timed beat session in a foreground service so it keeps going with the screen off.
//
// Session = start gong -> induction (steady beat) -> maintenance (beat fades per setting) -> end gong.
public class PlaybackService extends Service {

    public static final String ACTION_START = "com.selfmeditation.app.START";
    public static final String ACTION_STOP = "com.selfmeditation.app.STOP";

    public static final String EXTRA_SOUND = "sound";
    public static final String EXTRA_PITCH = "pitch";
    public static final String EXTRA_DECAY = "decay";
    public static final String EXTRA_INTERVAL = "interval_sec";
    public static final String EXTRA_INDUCTION_MIN = "induction_min";
    public static final String EXTRA_MAINTENANCE_MIN = "maintenance_min";
    public static final String EXTRA_MODE = "mode";

    public static final int MODE_NONE = 0;
    public static final int MODE_FAINT = 1;
    public static final int MODE_SAME = 2;

    // Shared with the UI so it can show the remaining time.
    static volatile boolean running = false;
    static volatile long startedAt = 0;
    static volatile int inductionSec = 0;
    static volatile int totalSec = 0;

    private static final String CHANNEL_ID = "meditation_playback";
    private static final int NOTIFICATION_ID = 1;

    private static final int CHUNK = SoundSynth.RATE / 100; // 10 ms
    private static final double LEAD_IN_SEC = 4.0;          // silence after the start gong
    private static final double FADE_SEC = 90.0;            // induction -> maintenance fade
    private static final float FAINT_LEVEL = 0.15f;

    private volatile boolean stopRequested = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startSession(intent);
        return START_NOT_STICKY;
    }

    private void startSession(Intent intent) {
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (worker != null && worker.isAlive()) {
            return;
        }

        final int sound = intent.getIntExtra(EXTRA_SOUND, SoundSynth.GONG);
        final int pitch = intent.getIntExtra(EXTRA_PITCH, 0);
        final float decay = intent.getFloatExtra(EXTRA_DECAY, 1f);
        final float interval = Math.max(0.3f, intent.getFloatExtra(EXTRA_INTERVAL, 2f));
        final int inductionMin = Math.max(0, intent.getIntExtra(EXTRA_INDUCTION_MIN, 5));
        final int maintenanceMin = Math.max(0, intent.getIntExtra(EXTRA_MAINTENANCE_MIN, 15));
        final int mode = intent.getIntExtra(EXTRA_MODE, MODE_FAINT);

        inductionSec = inductionMin * 60;
        totalSec = (inductionMin + maintenanceMin) * 60;
        startedAt = SystemClock.elapsedRealtime();
        running = true;
        stopRequested = false;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SelfMeditation:session");
        wakeLock.acquire((totalSec + 120) * 1000L);

        worker = new Thread(() -> {
            try {
                runSession(sound, pitch, decay, interval, inductionMin * 60.0,
                        (inductionMin + maintenanceMin) * 60.0, mode);
            } finally {
                running = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "meditation-audio");
        worker.start();
    }

    private static final class Voice {
        final float[] data;
        final float gain;
        int pos; // negative = not started yet

        Voice(float[] data, float gain, int pos) {
            this.data = data;
            this.gain = gain;
            this.pos = pos;
        }
    }

    private void runSession(int sound, int pitch, float decay, float intervalSec,
                            double inductionSec, double totalSec, int mode) {
        float[] beat = SoundSynth.render(sound, pitch, decay);
        float[] gong = SoundSynth.render(SoundSynth.GONG, -2, 1.3f);
        double gongSec = gong.length / (double) SoundSynth.RATE;

        int minBuf = AudioTrack.getMinBufferSize(SoundSynth.RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SoundSynth.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, SoundSynth.RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        List<Voice> voices = new ArrayList<>();
        float[] mix = new float[CHUNK];
        short[] pcm = new short[CHUNK];
        long written = 0;
        double baseInterval = intervalSec;
        double nextBeat = LEAD_IN_SEC;
        boolean endPlayed = false;

        track.play();
        voices.add(new Voice(gong, 0.9f, 0));

        while (!stopRequested) {
            double t = written / (double) SoundSynth.RATE;
            double chunkEnd = t + CHUNK / (double) SoundSynth.RATE;

            while (nextBeat < chunkEnd && nextBeat < totalSec) {
                float g = gainAt(nextBeat, inductionSec, mode);
                if (g > 0.02f) {
                    int offset = (int) Math.max(0, (nextBeat - t) * SoundSynth.RATE);
                    voices.add(new Voice(beat, g, -offset));
                }
                nextBeat += baseInterval * intervalFactor(nextBeat, inductionSec, mode);
            }
            if (!endPlayed && chunkEnd >= totalSec) {
                int offset = (int) Math.max(0, (totalSec - t) * SoundSynth.RATE);
                voices.add(new Voice(gong, 0.9f, -offset));
                endPlayed = true;
            }
            if (endPlayed && t >= totalSec + gongSec) {
                break;
            }

            java.util.Arrays.fill(mix, 0f);
            for (int vi = voices.size() - 1; vi >= 0; vi--) {
                Voice v = voices.get(vi);
                for (int i = 0; i < CHUNK; i++) {
                    int idx = v.pos + i;
                    if (idx >= v.data.length) break;
                    if (idx >= 0) mix[i] += v.data[idx] * v.gain;
                }
                v.pos += CHUNK;
                if (v.pos >= v.data.length) voices.remove(vi);
            }
            for (int i = 0; i < CHUNK; i++) {
                float s = Math.max(-1f, Math.min(1f, mix[i]));
                pcm[i] = (short) (s * 32767);
            }
            track.write(pcm, 0, CHUNK);
            written += CHUNK;
        }

        if (stopRequested) {
            track.pause();
            track.flush();
        } else {
            track.stop(); // lets the last buffered sound finish
        }
        track.release();
    }

    // 1.0 during induction, then fades to the maintenance level.
    private static float gainAt(double t, double inductionSec, int mode) {
        double after = t - inductionSec;
        if (after <= 0 || mode == MODE_SAME) return 1f;
        float target = mode == MODE_FAINT ? FAINT_LEVEL : 0f;
        float ramp = (float) Math.min(1.0, after / FADE_SEC);
        return 1f + (target - 1f) * ramp;
    }

    // Faint maintenance beats are also sparser (every other beat).
    private static double intervalFactor(double t, double inductionSec, int mode) {
        if (mode == MODE_FAINT && t - inductionSec >= FADE_SEC) return 2.0;
        return 1.0;
    }

    private void stopSession() {
        stopRequested = true;
        if (worker == null || !worker.isAlive()) {
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        // Otherwise the worker thread cleans up and stops the service itself.
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "명상 재생", NotificationManager.IMPORTANCE_LOW);
            // No notification sound: the meditation audio is the only sound.
            channel.setSound(null, null);
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("명상 진행 중")
                .setContentText("박자에 집중해 보세요")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
