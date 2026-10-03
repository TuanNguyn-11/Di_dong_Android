package com.fallguard.monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/** Foreground sensor service: synchronized 100 Hz IMU and on-device INT8 AI.
 * Inference stays on the worker; alarm/UI notifications run on the main thread.
 * The existing WebView owns cancellation countdown and Firebase delivery.
 */
public class FallGuardSensorService extends Service implements SensorEventListener {

    private static final String TAG = "FallGuardSensor";

    public static final String ACTION_START = "com.fallguard.monitor.START";
    public static final String ACTION_STOP = "com.fallguard.monitor.STOP";
    public static final String ACTION_START_ALARM = "com.fallguard.monitor.START_ALARM";
    public static final String ACTION_STOP_ALARM = "com.fallguard.monitor.STOP_ALARM";

    public static final String EXTRA_MUTED = "muted";

    private static final String CHANNEL_MONITOR = "fallguard_monitor";
    private static final String CHANNEL_ALARM = "fallguard_alarm";
    private static final int NOTIFICATION_ID = 4301;
    private static final int ALARM_NOTIFICATION_ID = 4302;

    /** One UI batch per 250 ms at 100 Hz. */
    private static final int BATCH_SIZE = 25;

    /** Sau khi báo một lần thì im trong bấy nhiêu mili-giây, tránh báo dồn dập */
    private static final long DETECT_COOLDOWN_MS = 8000;

    /** Nơi TypeScript nhận dữ liệu và sự kiện từ dịch vụ này */
    public interface Listener {
        void onSensorBatch(long t0, int hz, float[] ax, float[] ay, float[] az,
                           float[] gx, float[] gy, float[] gz);

        void onFallDetected(double probability);
        void onMonitorError(String message);
    }

    @Nullable
    private static volatile Listener listener;

