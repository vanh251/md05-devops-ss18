# DỰ ÁN STOREX-SERVICE (TỔNG HỢP BÀI 1, BÀI 2, BÀI 3)

Dự án mẫu Spring Boot 3.3.4 (Java 21) tích hợp toàn diện:
1. **Bài 1**: Quản lý và thay đổi Log Level Runtime với **Spring Boot Actuator** không cần restart server.
2. **Bài 2**: Theo vết Request trong hệ thống đa luồng bằng **MDC (Mapped Diagnostic Context)** và `RequestIdFilter`.
3. **Bài 3**: Tự động bắn cảnh báo lỗi mức `ERROR` lên **Discord Webhook** bằng Custom **`DiscordAppender`** của Logback.

---

## 1. Cấu Trúc Dự Án

```text
storex-service/
├── pom.xml
├── README.md
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── storex/
        │           ├── StorexApplication.java
        │           ├── filter/
        │           │   └── RequestIdFilter.java       <-- [Bài 2] MDC & dọn dẹp finally
        │           ├── logging/
        │           │   └── DiscordAppender.java       <-- [Bài 3] Custom Logback Webhook Appender
        │           └── payment/
        │               └── PaymentController.java     <-- [Bài 1 & 3] API test-debug & test-error
        └── resources/
            ├── application.yml                        <-- [Bài 1] Expose actuator loggers
            └── logback-spring.xml                     <-- [Bài 2 & 3] %X{requestId} & Discord Appender
```

---

## 2. Hướng Dẫn Chạy Ứng Dụng

### Điều kiện tiên quyết:
- Java JDK 21 (hoặc JDK 17+)
- Apache Maven 3.8+

### Khởi động dự án:
```bash
# Di chuyển vào thư mục storex-service
cd session_18/storex-service

# (Tùy chọn) Cung cấp Discord Webhook URL thật của bạn nếu muốn nhận thông báo thực tế
export DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/YOUR/WEBHOOK/URL"

# Build và chạy ứng dụng Spring Boot
mvn spring-boot:run
```
Ứng dụng sẽ lắng nghe tại cổng `http://localhost:8080`.

---

## 3. Kịch Bản Kiểm Thử Từng Bài Tập

### KỊCH BẢN BÀI 1: Thay Đổi Log Level Runtime Với Actuator

1. **Bước 1**: Gọi API `test-debug` (Mặc định log level là `INFO`):
   ```bash
   curl -X GET http://localhost:8080/api/payment/test-debug
   ```
   *Quan sát Console*: **Không có** dòng log DEBUG nào xuất hiện.

2. **Bước 2**: Kiểm tra cấu hình log level hiện tại của package:
   ```bash
   curl -X GET http://localhost:8080/actuator/loggers/com.storex.payment
   ```
   *Kết quả JSON*: `{"configuredLevel":"INFO","effectiveLevel":"INFO"}`

3. **Bước 3**: Chuyển Log Level sang `DEBUG` qua Actuator mà không restart:
   ```bash
   curl -X POST http://localhost:8080/actuator/loggers/com.storex.payment \
     -H "Content-Type: application/json" \
     -d '{"configuredLevel": "DEBUG"}'
   ```
   *Kết quả*: HTTP `204 No Content`.

4. **Bước 4**: Gọi lại API `test-debug`:
   ```bash
   curl -X GET http://localhost:8080/api/payment/test-debug
   ```
   *Quan sát Console*: Dòng log DEBUG xuất hiện ngay lập tức:
   ```text
   2026-09-28 23:45:10.123 DEBUG [e6f1545e-4c75-4081-9b16-52c7be0e9c12] [http-nio-8080-exec-3] c.s.payment.PaymentController - DEBUG: Đang xử lý giao dịch thanh toán chi tiết tại com.storex.payment.PaymentController. Check balance, checksum verify OK.
   ```

---

### KỊCH BẢN BÀI 2: Theo Vết Request Qua MDC (UUID / RequestId)

1. **Client không truyền Header `X-Request-ID`**:
   ```bash
   curl -i -X GET http://localhost:8080/api/payment/test-debug
   ```
   - **Response Header**: Có chứa `X-Request-ID: <UUID-ngau-nhien>`
   - **Log Console**: Ở đầu dòng log hiển thị mã UUID nằm trong ngoặc vuông `[<UUID>]`.

2. **Client chủ động truyền Header `X-Request-ID`** (Ví dụ hệ thống Gateway chuyển tiếp):
   ```bash
   curl -i -X GET http://localhost:8080/api/payment/test-debug \
     -H "X-Request-ID: MY-CUSTOM-TRACE-9999"
   ```
   - **Log Console**:
   ```text
   2026-09-28 23:46:02.880 DEBUG [MY-CUSTOM-TRACE-9999] [http-nio-8080-exec-5] c.s.payment.PaymentController - DEBUG: Đang xử lý giao dịch thanh toán...
   ```
   - Request kết thúc, khối `finally` trong `RequestIdFilter` đã gọi `MDC.remove("requestId")` bảo đảm Thread sạch hoàn toàn khi quay về Thread Pool.

---

### KỊCH BẢN BÀI 3: Bắn Cảnh Báo Lỗi ERROR Lên Discord Webhook

1. Gọi API giả lập lỗi thanh toán:
   ```bash
   curl -X GET http://localhost:8080/api/payment/test-error
   ```
2. Phản hồi API: Trả về HTTP 500 kèm thông báo JSON.
3. Trên Console:
   - In ra dòng log `ERROR` kèm toàn bộ Exception StackTrace và Request ID.
4. Trên Kênh Discord (hoặc log cảnh báo Webhook):
   - `DiscordAppender` bắt sự kiện `ERROR`, trích xuất thông tin:
     - Application Name: `storex-service`
     - Request ID: `e6f1545e-4c75-4081-9b16-52c7be0e9c12`
     - Logger: `com.storex.payment.PaymentController`
     - Error Message: `Giao dịch thanh toán thất bại nghiêm trọng do Cổng thanh toán Timeout!`
     - Top StackTrace
   - Đóng gói thành Discord Rich Embeds gửi HTTP POST tới Discord Webhook.
