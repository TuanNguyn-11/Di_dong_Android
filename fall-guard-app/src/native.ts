import { registerPlugin, Capacitor } from '@capacitor/core';
import { Geolocation } from '@capacitor/geolocation';
import { Device } from '@capacitor/device';

// Capacitor plugins are explicitly registered/imported so Android and browser builds agree.

export interface GeoFix {
  latitude: number;
  longitude: number;
  accuracy: number | null;
}

interface CapacitorPosition {
  coords: { latitude: number; longitude: number; accuracy?: number };
}

interface CapacitorGeolocationPlugin {
  requestPermissions: () => Promise<{ location?: string; coarseLocation?: string }>;
  watchPosition: (
    options: { enableHighAccuracy?: boolean; timeout?: number; maximumAge?: number },
    callback: (position: CapacitorPosition | null, err?: unknown) => void
  ) => Promise<string>;
  clearWatch: (options: { id: string }) => Promise<void>;
}

interface CapacitorDevicePlugin {
  getBatteryInfo: () => Promise<{ batteryLevel?: number; isCharging?: boolean }>;
}

/** Một lô mẫu cảm biến do dịch vụ nền native gửi lên */
export interface SensorBatch {
  /** Mốc thời gian (ms) của mẫu ĐẦU TIÊN trong lô */
  t0: number;
  /** Tần số lấy mẫu thực tế của lô này */
  hz: number;
  ax: number[];
  ay: number[];
  az: number[];
  gx: number[];
  gy: number[];
  gz: number[];
}

export interface PluginListenerHandle {
  remove: () => Promise<void>;
}

/**
 * Plugin native tự viết, nằm ở android/app/src/main/java/com/fallguard/monitor/.
 * Nó chạy một Foreground Service đọc cảm biến bằng SensorManager nên vẫn hoạt
 * động khi màn hình đã tắt — điều mà WebView không tự làm được.
 */
export interface FallGuardSensorPlugin {
  isAvailable: () => Promise<AiDiagnostics>;
  start: (options: { alarmMuted: boolean }) => Promise<void>;
  stop: () => Promise<void>;
  setAlarmMuted: (options: { muted: boolean }) => Promise<void>;
  startAlarm: () => Promise<void>;
  stopAlarm: () => Promise<void>;
  requestBatteryExemption: () => Promise<{ granted: boolean }>;
  addListener: {
    (event: 'monitorError', cb: (data: { message: string }) => void): Promise<PluginListenerHandle>;
    (event: 'sensorBatch', cb: (data: SensorBatch) => void): Promise<PluginListenerHandle>;
    (event: 'fallDetected', cb: (data: { probability: number; source: 'model' }) => void): Promise<PluginListenerHandle>;
  };
}

export interface AiDiagnostics {
  schemaVersion: number;
  source: string;
  available: boolean;
  running: boolean;
  modelName: string;
  modelSha256: string;
  sessionId: string;
  inferenceCount: number;
  lastInferenceMicros: number;
  probability: number;
  inferenceAgeMs: number;
  goldenPassed: number;
}

interface CapacitorGlobal {
  isNativePlatform?: () => boolean;
  Plugins?: {
    Geolocation?: CapacitorGeolocationPlugin;
    Device?: CapacitorDevicePlugin;
    FallGuardSensor?: FallGuardSensorPlugin;
  };
}

const sensorPlugin = registerPlugin<FallGuardSensorPlugin>('FallGuardSensor');
function capacitor(): CapacitorGlobal {
  return { isNativePlatform: () => Capacitor.isNativePlatform(), Plugins: { Geolocation, Device } };
}

export function getSensorPlugin(): FallGuardSensorPlugin | undefined {
  return Capacitor.isNativePlatform() ? sensorPlugin : undefined;
}

/** App có đang chạy trong vỏ native Android (Capacitor) không? */
export function isNativePlatform(): boolean {
  const cap = capacitor();
  return typeof cap?.isNativePlatform === 'function' && cap.isNativePlatform();
}

// ------------------------------------------------------------
// GPS
// ------------------------------------------------------------

export interface GeoWatchHandle {
  stop: () => void;
}

/**
 * Theo dõi vị trí liên tục. Trả về handle để tắt khi không cần nữa.
 * Tự chọn plugin native nếu có, không thì dùng Geolocation API của trình duyệt.
 */