    /** Cho phép TypeScript biết dịch vụ có đang chạy không sau khi app bị dựng lại */
    private static volatile boolean running = false;
    private static volatile String startupError;
    private static volatile long inferenceCount;
    private static volatile long lastInferenceMicros;
    private static volatile double lastProbability = -1;
    private static volatile long lastInferenceElapsed;
    private static volatile int goldenPassed;
    private static volatile String sessionId = "";
    public static double getLastProbability() { return lastProbability; }
    public static long getInferenceAgeMs() { return lastInferenceElapsed == 0 ? -1 : SystemClock.elapsedRealtime() - lastInferenceElapsed; }
    public static int getGoldenPassed() { return goldenPassed; }
    public static String getSessionId() { return sessionId; }
    public static long getInferenceCount() { return inferenceCount; }
    public static long getLastInferenceMicros() { return lastInferenceMicros; }
    public static String getStartupError() { return startupError; }
    public static void clearStartupError() { startupError = null; }
    private HandlerThread workerThread;
    private Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FallAiEngine ai;
    private ImuSynchronizer synchronizer;
    private final FallDecision decision = new FallDecision();
    private final float[][] window = new float[50][6];
    private int writeIndex, sampleCount, stepCount;
    private volatile boolean alarmActive;
    private volatile boolean stopping;
    private boolean starting;
    private volatile long lastAlignedAt;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running || stopping) return;
            if (SystemClock.elapsedRealtime() - lastAlignedAt > 3000) {
                fail("Không nhận đủ dữ liệu gia tốc và gyro liên tục ở 100 Hz");
                return;
            }
            main.postDelayed(this, 1000);
        }
    };

    public static void setListener(@Nullable Listener value) {
        listener = value;
    }

    public static boolean isRunning() {
        return running;
    }

    private SensorManager sensorManager;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer alarmPlayer;
    private Vibrator vibrator;

    private volatile boolean alarmMuted = false;
    private long lastDetectionAt = 0;

    // Bộ đệm một lô mẫu
    private final float[] bufAx = new float[BATCH_SIZE];
    private final float[] bufAy = new float[BATCH_SIZE];
    private final float[] bufAz = new float[BATCH_SIZE];
    private final float[] bufGx = new float[BATCH_SIZE];
    private final float[] bufGy = new float[BATCH_SIZE];
    private final float[] bufGz = new float[BATCH_SIZE];
    private int bufCount = 0;
    private long bufStartAt = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createNotificationChannels();
        workerThread = new HandlerThread("FallGuardAI");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_STOP:
                stopMonitoring();
                return START_NOT_STICKY;

            case ACTION_START_ALARM:
                startAlarm();
                return START_STICKY;

            case ACTION_STOP_ALARM:
                stopAlarm();
                return START_STICKY;

            case ACTION_START:
            default:
                // Gửi lại ACTION_START cũng chính là cách đổi ngưỡng / bật tắt
                // tiếng: dịch vụ nhận giá trị mới rồi bỏ qua phần khởi động nếu
                // đang chạy sẵn. Đỡ phải bind service chỉ để gọi một setter.
                if (intent != null) {
                    alarmMuted = intent.getBooleanExtra(EXTRA_MUTED, alarmMuted);
                    if (alarmMuted) { releaseAlarmPlayer(); if (vibrator != null) vibrator.cancel(); }
                }
                startMonitoring();
                // START_STICKY: nếu hệ thống có giết tiến trình vì thiếu bộ nhớ
                // thì vẫn dựng lại dịch vụ khi rảnh tay.
                return START_STICKY;
        }
    }

    // ------------------------------------------------------------
    // BẬT / TẮT GIÁM SÁT
    // ------------------------------------------------------------

    private void startMonitoring() {
        if (running || starting || stopping) return;
        starting = true;
        inferenceCount = 0;
        lastInferenceMicros = 0;
        lastProbability = -1;
        lastInferenceElapsed = 0;
        goldenPassed = 0;
        sessionId = java.util.UUID.randomUUID().toString();

        try {
            startForegroundWithNotification();
        } catch (Exception e) {
            // Android 12+ chặn khởi động dịch vụ nền từ background. Ở đây luôn
            // được gọi lúc app đang mở nên hiếm khi xảy ra, nhưng vẫn phải chịu lỗi.
            fail("Không khởi động được foreground service: " + e.getMessage());
            return;
        }

        acquireWakeLock();

        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accel == null || gyro == null) {
            fail("AI cần cả cảm biến gia tốc và con quay hồi chuyển");
            return;
        }
        worker.post(() -> {
            try {
                ai = new FallAiEngine(getAssets());
                int passed = ai.verifyGolden(getAssets());
                goldenPassed = passed;
                Log.i(TAG, "AI golden PASS " + passed + "/10; SHA256=" + FallAiEngine.SHA256);
                synchronizer = new ImuSynchronizer(new ImuSynchronizer.Sink() {
                    public void sample(long time, float[] values) { processSample(time, values); }
                    public void gap() { sampleCount = stepCount = writeIndex = 0; decision.reset(); bufCount = 0; }
                });
                // Registration and inference share one worker: no concurrent Interpreter calls.
                main.post(() -> {
                    if (stopping) return;
                    boolean a = sensorManager.registerListener(this, accel, 10_000, worker);
                    boolean g = sensorManager.registerListener(this, gyro, 10_000, worker);
                    if (!a || !g) { fail("Không đăng ký được cảm biến 100 Hz"); return; }
                    lastAlignedAt = SystemClock.elapsedRealtime();
                    running = true;
                    starting = false;
                    main.postDelayed(watchdog, 1000);
                });
            } catch (Exception e) { fail("Không khởi động được AI: " + e.getMessage()); }
        });
    }

    private void fail(String message) {
        if (stopping) return;
        stopping = true;
        startupError = message;
        Log.e(TAG, message);
        main.post(() -> {
            Listener current = listener;
            if (current != null) current.onMonitorError(message);
            stopMonitoring();
        });
    }

    private void stopMonitoring() {
        stopping = true;
        main.removeCallbacks(watchdog);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        stopAlarm();
        releaseWakeLock();
        running = false;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        main.removeCallbacks(watchdog);
        worker.post(() -> { if (ai != null) { ai.close(); ai = null; } workerThread.quitSafely(); });
        if (sensorManager != null) sensorManager.unregisterListener(this);
        stopAlarm();
        releaseWakeLock();
        running = false;
        super.onDestroy();
    }

    // ------------------------------------------------------------
    // ĐỌC CẢM BIẾN
    // ------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (stopping || synchronizer == null) return;
        boolean acceleration = event.sensor.getType() == Sensor.TYPE_ACCELEROMETER;
        if (!acceleration && event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        try {
            synchronizer.add(acceleration, event.timestamp, event.values[0], event.values[1], event.values[2]);
        } catch (Exception e) { fail("Lỗi suy luận AI: " + e.getMessage()); }
    }

    private void processSample(long time, float[] values) {
        lastAlignedAt = SystemClock.elapsedRealtime();
        if (bufCount == 0) bufStartAt = System.currentTimeMillis()
                - (SystemClock.elapsedRealtimeNanos() - time) / 1_000_000L;
        for (int c = 0; c < 3; c++) window[writeIndex][c] = values[c] / 9.80665f;
        for (int c = 3; c < 6; c++) window[writeIndex][c] = (float) Math.toDegrees(values[c]);
        bufAx[bufCount] = values[0]; bufAy[bufCount] = values[1]; bufAz[bufCount] = values[2];
        bufGx[bufCount] = window[writeIndex][3];
        bufGy[bufCount] = window[writeIndex][4];
        bufGz[bufCount] = window[writeIndex][5];
        if (++bufCount == BATCH_SIZE) flushBatch();
        writeIndex = (writeIndex + 1) % 50;
        sampleCount++;
        if (sampleCount < 50) return;
        if (sampleCount > 50 && ++stepCount < 10) return;
        stepCount = 0;
        long startedAt = SystemClock.elapsedRealtimeNanos();
        double probability = ai.predict(window, writeIndex);
        lastInferenceMicros = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1000;
        lastProbability = probability;
        lastInferenceElapsed = SystemClock.elapsedRealtime();
        inferenceCount++;
        if (decision.accept(probability)) main.post(() -> detectFall(probability));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Không dùng tới, nhưng giao diện SensorEventListener bắt buộc phải có.
    }

    private void flushBatch() {
        if (bufCount == 0) return;
        Listener current = listener;
        if (current != null) {
            int hz = 100;
            current.onSensorBatch(
                    bufStartAt, hz,
                    Arrays.copyOf(bufAx, bufCount), Arrays.copyOf(bufAy, bufCount), Arrays.copyOf(bufAz, bufCount),
                    Arrays.copyOf(bufGx, bufCount), Arrays.copyOf(bufGy, bufCount), Arrays.copyOf(bufGz, bufCount)
            );
        }
        bufCount = 0;
    }

    // ------------------------------------------------------------
    // DÒ TÉ NGÃ
    // ------------------------------------------------------------

    /** A model candidate starts the separate cancellation/alarm flow. */
    private void detectFall(double probability) {
        if (stopping || !running || alarmActive) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastDetectionAt < DETECT_COOLDOWN_MS) return;
        lastDetectionAt = now;
        alarmActive = true;
        startAlarm();
        showAlarmNotification();
        Listener current = listener;
        if (current != null) current.onFallDetected(probability);
    }

    // ------------------------------------------------------------
    // CÒI BÁO ĐỘNG
    // ------------------------------------------------------------

    /**
     * Dùng chuông báo thức mặc định của máy: to, đi theo âm lượng báo thức nên
     * vẫn kêu khi máy để chế độ im lặng, và không cần đóng gói thêm file âm thanh.
     */
    private void startAlarm() {
        if (alarmMuted || alarmPlayer != null) return;

        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) return;

            alarmPlayer = new MediaPlayer();
            alarmPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            alarmPlayer.setDataSource(this, uri);
            alarmPlayer.setLooping(true);
            alarmPlayer.prepare();
            alarmPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Không phát được chuông báo động", e);
            releaseAlarmPlayer();
        }

        vibrateAlarm();
    }

    private void stopAlarm() {
        if (alarmActive) lastDetectionAt = SystemClock.elapsedRealtime();
        alarmActive = false;
        releaseAlarmPlayer();
        if (vibrator != null) vibrator.cancel();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(ALARM_NOTIFICATION_ID);
    }

    private void releaseAlarmPlayer() {
        if (alarmPlayer == null) return;
        try {
            if (alarmPlayer.isPlaying()) alarmPlayer.stop();
        } catch (Exception ignored) {
            // Trình phát có thể đã ở trạng thái không hợp lệ — kệ, vẫn giải phóng.
        }
        alarmPlayer.release();
        alarmPlayer = null;
    }

    private void vibrateAlarm() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = { 0, 500, 300, 500, 300, 500 };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    // ------------------------------------------------------------
    // THÔNG BÁO
    // ------------------------------------------------------------

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // Thông báo thường trực: để im lặng, chỉ cần cho người dùng biết app đang chạy.
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR, "Giám sát té ngã", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Thông báo thường trực trong lúc ứng dụng đang giám sát cảm biến.");
        monitor.setShowBadge(false);
        manager.createNotificationChannel(monitor);

        // Kênh cảnh báo: mức cao nhất để hiện đè lên màn hình khoá.
        NotificationChannel alarm = new NotificationChannel(
                CHANNEL_ALARM, "Cảnh báo té ngã", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("Báo động khi phát hiện té ngã.");
        alarm.enableVibration(true);
        manager.createNotificationChannel(alarm);
    }

    private void startForegroundWithNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_MONITOR)
                .setContentTitle("Fall Guard đang bảo vệ bạn")
                .setContentText("Đang theo dõi cảm biến, kể cả khi màn hình tắt.")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(openAppIntent(false))
                .build();

        int type = 0;
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 bắt buộc khai báo loại dịch vụ. Dò cảm biến té ngã không
            // khớp hẳn loại nào có sẵn nên dùng "specialUse" đúng như tài liệu Google.
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
    }

    /**
     * Thông báo toàn màn hình: đánh thức và mở app lên ngay cả khi đang khoá máy,
     * để người dùng kịp bấm "Tôi ổn" trong lúc đếm ngược.
     */
    private void showAlarmNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ALARM)
                .setContentTitle("⚠️ Phát hiện té ngã")
                .setContentText("Mở ứng dụng để huỷ nếu bạn vẫn ổn.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(true))
                .setFullScreenIntent(openAppIntent(true), true)
                .build();

        manager.notify(ALARM_NOTIFICATION_ID, notification);
    }

    private PendingIntent openAppIntent(boolean highPriority) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, highPriority ? 1 : 0, intent, flags);
    }

    // ------------------------------------------------------------
    // WAKE LOCK
    // ------------------------------------------------------------

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        // PARTIAL_WAKE_LOCK giữ CPU chạy nhưng KHÔNG bật màn hình — đúng thứ cần
        // để đọc cảm biến trong lúc điện thoại nằm im trong túi.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FallGuard::SensorMonitor");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
}
