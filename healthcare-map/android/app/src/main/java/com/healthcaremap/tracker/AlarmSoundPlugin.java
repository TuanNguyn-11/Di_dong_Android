package com.healthcaremap.tracker;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Phát chuông báo khi có cảnh báo té ngã.
 *
 * VÌ SAO PHẢI VIẾT NATIVE THAY VÌ DÙNG WEB AUDIO:
 * Web Audio trong WebView phát qua luồng MEDIA. Rất nhiều người chỉnh âm lượng
 * chuông to nhưng để âm lượng media bằng 0, nên tiếng chuông báo té ngã im re mà
 * không ai biết. Ngoài ra WebView còn bị hệ điều hành bóp tiếng khi app ở nền.
 *
 * Plugin này dùng chuông báo thức của hệ thống phát qua luồng ALARM:
 *   - đi theo âm lượng báo thức, kêu cả khi máy để chế độ im lặng
 *   - không phụ thuộc AudioContext của WebView
 *   - không cần đóng gói thêm file âm thanh nào
 */
@CapacitorPlugin(name = "AlarmSound")
public class AlarmSoundPlugin extends Plugin {

    private static final String TAG = "AlarmSound";

    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoStop;

    @Override
    public void load() {
        vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", true);
        call.resolve(result);
    }

    /**
     * Bắt đầu kêu.
     *   loop       : lặp lại tới khi gọi stop() hoặc hết durationMs
     *   durationMs : tự tắt sau bấy nhiêu mili-giây (0 = không tự tắt)
     *   vibrate    : có rung kèm không
     */
    @PluginMethod
    public void play(PluginCall call) {
        boolean loop = Boolean.TRUE.equals(call.getBoolean("loop", true));
        int durationMs = call.getInt("durationMs", 15000);
        boolean shouldVibrate = Boolean.TRUE.equals(call.getBoolean("vibrate", true));

        stopInternal();

        JSObject result = new JSObject();
        try {
            // Ưu tiên chuông báo thức; máy nào không có thì lùi về chuông thông báo.
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);

            if (uri == null) {
                result.put("played", false);
                result.put("reason", "May khong co chuong he thong nao de phat.");
                call.resolve(result);
                return;
            }

            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(getContext(), uri);
            player.setLooping(loop);
            player.prepare();
            player.start();

            result.put("played", true);
        } catch (Exception e) {
            Log.e(TAG, "Không phát được chuông", e);
            releasePlayer();
            result.put("played", false);
            result.put("reason", String.valueOf(e.getMessage()));
        }

        if (shouldVibrate) vibrate();

        if (durationMs > 0) {
            autoStop = this::stopInternal;
            handler.postDelayed(autoStop, durationMs);
        }

        call.resolve(result);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopInternal();
        call.resolve();
    }

    private void stopInternal() {
        if (autoStop != null) {
            handler.removeCallbacks(autoStop);
            autoStop = null;
        }
        releasePlayer();
        if (vibrator != null) vibrator.cancel();
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.stop();
        } catch (Exception ignored) {
            // Trình phát có thể đã ở trạng thái không hợp lệ — kệ, vẫn giải phóng.
        }
        player.release();
        player = null;
    }

    private void vibrate() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = { 0, 400, 200, 400, 200, 400, 200, 600 };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Không rung được", e);
        }
    }

    @Override
    protected void handleOnDestroy() {
        stopInternal();
        super.handleOnDestroy();
    }
}