export async function watchLocation(
  onFix: (fix: GeoFix) => void,
  onError: (message: string) => void
): Promise<GeoWatchHandle> {
  const plugin = capacitor()?.Plugins?.Geolocation;

  if (isNativePlatform() && plugin) {
    try {
      const permission = await plugin.requestPermissions();
      if (permission.location !== 'granted' && permission.coarseLocation !== 'granted') {
        onError('Bạn chưa cho phép ứng dụng truy cập vị trí.');
        return { stop: () => undefined };
      }

      const watchId = await plugin.watchPosition(
        { enableHighAccuracy: true, timeout: 15000, maximumAge: 5000 },
        (position, err) => {
          if (err || !position) {
            onError('Không lấy được vị trí GPS.');
            return;
          }
          onFix({
            latitude: position.coords.latitude,
            longitude: position.coords.longitude,
            accuracy: position.coords.accuracy ?? null,
          });
        }
      );

      return {
        stop: () => {
          void plugin.clearWatch({ id: watchId }).catch(() => undefined);
        },
      };
    } catch {
      // Plugin lỗi — rơi xuống dùng API trình duyệt bên dưới.
    }
  }

  if (!('geolocation' in navigator)) {
    onError('Thiết bị không hỗ trợ định vị.');
    return { stop: () => undefined };
  }

  const handleSuccess = (pos: GeolocationPosition) =>
    onFix({
      latitude: pos.coords.latitude,
      longitude: pos.coords.longitude,
      accuracy: pos.coords.accuracy ?? null,
    });

  const handleError = (error: GeolocationPositionError) => {
    if (error.code === error.PERMISSION_DENIED) {
      onError('Bạn chưa cho phép ứng dụng truy cập vị trí.');
    } else {
      onError('Không lấy được vị trí GPS.');
    }
  };

  navigator.geolocation.getCurrentPosition(handleSuccess, handleError, {
    enableHighAccuracy: true,
    timeout: 15000,
  });

  const watchId = navigator.geolocation.watchPosition(handleSuccess, handleError, {
    enableHighAccuracy: true,
    maximumAge: 5000,
  });

  return { stop: () => navigator.geolocation.clearWatch(watchId) };
}

// ------------------------------------------------------------
// PIN
// ------------------------------------------------------------

/** Phần trăm pin 0-100, hoặc null nếu không đọc được */
export async function readBatteryLevel(): Promise<number | null> {
  const devicePlugin = capacitor()?.Plugins?.Device;
  if (devicePlugin) {
    try {
      const info = await devicePlugin.getBatteryInfo();
      if (typeof info.batteryLevel === 'number') return Math.round(info.batteryLevel * 100);
    } catch {
      // Rơi xuống Battery Status API của trình duyệt.
    }
  }

  const nav = navigator as unknown as { getBattery?: () => Promise<{ level: number }> };
  if (typeof nav.getBattery !== 'function') return null;
  try {
    const battery = await nav.getBattery();
    return Math.round(battery.level * 100);
  } catch {
    return null;
  }
}

// ------------------------------------------------------------
// GIÁM SÁT NỀN BẰNG DỊCH VỤ NATIVE
// ------------------------------------------------------------

export interface NativeMonitorHandle {
  stop: () => void;
}

/**
 * Bật dịch vụ nền đọc cảm biến. Trả về null khi không có plugin (đang chạy trên
 * trình duyệt), để nơi gọi tự quay về dùng cảm biến của WebView.
 *
 * `onBatch` nhận từng lô mẫu; `onFall` bắn sau hai cửa sổ AI đạt ngưỡng — kể cả lúc màn hình tắt và JavaScript đang bị hệ điều hành bóp.
 */
export async function startNativeMonitor(
  options: { alarmMuted: boolean },
  onBatch: (batch: SensorBatch) => void,
  onFall: (probability: number) => void,
  onError: (message: string) => void,
  onDiagnostics?: (value: AiDiagnostics) => void
): Promise<NativeMonitorHandle | null> {
  const plugin = getSensorPlugin();
  if (!isNativePlatform() || !plugin) return null;

  const handles: PluginListenerHandle[] = [];
  try {
    handles.push(await plugin.addListener('monitorError', (data) => onError(data.message)));
    handles.push(await plugin.addListener('sensorBatch', onBatch));
    handles.push(await plugin.addListener('fallDetected', (data) => onFall(data.probability)));
    const availability = await plugin.isAvailable();
    if (!availability.available) throw new Error('Điện thoại cần có cả gia tốc kế và con quay hồi chuyển để chạy AI.');
    await plugin.start(options);
  } catch (error) {
    await Promise.all(handles.map((h) => h.remove().catch(() => undefined)));
    throw error;
  }

  let active = true;
  let reading = false;
  const poll = async () => {
    if (!active || reading || !onDiagnostics) return;
    reading = true;
    try {
      const value = await plugin.isAvailable();
      if (active && value.schemaVersion === 1) onDiagnostics(value);
    } catch { /* No fabricated diagnostics when native bridge is unavailable. */ }
    finally { reading = false; }
  };
  void poll();
  const diagnosticTimer = window.setInterval(() => void poll(), 1000);
  return {
    stop: () => {
      active = false;
      window.clearInterval(diagnosticTimer);
      void plugin.stop().catch(() => undefined);
      handles.forEach((h) => void h.remove().catch(() => undefined));
    },
  };
}

/** Mở trang xin bỏ giới hạn tiết kiệm pin của Android */
export async function requestBatteryExemption(): Promise<boolean> {
  const plugin = getSensorPlugin();
  if (!plugin) return false;
  try {
    const result = await plugin.requestBatteryExemption();
    return result.granted === true;
  } catch {
    return false;
  }
}
