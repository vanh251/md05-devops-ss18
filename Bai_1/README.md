# BÁO CÁO BÀI TẬP 1: THAY ĐỔI LOG LEVEL RUNTIME KHÔNG CẦN RESTART VỚI SPRING BOOT ACTUATOR

## 1. Đặt Vấn Đề
Trong môi trường Production, hệ thống thường được cấu hình mức log mặc định là `INFO` hoặc `WARN` nhằm:
- Tiết kiệm dung lượng lưu trữ ổ đĩa.
- Tránh làm suy giảm hiệu năng I/O (I/O Bottleneck).
- Giảm tải cho các hệ thống log tập trung như ELK (Elasticsearch - Logstash - Kibana) hoặc Grafana Loki.

Tuy nhiên, khi xảy ra sự cố cần debug lỗi nghiệp vụ phức tạp, thông tin ở mức `INFO` thường không đủ để tái hiện hoặc khoanh vùng nguyên nhân. Nếu phải sửa cấu hình rồi **Restart lại toàn bộ Service**:
- Gây gián đoạn dịch vụ (downtime hoặc đứt kết nối người dùng tạm thời).
- Làm mất trạng thái lỗi hiện tại trong bộ nhớ (in-memory state, connection pool, cache).
- Tốn nhiều thời gian deploy và phê duyệt quy trình vận hành.

**Giải pháp:** Sử dụng **Spring Boot Actuator Loggers Endpoint** cho phép kỹ sư DevOps / SRE thay đổi Log Level trực tiếp khi ứng dụng đang chạy (Runtime) thông qua giao thức HTTP REST API mà **không cần restart ứng dụng**.

---

## 2. Cấu Hình `application.yml` Expose Endpoint `loggers`

Theo mặc định trong Spring Boot 3.x, vì lý do bảo mật, chỉ có endpoint `/actuator/health` được expose ra ngoài qua HTTP. Để mở quyền truy cập cho endpoint `loggers`, ta cấu hình trong file `application.yml`:

```yaml
server:
  port: 8080

# Cấu hình mức log mặc định ban đầu
logging:
  level:
    root: INFO
    com.storex: INFO
    com.storex.payment: INFO # Mặc định INFO, các log DEBUG sẽ bị bỏ qua

# Cấu hình Spring Boot Actuator
management:
  endpoints:
    web:
      exposure:
        # Expose các endpoint cần thiết bao gồm loggers
        include: health, info, loggers
        # Hoặc expose toàn bộ (chỉ khuyến nghị trong môi trường dev): include: "*"
      base-path: /actuator
  endpoint:
    loggers:
      enabled: true
```

### Giải thích chi tiết:
- `management.endpoints.web.exposure.include`: Chỉ định danh sách endpoint được mở qua giao thức HTTP Web. Ở đây ta thêm `loggers`.
- `management.endpoint.loggers.enabled: true`: Bật tính năng quản lý loggers của Actuator.
- `logging.level.com.storex.payment: INFO`: Đặt package `com.storex.payment` ở mức `INFO` ban đầu.

---

## 3. Quy Trình & Các Mẫu Request Thay Đổi Log Level

### 3.1. Kiểm tra mức Log hiện tại của Package (GET)

Trước khi thay đổi, ta gửi request kiểm tra cấu hình hiện tại của logger `com.storex.payment`:

#### cURL Request:
```bash
curl -X GET http://localhost:8080/actuator/loggers/com.storex.payment \
  -H "Accept: application/vnd.spring-boot.actuator.v3+json, application/json"
```

#### Response mẫu:
```json
{
  "configuredLevel": "INFO",
  "effectiveLevel": "INFO"
}
```
> **Ghi chú**: `configuredLevel` là mức log được cấu hình tường minh, còn `effectiveLevel` là mức log thực tế đang có hiệu lực (kế thừa từ root nếu không cấu hình trực tiếp).

---

### 3.2. Thay đổi Log Level sang `DEBUG` (POST)

Để đổi log level sang `DEBUG` cho `com.storex.payment`, ta gửi HTTP POST tới endpoint tương ứng:

