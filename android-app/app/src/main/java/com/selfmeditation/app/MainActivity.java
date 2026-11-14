package com.selfmeditation.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIFICATION = 100;
    private static final int PITCH_CENTER = 18; // seekbar 0..30 -> -18..+12 semitones

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button playButton;
    private TextView statusText;
    private RadioGroup soundGroup;
    private RadioGroup modeGroup;
    private SeekBar pitchSeek;
    private SeekBar decaySeek;
    private SeekBar bpmSeek;
    private TextView pitchLabel;
    private TextView decayLabel;
    private TextView bpmLabel;
    private EditText inductionEdit;
    private EditText maintenanceEdit;

    private AudioTrack previewTrack;

    private View updateBanner;
    private TextView updateText;
    private TextView updateStatus;
    private ProgressBar updateProgress;
    private TextView checkedText;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        playButton = findViewById(R.id.playButton);
        statusText = findViewById(R.id.statusText);
        soundGroup = findViewById(R.id.soundGroup);
        modeGroup = findViewById(R.id.modeGroup);
        pitchSeek = findViewById(R.id.pitchSeek);
        decaySeek = findViewById(R.id.decaySeek);
        bpmSeek = findViewById(R.id.bpmSeek);
        pitchLabel = findViewById(R.id.pitchLabel);
        decayLabel = findViewById(R.id.decayLabel);
        bpmLabel = findViewById(R.id.bpmLabel);
        inductionEdit = findViewById(R.id.inductionEdit);
        maintenanceEdit = findViewById(R.id.maintenanceEdit);

        loadSettings();

        SeekBar.OnSeekBarChangeListener labels = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                updateLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        };
        pitchSeek.setOnSeekBarChangeListener(labels);
        decaySeek.setOnSeekBarChangeListener(labels);
        bpmSeek.setOnSeekBarChangeListener(labels);
        updateLabels();

        findViewById(R.id.previewButton).setOnClickListener(v -> preview());
        playButton.setOnClickListener(v -> toggle());

        // App update (banner + settings rows)
        updateBanner = findViewById(R.id.updateBanner);
        updateText = findViewById(R.id.updateText);
        updateStatus = findViewById(R.id.updateStatus);
        updateProgress = findViewById(R.id.updateProgress);
        checkedText = findViewById(R.id.checkedText);
        ((TextView) findViewById(R.id.versionText)).setText("현재 버전 " + Updater.current(this));
        updateBanner.setOnClickListener(v -> Updater.updateNow(this));
        findViewById(R.id.checkUpdateButton).setOnClickListener(v -> Updater.check(this, true, true));
        CheckBox auto = findViewById(R.id.autoUpdateCheck);
        auto.setChecked(Updater.autoCheck(this));
        auto.setOnCheckedChangeListener((b, on) -> Updater.setAutoCheck(this, on));

        String updated = Updater.justUpdated(this);
        if (updated != null) {
            Toast.makeText(this, "앱이 " + updated + " 로 업데이트되었습니다", Toast.LENGTH_LONG).show();
        }
        Updater.check(this, false);

        // Android 13+ needs permission to show the playback notification.
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
        Updater.listener = this::refreshUpdate;
        refreshUpdate();
    }

    private void refreshUpdate() {
        Updater.Info info = Updater.available;
        if (info == null) {
            updateBanner.setVisibility(View.GONE);
        } else {
            updateBanner.setVisibility(View.VISIBLE);
            updateBanner.setEnabled(!Updater.busy);
            updateText.setText("새 버전 " + info.version + " 이 있습니다 — 눌러서 업데이트");
            updateStatus.setText(Updater.status);
            updateStatus.setVisibility(Updater.status.isEmpty() ? View.GONE : View.VISIBLE);
            boolean showBar = Updater.busy || (Updater.step >= 2 && Updater.step <= 4);
            updateProgress.setVisibility(showBar ? View.VISIBLE : View.GONE);
            if (Updater.progress >= 0f) {
                updateProgress.setIndeterminate(false);
                updateProgress.setProgress((int) (Updater.progress * 100));
            } else {
                updateProgress.setIndeterminate(true);
            }
        }
        checkedText.setText(Updater.checkedMsg);
    }

    @Override
    protected void onPause() {
        Updater.listener = null;
        handler.removeCallbacks(ticker);
        releasePreview();
        super.onPause();
    }

    // ---- settings ----

    private int soundType() {
        int id = soundGroup.getCheckedRadioButtonId();
        if (id == R.id.soundBell) return SoundSynth.BELL;
        if (id == R.id.soundWood) return SoundSynth.WOOD;
        if (id == R.id.soundDrum) return SoundSynth.DRUM;
        if (id == R.id.soundDeep) return SoundSynth.DEEP;
        return SoundSynth.GONG;
    }

    private int mode() {
        int id = modeGroup.getCheckedRadioButtonId();
        if (id == R.id.modeNone) return PlaybackService.MODE_NONE;
        if (id == R.id.modeSame) return PlaybackService.MODE_SAME;
        return PlaybackService.MODE_FAINT;
    }

    private int pitch() {
        return pitchSeek.getProgress() - PITCH_CENTER;
    }

    private float decay() {
        return 0.5f + decaySeek.getProgress() * 0.05f; // 0.5x .. 2.0x
    }

    // Seconds between beats: 0.5 .. 10.0 in 0.5 s steps.
    private float interval() {
        return 0.5f + bpmSeek.getProgress() * 0.5f;
    }

    private int minutes(EditText edit, int fallback) {
        try {
            return Integer.parseInt(edit.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void loadSettings() {
        int[] soundIds = {R.id.soundGong, R.id.soundBell, R.id.soundWood, R.id.soundDrum,
                R.id.soundDeep};
        soundGroup.check(soundIds[Math.max(0, Math.min(4, prefs.getInt("sound", 0)))]);
        int[] modeIds = {R.id.modeNone, R.id.modeFaint, R.id.modeSame};
        modeGroup.check(modeIds[Math.max(0, Math.min(2, prefs.getInt("mode", 1)))]);
        pitchSeek.setProgress(prefs.getInt("pitchProgress2", PITCH_CENTER));
        decaySeek.setProgress(prefs.getInt("decayProgress", 10));
        bpmSeek.setProgress(prefs.getInt("intervalProgress", 3)); // 2.0 s
        inductionEdit.setText(String.valueOf(prefs.getInt("induction", 5)));
        maintenanceEdit.setText(String.valueOf(prefs.getInt("maintenance", 15)));
    }

    private void saveSettings() {
        prefs.edit()
                .putInt("sound", soundType())
                .putInt("mode", mode())
                .putInt("pitchProgress2", pitchSeek.getProgress())
                .putInt("decayProgress", decaySeek.getProgress())
                .putInt("intervalProgress", bpmSeek.getProgress())
                .putInt("induction", minutes(inductionEdit, 5))
                .putInt("maintenance", minutes(maintenanceEdit, 15))
                .apply();
    }

    private void updateLabels() {
        int p = pitch();
        pitchLabel.setText("음높이: " + (p > 0 ? "+" : "") + p);
        decayLabel.setText(String.format("울림 길이: x%.2f", decay()));
        bpmLabel.setText(String.format("%.1f초에 한 번", interval()));
    }

    // ---- start / stop ----

    private void toggle() {
        Intent intent = new Intent(this, PlaybackService.class);
        if (PlaybackService.running) {
            intent.setAction(PlaybackService.ACTION_STOP);
            startService(intent);
            playButton.setText("시작");
            statusText.setText("대기 중");
            return;
        }
        int induction = minutes(inductionEdit, 5);
        int maintenance = minutes(maintenanceEdit, 15);
        if (induction + maintenance <= 0) {
            statusText.setText("시간을 1분 이상으로 입력해 주세요");
            return;
        }
        releasePreview();
        saveSettings();
        intent.setAction(PlaybackService.ACTION_START);
        intent.putExtra(PlaybackService.EXTRA_SOUND, soundType());
        intent.putExtra(PlaybackService.EXTRA_PITCH, pitch());
        intent.putExtra(PlaybackService.EXTRA_DECAY, decay());
        intent.putExtra(PlaybackService.EXTRA_INTERVAL, interval());
        intent.putExtra(PlaybackService.EXTRA_INDUCTION_MIN, induction);
        intent.putExtra(PlaybackService.EXTRA_MAINTENANCE_MIN, maintenance);
        intent.putExtra(PlaybackService.EXTRA_MODE, mode());
        ContextCompat.startForegroundService(this, intent);
        // The service sets `running` itself; show it right away.
        playButton.setText("정지");
        statusText.setText("시작하는 중…");
    }

    private void updateStatus() {
        if (PlaybackService.running) {
            playButton.setText("정지");
            long elapsed = (SystemClock.elapsedRealtime() - PlaybackService.startedAt) / 1000;
            long remaining = Math.max(0, PlaybackService.totalSec - elapsed);
            String phase = elapsed < PlaybackService.inductionSec ? "유도 단계" : "유지 단계";
            if (elapsed >= PlaybackService.totalSec) phase = "마무리";
            statusText.setText(String.format("%s · 남은 시간 %d:%02d", phase, remaining / 60, remaining % 60));
        } else if (playButton.getText().toString().equals("정지")
                && SystemClock.elapsedRealtime() - PlaybackService.startedAt > 3000) {
            // Session ended on its own.
            playButton.setText("시작");
            statusText.setText("끝났습니다");
        }
    }

    // ---- preview ----

    private void preview() {
        releasePreview();
        float[] data = SoundSynth.render(soundType(), pitch(), decay());
        short[] pcm = new short[data.length];
        for (int i = 0; i < data.length; i++) {
            pcm[i] = (short) (Math.max(-1f, Math.min(1f, data[i])) * 32767);
        }
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
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        track.write(pcm, 0, pcm.length);
        track.play();
        previewTrack = track;
    }

    private void releasePreview() {
        if (previewTrack != null) {
            previewTrack.release();
            previewTrack = null;
        }
    }
}
