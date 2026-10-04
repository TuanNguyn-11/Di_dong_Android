# Fall Guard AI — build và cài Android

App Capacitor 7 chạy `dilated_aug_s0_int8.tflite` trực tiếp trong dịch vụ Java.
Model được đóng gói trong APK, không cần tải model hay gọi máy chủ AI. Đăng nhập,
GPS và gửi sự kiện cho người thân vẫn phụ thuộc quyền thiết bị, Firebase và mạng.

## Cài APK đã build

File bàn giao: `artifacts/fall-guard-ai-debug.apk`.

1. Chép APK sang điện thoại Android 6 trở lên.
2. Mở APK bằng ứng dụng quản lý file; cho phép ứng dụng đó cài ứng dụng từ nguồn này.
3. Chọn **Cài đặt**, rồi mở **Fall Guard**.
4. Đăng nhập tài khoản người dùng thiết bị. Cấp quyền vị trí và thông báo.
5. Bật giám sát. Điện thoại cần có cả gia tốc kế và con quay hồi chuyển.
6. Đặt app ở chế độ pin không hạn chế nếu cần kiểm tra khi khóa màn hình.

Nếu Android báo xung đột chữ ký với bản đã cài, sử dụng APK cùng khóa ký với bản
cũ, hoặc chủ động gỡ bản cũ rồi cài lại (gỡ app làm mất dữ liệu cục bộ).
Đây là APK debug ký bằng khóa debug của máy build, dành cho cài thử; chưa phải
bản release đã ký bằng khóa phát hành của nhóm.

Qua USB, sau khi bật USB debugging và chấp nhận kết nối:

```powershell
adb install -r .\artifacts\fall-guard-ai-debug.apk
```

## Build bằng Android Studio

- Node.js >= 20; máy thực hiện dùng Node 24.
- JDK 21; máy này có tại `C:\DDisk\Apps\AndroidStudio\AppAS\jbr`.
- Android Studio Ladybug 2024.2.1 trở lên, SDK Platform 35.
- Dự án Android đã tồn tại, **không chạy lại `cap add android`**.

Mở PowerShell tại `web/fall-guard-app`:

```powershell
npm ci
npm run sync
npm run open:android
```

Trong Android Studio:

1. Mở thư mục `fall-guard-app/android` nếu chưa mở.
2. Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle JDK: 21**.
3. Đợi Gradle Sync; cài SDK 35 nếu được yêu cầu.
4. Chọn build variant **debug**.
5. Build → Generate App Bundles or APKs → Generate APKs
   (một số bản ghi Build Bundle(s) / APK(s) → Build APK(s)).
6. Lấy APK tại `android/app/build/outputs/apk/debug/app-debug.apk`.

Sau mỗi lần đổi TypeScript/HTML/CSS, chạy lại `npm run sync` trước khi build Android.
Không chỉnh `www/app.js` vì đây là file sinh tự động.

## Build bằng script

```powershell
npm ci
.\scripts\build-apk.ps1 -JdkHome 'C:\DDisk\Apps\AndroidStudio\AppAS\jbr'
```

Script kiểm tra TypeScript, bundle web, sync Capacitor, chạy unit test, build APK,
chép sang `artifacts/fall-guard-ai-debug.apk` và tạo file SHA-256 kèm theo.
Nếu có máy ảo/điện thoại test nối ADB, thêm `-TestOnDevice` để chạy instrumentation
test (Gradle có thể gỡ app test và app được kiểm thử sau khi chạy; không dùng trên
điện thoại có dữ liệu cần giữ).

Hoặc dùng script npm có sẵn sau khi chọn Java 21:

```powershell
$env:JAVA_HOME = 'C:\DDisk\Apps\AndroidStudio\AppAS\jbr'
npm run apk
```

## Model và luồng dữ liệu

- Assets: `android/app/src/main/assets/models/`.
- `FallAiEngine.java`: SHA-256, tensor INT8, mean/std, lượng tử hóa nearest-even,
  suy luận LiteRT 1.4.2 với XNNPACK, giải lượng tử logits và sigmoid.
- `ImuSynchronizer.java`: ghép hai luồng theo timestamp đơn điệu, nội suy trên lưới
  100 Hz; reset cửa sổ khi khoảng trống trên 30 ms, sai thứ tự hoặc mẫu không hữu hạn.
- `FallGuardSensorService.java`: yêu cầu cảm biến mỗi 10 ms; giữ 50 mẫu, suy luận
  lần đầu ở mẫu 50 và mỗi 10 mẫu mới; worker riêng để không chặn UI.
- `FallDecision.java`: `pFall >= 0.7` trong 2 cửa sổ liên tiếp.
- `FallGuardSensorPlugin.java` và `src/native.ts`: truyền xác suất/lỗi/trạng thái;
  khởi động chỉ thành công sau khi model và cảm biến sẵn sàng.
- `src/app.ts`: native AI là nguồn phát hiện duy nhất; trình duyệt chỉ mô phỏng
  dữ liệu hiển thị. Sự kiện AI ghi `source: sensor`, demo ghi `source: demo`.

Gia tốc gồm trọng lực: m/s² → g bằng chia 9.80665; gyro: rad/s → độ/s.
Không tự đảo/hoán vị trục vì chưa có đo đạc chứng minh hướng lắp tương đương KFall.
Model nhận ứng viên trước va chạm; bản app dùng ứng viên này để mở đếm ngược hủy
cảnh báo hiện có (mặc định 10 giây). Chưa thêm bộ xác nhận bất động vì gói model
không cung cấp ngưỡng hay thời gian bất động đã kiểm chứng.

## Kiểm thử và giới hạn

Mỗi lần dịch vụ khởi động, app chạy 10 golden vector tổng hợp. Phải khớp chính xác
INT8 và xác suất mới bật cảm biến. Sai SHA, tensor, golden hoặc thiếu cảm biến sẽ
báo lỗi; không tự chuyển sang AI giả lập. Logcat tag: `FallGuardSensor`.

Instrumentation tests kiểm tra golden, dữ liệu vật lý→INT8→model, thứ tự bộ đệm,
làm tròn/clipping/NaN, quyết định 2 cửa sổ và đồng bộ timestamp/reset khi mất mẫu.

Model train trên KFall với cảm biến vùng thắt lưng. Golden chỉ kiểm tra tính đúng
của tích hợp, không chứng minh độ chính xác với điện thoại thực tế. Cần đo tần số,
hướng trục, vị trí đeo và kiểm thử hoạt động hằng ngày trên thiết bị đích.
100 Hz là lưới nội suy đầu vào; tốc độ phần cứng thực tế phụ thuộc điện thoại.

Suy luận và còi chạy trong native foreground service. Đếm ngược/gửi Firebase vẫn
ở WebView; dùng deadline thực để không kéo dài đếm ngược khi JS tạm dừng, nhưng
không bảo đảm gửi cloud đúng hạn nếu hệ điều hành treo/diệt WebView. Chưa có hàng
đợi cloud native/offline. Kiểm tra phần này trên điện thoại trước khi dùng thực tế.

Firebase: xem `../FIREBASE-SETUP.md`; không cần thay cấu hình chỉ để nạp model.
Không tạo tài khoản hay gửi cảnh báo thật khi chạy bộ kiểm thử số học.

Tài liệu chính thức:
- https://capacitorjs.com/docs/updating/7-0
- https://developers.google.com/edge/litert/android
- https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- https://developer.android.com/studio/run

Xem phần giới hạn hiện tại trong [README dự án](../README.md) khi đánh giá ứng dụng.
