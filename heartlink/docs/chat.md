# Chat theo match

Một match đang hoạt động là cuộc trò chuyện giữa hai người. Tin nhắn được lưu trong bảng `messages`, liên kết tới `matches` và `users`.

## Gửi tin

```http
POST /api/v1/matches/42/messages
Authorization: Bearer <access_token>
Content-Type: application/json

{ "content": "Chào bạn!" }
```

Response `201` có `id`, `sender_id`, `content` và `created_at`. Nội dung phải có từ 1 đến 2.000 ký tự và được gửi dưới dạng text. Backend lấy người gửi từ JWT.

## Đọc lịch sử

```http
GET /api/v1/matches/42/messages?limit=20
Authorization: Bearer <access_token>
```

Mặc định, server trả tin mới nhất trước theo ID giảm dần. Response luôn có cả hai cursor; cursor không áp dụng sẽ là `null`:

```json
{
  "items": [
    { "id": 124, "sender_id": 1, "content": "Chào bạn!", "created_at": "2026-10-28T09:00:00Z" }
  ],
  "next_before": null,
  "next_after": null
}
```

Dùng `before=<next_before>` để tải trang cũ hơn. Dùng `after=<lastSeenMessageId>` để tải tin mới hơn theo thứ tự ID tăng dần. Khi còn một trang tiếp theo, `next_after` là ID cuối của trang hiện tại; gọi lại với `after=<next_after>` cho tới khi `next_after` là `null`. Hai cursor không được gửi cùng lúc. `limit` mặc định là 20 và nhận giá trị từ 1 đến 50. Truy vấn luôn giới hạn trong match hiện tại.

## Nhận tin mới và khôi phục sau khi mất kết nối

Mở `GET /api/v1/matches/{matchId}/messages/stream` với Bearer JWT để nhận SSE event `message.created`. ID event là ID tin nhắn. Server chỉ gửi event sau khi transaction lưu tin thành công.

Server không lưu hàng đợi event cho client offline. Khi kết nối bị gián đoạn:

1. Mở lại SSE stream.
2. Gọi `GET /api/v1/matches/{matchId}/messages?after=<lastSeenMessageId>`.
3. Thêm các tin trả về theo thứ tự ID tăng dần; tiếp tục với `next_after` cho tới khi cursor là `null`.
4. Bỏ qua ID đã hiển thị vì event SSE và kết quả tải bù có thể trùng nhau. Cập nhật `lastSeenMessageId` theo ID lớn nhất đã xử lý.

Kết nối SSE hiện nằm trong bộ nhớ của một instance. Nếu chạy nhiều instance, cần pub/sub dùng chung để chuyển event giữa các instance. Match phải đang hoạt động; chỉ hai thành viên của match đọc/gửi được. Request không hợp lệ nhận `400`, người ngoài match nhận `403`, match không tồn tại hoặc đã huỷ nhận `404`.

## Kiểm tra

Trong thư mục `heartlink`, chạy kiểm tra cursor:

```bash
./mvnw -Dtest=ChatHistoryCursorTests test
```

Kiểm tra tích hợp API với PostgreSQL test (cổng 55432):

```bash
docker compose -f docker-compose.test.yml up -d --wait
HEARTLINK_DB_TESTS=true ./mvnw -Dtest=Phase1ApiTests test
docker compose -f docker-compose.test.yml stop
```