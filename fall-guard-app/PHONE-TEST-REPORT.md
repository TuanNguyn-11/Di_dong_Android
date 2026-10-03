# Kiểm tra điện thoại thật — 03/10/2026

Thiết bị: CPH2363 (OPPO), Android 14 / API 34. Kết nối ADB qua USB.
APK: `artifacts/fall-guard-ai-debug.apk`, SHA-256
`c4d436c5d368399143d06770114c7b5bd021a661f7bfd5358f445acc76238ba0`.

## Đã đạt

- [x] Cập nhật APK bằng `adb install -r`; không gỡ app chính hay xóa dữ liệu.
- [x] App mở được, người dùng đăng nhập; phiên đăng nhập còn sau khi khởi động lại.
- [x] Thiết bị có accelerometer và gyroscope TDK-Invensense `icm4x6xa`.
- [x] Model SHA-256 đúng; runtime ARM chạy 10/10 golden vector khớp chính xác.
- [x] Chạy trực tiếp 6 instrumentation tests: **OK (6 tests)**, gồm golden,
  preprocessing, bộ đệm vòng, quyết định liên tiếp, đồng bộ/reset timestamp và package.
- [x] Dữ liệu trả về WebView theo lô 25 mẫu, lưới đồng bộ 100 Hz.
- [x] Đo foreground: 1.100 mẫu, 107 lần suy luận, không lỗi, không ứng viên té ngã.
- [x] Khi Android báo `mWakefulness=Asleep`, foreground service và partial wake lock
  vẫn tồn tại; tổng đạt 6.400 mẫu và 637 lần suy luận, không lỗi, không ứng viên té ngã.
  So với mốc trước khóa: tăng 530 lần suy luận trong khoảng 53 giây.
- [x] Dừng qua plugin: `running: false`; không còn service cảm biến hoặc wake lock.
- [x] Quyền vị trí được cấp; lấy vị trí mới sau khoảng 3,6 giây, độ chính xác báo về
  25,786 m. Không lưu tọa độ vào báo cáo.
- [x] Quyền thông báo được cấp ở profile đang sử dụng.
- [x] Plugin pin đọc được mức 58–59% và trạng thái đang sạc.
- [x] Thử chuông/rung native cục bộ 2 giây, dừng thành công; người dùng xác nhận
  **có cả chuông và rung**.
- [x] Log của tiến trình app trong lần kiểm tra cuối không có lỗi AndroidRuntime;
  có `AI golden PASS 10/10`.

## Phát hiện cấu hình cần chú ý

- Quyền `USE_FULL_SCREEN_INTENT` ban đầu trả về **deny**. Sau khi người dùng bật,
  đã kiểm tra lại bằng ADB và xác nhận **allow**. Chưa phát cảnh báo toàn màn hình
  giả để đánh giá toàn bộ luồng UI khi khóa máy.
- App chưa nằm trong danh sách miễn tối ưu pin được ADB liệt kê. Bài thử khóa
  màn hình ngắn vẫn đạt, nhưng chưa kiểm tra bị hệ điều hành hạn chế trong nhiều giờ.

## Chưa kết luận

- [ ] Hiện màn hình hủy cảnh báo toàn màn hình thực tế khi khóa máy.
- [ ] Countdown → ghi Firebase → người thân nhận cảnh báo, SMS/email/cuộc gọi.
  Không chủ động gửi cảnh báo thật trong đợt kiểm tra này.
- [ ] Độ chính xác phát hiện té ngã ngoài thực tế, trục/vị trí đeo so với KFall.
  Người dùng đặt máy yên; không yêu cầu tự ngã để thử.
- [ ] Pin/độ ổn định dài hạn, khi rút USB, mất mạng, hệ điều hành dừng tiến trình.
- [ ] Gửi cloud đúng hạn khi WebView bị treo/diệt: kiến trúc hiện tại chưa bảo đảm;
  AI/chuông ở native nhưng countdown/Firebase vẫn ở WebView.

Trong bài kiểm tra tách biệt, giám sát trên giao diện được tắt trước khi gọi dịch vụ
native, nhằm không đi vào luồng gửi cảnh báo. Không thay đổi model, không nới ngưỡng
golden, không tạo tài khoản hoặc cảnh báo giả lên Firebase.

Trạng thái bàn giao: app chính còn cài và đăng nhập, mở ở trang chủ; **giám sát đang
tắt**, người dùng tự bật lại khi sử dụng. Dịch vụ cảm biến/wake lock đã dừng.
Gói kiểm thử `com.fallguard.monitor.test` đã được gỡ; dữ liệu app chính được giữ.

## Bằng chứng

Trong `artifacts/phone-check/`:

- `instrumentation.txt`: 6 tests đạt trên điện thoại.
- `foreground.json`, `screen-off.json`: số mẫu, số inference, lỗi và số ứng viên.
- `gps.json`: kết quả GPS đã loại tọa độ.
- `alarm.json`: lệnh bật/dừng chuông cục bộ.
- `stop.json`: trạng thái dịch vụ đã dừng.
- `app-log.txt`: log giới hạn theo tiến trình và các tag của bài kiểm tra.

Script kiểm tra: `scripts/device-check.mjs`; chỉ dùng với APK debug và ADB forward
port 9223 tới socket `webview_devtools_remote_<pid>` của app. Các mode:
`status`, `prepare`, `gps`, `start`, `sample`, `stop`, `alarm`.
