# Checklist tích hợp AI và APK — 03/10/2026

## Đã thực hiện

- [x] Đối chiếu model trong ZIP với assets: 51.120 byte, SHA-256
  `436a3463ee4802aa960c777775b680d3f9fc50a1c5798b0204a5c8c91dff0f11`.
- [x] Đóng gói `.tflite`, thông số chuẩn hóa và 10 golden vector trong APK.
- [x] Tích hợp LiteRT 1.4.2, CPU/XNNPACK; kiểm tra SHA, tensor và golden khi bật AI.
- [x] Đổi m/s²→g, rad/s→độ/s; đúng thứ tự 6 kênh, mean/std, clipping, nearest-even INT8.
- [x] Đồng bộ timestamp accelerometer/gyroscope trên lưới 100 Hz; reset khi mất mẫu.
- [x] Cửa sổ 50 mẫu, chạy mỗi 10 mẫu mới trên worker riêng.
- [x] Giải lượng tử logits, sigmoid; ngưỡng 0,7 trong hai cửa sổ liên tiếp.
- [x] Nối xác suất AI qua plugin Capacitor vào màn hình cảnh báo và luồng hủy hiện có.
- [x] Bỏ phát hiện bằng AI mock/ngưỡng trong TypeScript; tránh cảnh báo trùng.
- [x] Báo lỗi và tắt giám sát khi model/cảm biến không sẵn sàng, không chuyển sang mock.
- [x] Sự kiện AI dùng `source: sensor`, không còn gắn mặc định `demo`.
- [x] Không hiển thị gửi thành công khi thao tác ghi Firebase thất bại.
- [x] Đếm ngược theo deadline thực để không kéo dài nếu WebView bị tạm dừng.
- [x] Thêm plugin GPS/pin Capacitor 7 và đăng ký plugin đúng cách.
- [x] Bỏ cấu hình ép Java 17; dùng JDK 21 của Android Studio và SDK 35.
- [x] Giữ mã Android tùy chỉnh và HTML/CSS trong phạm vi Git; loại trừ file build.
- [x] Tạo script build `scripts/build-apk.ps1`, tài liệu `README-ANDROID.md`.
- [x] Build APK debug, tạo bản bàn giao trong `artifacts/` và checksum SHA-256.

## Kiểm chứng đã đạt

- [x] TypeScript typecheck, esbuild và Capacitor sync.
- [x] Gradle `:app:assembleDebug` và `:app:testDebugUnitTest`.
- [x] Android Lint: 0 errors, 21 warnings (chi tiết trong báo cáo lint; gồm tài nguyên,
  phiên bản dependency, wake lock và thiết lập tối ưu pin).
- [x] 6 instrumentation tests trên Pixel_4 AVD, Android 17/API 37, x86_64:
  kiểm tra package, golden, preprocessing, quyết định liên tiếp, đồng bộ/mất mẫu,
  và cửa sổ dữ liệu vật lý qua bộ đệm vòng đến output model.
- [x] 10/10 golden vector khớp chính xác từng output INT8 và xác suất Python.
- [x] Cài APK trên máy ảo, mở được trang đăng nhập.
- [x] `apksigner verify`: chữ ký APK hợp lệ (v1/v2). APK chứa runtime ARM64,
  ARMv7, x86, x86_64 và web bundle khớp bản build cuối.
- [x] Kiểm tra cầu nối WebView→Java→WebView: dịch vụ chạy và trả lô 25 mẫu/100 Hz.
- [x] Trong một phiên kiểm tra 3,5 giây: 31 lần suy luận, không có lỗi dịch vụ.
  Đây là kiểm tra máy ảo, không phải benchmark điện thoại hay đo độ chính xác.
- [x] Dừng qua plugin thành công; trạng thái native trả về `running: false`.

Lần thử đầu với XNNPACK tắt có sai khác 1 mức INT8. Cấu hình cuối bật XNNPACK
phù hợp runtime Python tạo golden, đạt đối chiếu chính xác; không nới tolerance
và không sửa các giá trị expected trong bộ mẫu.

## Chưa kiểm chứng / cần làm trên điện thoại đích

- [x] Cài và chạy trên điện thoại CPH2363, Android 14 qua USB; giữ dữ liệu hiện có.
- [x] Runtime ARM: 6 tests đạt, 10/10 golden khớp; cảm biến thật và khóa màn hình ngắn đạt.
- [ ] Kiểm chứng hướng trục và vị trí đeo thắt lưng với dữ liệu KFall.
- [ ] Đánh giá dữ liệu hoạt động thực, báo giả/bỏ sót, pin và độ trễ trên điện thoại.
- [x] Đăng nhập trên điện thoại, lấy GPS và pin; chuông/rung được người dùng xác nhận.
- [ ] Ghép người thân và xác nhận cảnh báo Firebase bằng tài khoản thật.
- [ ] Khóa màn hình lâu, tiết kiệm pin, mất mạng và tiến trình bị hệ điều hành dừng.
- [ ] Bản release với keystore phát hành của nhóm (bản hiện tại là debug để cài thử).

Model là bộ nhận diện ứng viên trước va chạm. Chưa bổ sung bước xác nhận bất động
vì không có tham số đã kiểm chứng trong gói bàn giao. Luồng cloud/đếm ngược vẫn
thuộc WebView; chưa có hàng đợi gửi nền native/offline nên chưa bảo đảm gửi cloud
đúng hạn khi WebView bị treo/diệt. Golden không chứng minh độ chính xác ngoài thực tế.

## Tệp bàn giao

- `artifacts/fall-guard-ai-debug.apk`: APK cài thử.
- `artifacts/fall-guard-ai-debug.apk.sha256`: checksum bản APK.
- `artifacts/android-smoke.json`: kết quả kiểm tra dịch vụ qua WebView.
- `android/app/build/reports/androidTests/connected/debug/index.html`: báo cáo tests.
- `README-ANDROID.md`: hướng dẫn cài và build lại bằng Android Studio/PowerShell.

APK của đợt kiểm tra ban đầu: 28.240.585 byte; SHA-256:
`c4d436c5d368399143d06770114c7b5bd021a661f7bfd5358f445acc76238ba0`.
Bản sao báo cáo XML, kết quả lint và log build được lưu trong `artifacts/`.

Bổ sung kiểm tra thiết bị thật: xem `PHONE-TEST-REPORT.md` và `artifacts/phone-check/`.

## Bổ sung màn hình chẩn đoán trên web

- [x] Native cung cấp xác suất, số golden đạt, mã phiên và tuổi lần suy luận.
- [x] App phát snapshot AI qua Realtime Database mỗi giây khi đang giám sát.
- [x] Sensor Viewer có tab Chẩn đoán AI và xuất bằng chứng JSON.
- [x] 7 tests trạng thái đạt; build web, app và APK mới thành công.
- [x] Đã triển khai validation cho nhánh `live/{deviceId}/ai` trên Firebase.
- [x] Kiểm tra xuyên suốt APK mới → Firebase → web trên CPH2363 / Android 14:
  bộ đếm cloud tăng 196 → 626 trong cùng phiên, 10/10 golden; người dùng xác nhận
  web hiển thị và bộ đếm đang tăng.

APK tại `artifacts/fall-guard-ai-debug.apk` đã được cập nhật cho tính năng này,
SHA-256: `9c74979c364220d3032a769ec84053f6579ee3a4cf7b13bb9e4c00ca8075753b`.
Xem thêm `../sensor-viewer/README-AI-DIAGNOSTICS.md`.
