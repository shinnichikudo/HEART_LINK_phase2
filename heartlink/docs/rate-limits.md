# Giới hạn tần suất API

Các endpoint được giới hạn sau khi JWT hợp lệ đã xác định user. Mỗi user có một token bucket riêng cho từng thao tác; gọi tới thao tác khác hoặc dùng tài khoản khác không trừ chung quota.

| Thao tác | Burst tối đa | Tốc độ nạp lại |
| --- | ---: | ---: |
| `POST /api/v1/swipes` | 20 | 20 token mỗi 60 giây |
| `GET /api/v1/recommendations` | 30 | 30 token mỗi 60 giây |
| `POST /api/v1/matches/{matchId}/messages` | 20 | 20 token mỗi 60 giây |
| `GET /api/v1/matches/{matchId}/messages` | 60 | 60 token mỗi 60 giây |

Bucket bắt đầu đầy và token được nạp liên tục trong khoảng thời gian cấu hình. Các giá trị mặc định nằm trong `application.properties` và có thể điều chỉnh riêng theo thao tác.

Khi hết token, server trả `429 Too Many Requests`, body có mã `RATE_LIMITED`, và header `Retry-After` cho biết số giây tối thiểu trước khi thử lại. Client nên chờ đủ thời gian đó trước khi gửi lại. Request đã xác thực nhưng bị từ chối do limit không đi tiếp tới controller/database.

Giới hạn hiện dựa trên số request, không phân tích hành vi để nhận diện bot. Bucket đang lưu trong bộ nhớ của một instance; khi chạy nhiều instance, cần chuyển state sang Redis hoặc kho dùng chung để quota không bị nhân theo số instance. Các API đăng nhập/đăng ký và SSE stream chưa nằm trong nhóm limit này.