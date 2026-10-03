# Xử lý web chưa có chẩn đoán — 03/10/2026

Triệu chứng: điện thoại có chuông nhưng Sensor Viewer hiện “Chưa có dữ liệu AI”.

## Nguyên nhân đã xác minh

Plugin trên APK đang chạy chỉ trả `available`, `running`, `inferenceCount`,
`lastInferenceMicros`, `modelSha256`. Không có `schemaVersion`, `probability`,
`goldenPassed`, `sessionId`. Thời điểm cập nhật app trên điện thoại là 17:44:49,
trước bản bổ sung chẩn đoán. Hai nhánh AI của các thiết bị trên Firebase đều null.
AI native vẫn suy luận; thiếu phần phát telemetry tới web.

## Đã xử lý và kiểm tra

1. Tạm tắt giám sát qua giao diện trước khi cập nhật.
2. Cài đè APK chẩn đoán bằng `adb install -r`, không gỡ app chính hay xóa dữ liệu.
3. Xác nhận native trả đủ schema phiên bản 1 và thông tin model.
4. Bật lại giám sát như trạng thái người dùng đang sử dụng trước khi sửa.
5. Firebase nhận snapshot ở `live/119191518586/ai` từ app thật:
   số suy luận tăng **196 → 626** trong cùng phiên, golden **10/10**.
6. Người dùng chọn thiết bị tương ứng và xác nhận: **web đã hiện và số lần suy luận
   đang tăng**.

APK SHA-256:
`9c74979c364220d3032a769ec84053f6579ee3a4cf7b13bb9e4c00ca8075753b`.

Bằng chứng: `artifacts/ai-first-confirmed.json`, `artifacts/ai-second-confirmed.json`.
Không tiêm dữ liệu giả lên Firebase, không tạo té ngã mô phỏng hay chủ động gửi
cảnh báo tới người thân. Phiên giám sát được giữ đang bật sau khi sửa.

Tab chẩn đoán hiển thị xác suất của lần suy luận gần nhất. Không giữ đỉnh xác suất
hay lịch sử lần cảnh báo, nên sau chuyển động xác suất có thể giảm nhanh. Đây là
chẩn đoán pipeline đang chạy, chưa phải đánh giá độ chính xác ngoài thực tế.
