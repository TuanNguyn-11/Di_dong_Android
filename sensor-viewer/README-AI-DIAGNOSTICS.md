# Chẩn đoán AI trên Sensor Viewer

## Mở màn hình

```powershell
cd C:\DDisk\HK_I_nam4_dot_1\Di_dong\web\sensor-viewer
npm run build
npm run serve
```

Mở http://localhost:5180, đăng nhập tài khoản chủ thiết bị hoặc người thân đã ghép
cặp, chọn thiết bị và bấm **Chẩn đoán AI**.

Cài APK mới tại `../fall-guard-app/artifacts/fall-guard-ai-debug.apk`, sau đó bật
giám sát trên điện thoại. APK cũ không gửi telemetry chẩn đoán nên web sẽ hiện
“Chưa có dữ liệu AI”. Không cần kết nối USB khi sử dụng bình thường.

## Các thông số

- Số lần suy luận: bộ đếm native, tăng sau khi model thực thi xong, reset theo phiên.
- Thời gian suy luận: thời gian lần chạy gần nhất, đơn vị ms; không phải độ trễ mạng.
- Xác suất té ngã: sigmoid từ logits INT8, không phải phần trăm độ chính xác.
- Mẫu chuẩn: số golden vector khớp Python khi khởi động, tối đa 10.
- Model, SHA-256, mã phiên và thời điểm cập nhật máy chủ.
- **Xuất bằng chứng JSON** lưu snapshot hiện tại kèm thiết bị, thời điểm xuất và trạng thái.

Trạng thái “đang suy luận” yêu cầu dữ liệu mới, dịch vụ chạy, có inference và đủ
10 golden. Quá 5 giây không cập nhật sẽ báo dữ liệu cũ; inference quá 3 giây
không tiến triển theo thời gian native sẽ báo kiểm tra cảm biến. Khi metadata
báo dừng, số liệu được giữ dưới nhãn “số liệu lần cuối”. Không suy ra AI từ biểu đồ.

UI tính tuổi dữ liệu bằng độ lệch giờ máy chủ Firebase, không lấy đồng hồ trình
duyệt đơn thuần. Đổi thiết bị/đăng xuất xóa snapshot khỏi màn hình. Nút tạm dừng
dạng sóng không tạm dừng cập nhật tab AI.

## Luồng dữ liệu và quyền

`Android LiteRT → Capacitor isAvailable → Fall Guard → live/{deviceId}/ai → web`.
App gửi tối đa một snapshot mỗi giây, ghi đè bản gần nhất. Không tạo lịch sử AI
liên tục, không gửi vị trí, tài khoản hay token trong telemetry này. Chỉ chủ máy
được ghi; chủ máy/người thân đã được cấp quyền xem luồng mới đọc được.

Đã bổ sung và triển khai nhánh kiểm tra `ai` trong Realtime Database của project
`fall-guard-gps` ngày 03/10/2026. Đã đối chiếu rules đang triển khai trước khi sửa;
không thay quyền đọc/ghi hiện có. Config riêng cho deploy rules:

```powershell
cd ..
firebase deploy --only database --config firebase-diagnostics.json --project fall-guard-gps
```

## Kiểm tra đã thực hiện

- Build/typecheck cả Sensor Viewer và Fall Guard.
- 7 bài test trạng thái: `node --test tests/diagnostics.test.mjs`.
- Build APK Android và unit test thành công.
- Kiểm tra giao diện bằng Edge headless với fixture được ghi rõ là dữ liệu kiểm thử;
  ảnh lưu ở `artifacts/ai-desktop.png`, `artifacts/ai-mobile.png`.
- Đã kiểm tra xuyên suốt trên CPH2363 / Android 14 ngày 03/10/2026:
  APK mới gửi telemetry lên Firebase; bộ đếm tăng từ 196 lên 626 trong cùng phiên,
  golden đạt 10/10. Người dùng xác nhận Sensor Viewer hiển thị dữ liệu và bộ đếm tăng.

## Nếu điện thoại báo chuông nhưng web chưa có dữ liệu

Chuông native có thể hoạt động với APK cũ chưa gửi chẩn đoán. Trong lần xử lý thực
tế, APK đang cài thiếu các trường `schemaVersion`, `probability`, `sessionId` trong
kết quả plugin; nhánh Firebase `ai` là null. Sau khi cài đè đúng APK có SHA-256
`9c74979c364220d3032a769ec84053f6579ee3a4cf7b13bb9e4c00ca8075753b`, dữ liệu đã xuất hiện.

Kiểm tra đúng mã thiết bị trên web, cài APK tại đường dẫn bàn giao rồi bật lại giám
sát. Không cần tạo một lần té để thử: bộ đếm phải tăng ngay cả khi điện thoại nằm yên.
Xác suất hiển thị là lần suy luận gần nhất; nó có thể giảm ngay sau chuyển động,
không phải lịch sử hoặc xác suất đỉnh của lần cảnh báo trước.

Telemetry là dữ liệu do app báo về, không phải chứng thực mật mã hay phép đo độ
chính xác ngoài thực tế. WebView bị treo hoặc mất mạng có thể làm web ngừng cập nhật
trong khi AI native vẫn chạy. Kiểm tra tình huống đó trực tiếp bằng Logcat/native.