#### a. Định dạng cURL:
```bash
curl -X POST http://localhost:8080/actuator/loggers/com.storex.payment \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'
```

#### b. Định dạng HTTP Raw / Postman:
- **Method**: `POST`
- **URL**: `http://localhost:8080/actuator/loggers/com.storex.payment`
- **Headers**:
  ```http
  Content-Type: application/json
  Accept: application/json
  ```
- **Body (raw JSON)**:
  ```json
  {
    "configuredLevel": "DEBUG"
  }
  ```

#### Response mong đợi:
- **HTTP Status Code**: `204 No Content` (Spring Actuator trả về HTTP 204 khi cập nhật thành công).

---

### 3.3. Kiểm tra lại mức Log sau khi thay đổi (GET)

```bash
curl -X GET http://localhost:8080/actuator/loggers/com.storex.payment
```

#### Response nhận được:
```json
{
  "configuredLevel": "DEBUG",
  "effectiveLevel": "DEBUG"
}
```

---

### 3.4. Reset Log Level về mặc định ban đầu (POST null)

Sau khi hoàn tất quá trình debug và sự cố đã được xử lý, cần đưa mức log trở lại trạng thái kế thừa cha hoặc reset để tránh đầy ổ đĩa:

#### cURL Request:
```bash
curl -X POST http://localhost:8080/actuator/loggers/com.storex.payment \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": null}'
```

---

## 4. Kiểm Thử Nghiệp Vụ Thực Tế Với Controller

Trong dự án `storex-service`, API `GET /api/payment/test-debug` ghi một log mức `DEBUG`:

```java
@GetMapping("/test-debug")
public ResponseEntity<Map<String, Object>> testDebug() {
    log.debug("Debug Payment Details: processing payment transaction in com.storex.payment");
    return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Debug log triggered"));
}
```

### Kịch bản kiểm thử:
1. **Bước 1**: Khởi động `storex-service`. Log level mặc định của `com.storex.payment` là `INFO`.
2. **Bước 2**: Gọi `GET http://localhost:8080/api/payment/test-debug`.
   - **Kết quả Terminal**: Không có dòng log nào xuất hiện vì mức log `DEBUG` < `INFO`.
3. **Bước 3**: Gửi request `POST /actuator/loggers/com.storex.payment` với payload `{"configuredLevel": "DEBUG"}`.
4. **Bước 4**: Gọi lại `GET http://localhost:8080/api/payment/test-debug`.
   - **Kết quả Terminal**: Xuất hiện ngay lập tức dòng log `DEBUG`:
     ```text
     2026-09-28 23:30:15.123 [DEBUG] [7b92f768-3d84-486a-8671-558e80a0ffaa] com.storex.payment.PaymentController: Debug Payment Details: processing payment transaction in com.storex.payment
     ```
   *(Đã bao gồm `requestId` từ MDC được cấu hình ở Bài 2).*

---

## 5. Lưu Ý Về An Toàn & Best Practices Trong Production

1. **Bảo mật Endpoint Actuator**:
   - Endpoint `/actuator/loggers` có thể làm lộ thông tin nhạy cảm hoặc bị kẻ tấn công đổi sang `TRACE/DEBUG` để gây cạn kiệt tài nguyên (Denial of Service via Disk exhaustion).
   - **Khuyến nghị**:
     - Đặt Actuator trên cổng riêng biệt qua `management.server.port: 8081` và chặn bằng Firewall/Security Group, chỉ cho phép mạng nội bộ (VPN / Bastion Host) truy cập.
     - Tích hợp Spring Security với Role `ROLE_ACTUATOR` hoặc `ROLE_ADMIN` để xác thực mỗi khi gọi thay đổi cấu hình.
2. **Tự Động Reset (Auto Revert)**:
   - Trong vận hành, DevOps nên thiết lập script tự động reset log level về `INFO` sau một khoảng thời gian (ví dụ 15 - 30 phút) để tránh việc quên tắt `DEBUG` gây tràn ổ cứng.
