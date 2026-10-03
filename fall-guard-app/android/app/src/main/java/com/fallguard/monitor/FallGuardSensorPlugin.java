package com.fallguard.monitor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

/**
 * Cầu nối giữa FallGuardSensorService (Java) và app.ts (TypeScript).
 *
 * Bên TypeScript gọi qua `window.Capacitor.Plugins.FallGuardSensor`, xem khai báo
 * kiểu trong src/native.ts. Trên trình duyệt thường không có plugin này, nên mọi
 * chỗ gọi bên TypeScript đều phải chịu được trường hợp `undefined`.
 */
@CapacitorPlugin(
        name = "FallGuardSensor",
        permissions = {
                @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
        }
)
public class FallGuardSensorPlugin extends Plugin implements FallGuardSensorService.Listener {

    @Override
    public void load() {
        FallGuardSensorService.setListener(this);
    }

    @Override
    protected void handleOnDestroy() {
        FallGuardSensorService.setListener(null);
        super.handleOnDestroy();
    }

    // ------------------------------------------------------------
    // CÁC HÀM TYPESCRIPT GỌI SANG
    // ------------------------------------------------------------

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject result = new JSObject();
        SensorManager manager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        result.put("available", manager != null && manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                && manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null);
        result.put("running", FallGuardSensorService.isRunning());
        result.put("inferenceCount", FallGuardSensorService.getInferenceCount());
        result.put("lastInferenceMicros", FallGuardSensorService.getLastInferenceMicros());
        result.put("modelSha256", FallAiEngine.SHA256);
        result.put("schemaVersion", 1);
        result.put("source", "native-litert");
        result.put("modelName", "dilated_aug_s0_int8.tflite");
        result.put("probability", FallGuardSensorService.getLastProbability());
        result.put("inferenceAgeMs", FallGuardSensorService.getInferenceAgeMs());
        result.put("goldenPassed", FallGuardSensorService.getGoldenPassed());
        result.put("sessionId", FallGuardSensorService.getSessionId());
        call.resolve(result);
    }

    @PluginMethod
    public void start(PluginCall call) {
        // Android 13+ cần quyền mới hiện được thông báo thường trực. Không có
        // quyền thì dịch vụ vẫn chạy, chỉ là người dùng không thấy thông báo.
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != com.getcapacitor.PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "afterNotificationPermission");
            return;
        }
        startService(call);
    }

    @com.getcapacitor.annotation.PermissionCallback
    private void afterNotificationPermission(PluginCall call) {
        // Dù người dùng từ chối thông báo, vẫn phải bật giám sát — đó mới là
        // chức năng chính, thông báo chỉ là phần hiển thị.
        startService(call);
    }

    private void startService(PluginCall call) {
        boolean muted = Boolean.TRUE.equals(call.getBoolean("alarmMuted", false));

        Intent intent = new Intent(getContext(), FallGuardSensorService.class);
        intent.setAction(FallGuardSensorService.ACTION_START);
        intent.putExtra(FallGuardSensorService.EXTRA_MUTED, muted);

        try {
            FallGuardSensorService.clearStartupError();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
            awaitReady(call, 0);
        } catch (Exception e) {
            call.reject("Không khởi động được dịch vụ nền: " + e.getMessage());
        }
    }

    private void awaitReady(PluginCall call, int attempt) {
        String error = FallGuardSensorService.getStartupError();
        if (error != null) { call.reject(error); return; }
        if (FallGuardSensorService.isRunning()) { call.resolve(); return; }
        if (attempt >= 150) {
            getContext().stopService(new Intent(getContext(), FallGuardSensorService.class));
            call.reject("Hết thời gian khởi động AI"); return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> awaitReady(call, attempt + 1), 100);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), FallGuardSensorService.class));
        call.resolve();
    }

    @PluginMethod
    public void setAlarmMuted(PluginCall call) {
        boolean muted = Boolean.TRUE.equals(call.getBoolean("muted", false));
        if (!FallGuardSensorService.isRunning()) { call.resolve(); return; }
        Intent intent = new Intent(getContext(), FallGuardSensorService.class);
        intent.setAction(FallGuardSensorService.ACTION_START);
        intent.putExtra(FallGuardSensorService.EXTRA_MUTED, muted);
        startServiceCompat(intent);
        call.resolve();
    }

    @PluginMethod
    public void startAlarm(PluginCall call) {
        sendAction(FallGuardSensorService.ACTION_START_ALARM);
        call.resolve();
    }

    @PluginMethod
    public void stopAlarm(PluginCall call) {
        sendAction(FallGuardSensorService.ACTION_STOP_ALARM);
        call.resolve();
    }

    /**
     * Mở màn hình xin bỏ giới hạn tiết kiệm pin.
     * Nhiều dòng máy (Xiaomi, Oppo, Samsung...) tự giết dịch vụ nền sau vài phút
     * nếu app không nằm trong danh sách miễn trừ.
     */
    @PluginMethod
    public void requestBatteryExemption(PluginCall call) {
        JSObject result = new JSObject();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.put("granted", true);
            call.resolve(result);
            return;
        }

        Context context = getContext();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        String pkg = context.getPackageName();

        if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
            result.put("granted", true);
            call.resolve(result);
            return;
        }

        try {
            // Mở đúng trang cài đặt; không tự ý bật hộ được, người dùng phải đồng ý.
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.put("granted", false);
            result.put("opened", true);
        } catch (Exception e) {
            result.put("granted", false);
            result.put("opened", false);
            result.put("error", e.getMessage());
        }
        call.resolve(result);
    }

    // ------------------------------------------------------------
    // SỰ KIỆN TỪ DỊCH VỤ BẮN NGƯỢC SANG TYPESCRIPT
    // ------------------------------------------------------------

    @Override
    public void onSensorBatch(long t0, int hz, float[] ax, float[] ay, float[] az,
                              float[] gx, float[] gy, float[] gz) {
        JSObject data = new JSObject();
        data.put("t0", t0);
        data.put("hz", hz);
        data.put("ax", toJsArray(ax));
        data.put("ay", toJsArray(ay));
        data.put("az", toJsArray(az));
        data.put("gx", toJsArray(gx));
        data.put("gy", toJsArray(gy));
        data.put("gz", toJsArray(gz));
        notifyListeners("sensorBatch", data);
    }

    @Override
    public void onFallDetected(double probability) {
        JSObject data = new JSObject();
        data.put("probability", probability);
        data.put("source", "model");
        notifyListeners("fallDetected", data, true);
    }

    @Override
    public void onMonitorError(String message) {
        JSObject data = new JSObject(); data.put("message", message);
        notifyListeners("monitorError", data, true);
    }

    // ------------------------------------------------------------
    // TIỆN ÍCH
    // ------------------------------------------------------------

    private JSArray toJsArray(float[] values) {
        JSArray array = new JSArray();
        for (float value : values) {
            // Làm tròn 3 chữ số: đủ để vẽ dạng sóng, mà nhẹ hơn khi qua cầu nối.
            double rounded = Math.round(value * 1000f) / 1000.0;

            // JSON không biểu diễn được NaN / vô cực. Cảm biến hỏng có thể trả về
            // những giá trị đó; đổi thành 0 để không làm hỏng cả lô dữ liệu.
            if (Double.isNaN(rounded) || Double.isInfinite(rounded)) rounded = 0.0;

            // Dùng put(Object) chứ không phải put(double): bản nhận double khai
            // báo ném JSONException, mà hàm này được gọi từ interface không cho ném.
            array.put((Object) rounded);
        }
        return array;
    }

    private void sendAction(String action) {
        if (!FallGuardSensorService.isRunning()) return;
        Intent intent = new Intent(getContext(), FallGuardSensorService.class);
        intent.setAction(action);
        startServiceCompat(intent);
    }

    private void startServiceCompat(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
        } catch (Exception ignored) {
            // Dịch vụ có thể đã dừng — không có gì để làm thêm.
        }
    }
}
