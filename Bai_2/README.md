# BÁO CÁO BÀI TẬP 2: THEO VẾT REQUEST VỚI MDC (MAPPED DIAGNOSTIC CONTEXT) TRONG SPRING BOOT

## 1. Bản Chất Của Vấn Đề Distributed Tracing & Thread Pool Trong Spring Boot

Trong các ứng dụng backend Spring Boot Web (dựa trên mô hình Servlet chuẩn như Apache Tomcat hoặc Jetty), server sử dụng kiến trúc **Thread-per-Request** với một **Thread Pool** (mặc định Tomcat có sẵn 200 worker threads).

Khi hàng nghìn request từ nhiều người dùng đồng thời gửi đến hệ thống:
- Các log của các request khác nhau sẽ bị đan xen (interleaved) lộn xộn trong file log hoặc hệ thống log tập trung.
- Khi một giao dịch thanh toán hoặc đơn hàng bị lỗi, kỹ sư không thể nào phân biệt được dòng log nào thuộc về request nào nếu chỉ nhìn vào Timestamp hay Thread Name.

```
[http-nio-8080-exec-1] INFO  com.storex.order.OrderService - Processing order...
[http-nio-8080-exec-2] INFO  com.storex.order.OrderService - Processing order...
[http-nio-8080-exec-1] ERROR com.storex.order.OrderService - Payment failed! <-- Lỗi của ai?
[http-nio-8080-exec-2] INFO  com.storex.order.OrderService - Order completed successfully.
```

### Giải pháp: MDC (Mapped Diagnostic Context)
**MDC** là cơ chế được cung cấp bởi thư viện logging chuẩn (SLF4J / Logback) cho phép lưu trữ các cặp key-value dưới dạng ngữ cảnh riêng biệt cho từng Thread (bản chất hoạt động dựa trên `ThreadLocal<Map<String, String>>`).
Bất kỳ log statement nào (`log.info`, `log.error`...) được gọi trên cùng một Thread đều có thể tự động truy xuất các giá trị trong MDC và in ra log pattern mà không cần lập trình viên phải truyền thủ công biến `requestId` vào từng dòng log.

---

## 2. Nguy Cơ Rò Rỉ Dữ Liệu & Memory Leak (Thread Pool Pollution)

### Nguyên nhân lỗi:
Vì Tomcat sử dụng cơ chế **tái sử dụng Thread (Thread Pool)**:
1. Thread `http-nio-8080-exec-1` phục vụ Request A của User A -> đưa `requestId = "AAA"` vào MDC.
2. Request A kết thúc, Thread được trả về Thread Pool.
3. Nếu lập trình viên **QUÊN KHÔNG DỌN DẸP** MDC:
   - Request B của User B đến và được cấp đúng lại Thread `http-nio-8080-exec-1`.
   - Nếu Request B không set `requestId` mới, toàn bộ log của Request B sẽ bị gắn nhãn sai thành `AAA` (hiện tượng **Thread Pool Pollution / Context Pollution**).
   - Nguy hiểm hơn, nếu object được put vào `ThreadLocal` có dung lượng lớn, Thread không bị GC giải phóng sẽ dẫn tới **Memory Leak (`OutOfMemoryError`)**.

### Biện pháp xử lý chuẩn:
Bắt buộc phải bọc logic xử lý trong khối `try ... finally` và gọi `MDC.remove(KEY)` hoặc `MDC.clear()` ngay tại khối `finally`.

---

## 3. Mã Nguồn Chuẩn Lớp `RequestIdFilter`

Class `RequestIdFilter` kế thừa từ `OncePerRequestFilter` của Spring Web để đảm bảo mỗi HTTP request chỉ đi qua filter này đúng một lần duy nhất:

```java
package com.storex.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter tự động trích xuất hoặc sinh mới RequestId/TraceId cho mỗi HTTP Request
 * và đưa vào MDC (Mapped Diagnostic Context) của SLF4J/Logback.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Đảm bảo Filter này chạy đầu tiên trước các filter khác
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_REQUEST_ID_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. Kiểm tra Client hoặc API Gateway có gửi kèm header X-Request-ID không
            String requestId = request.getHeader(REQUEST_ID_HEADER);

            // 2. Nếu không có hoặc rỗng, tự động sinh chuỗi UUID ngẫu nhiên
            if (!StringUtils.hasText(requestId)) {
                requestId = UUID.randomUUID().toString();
            }

            // 3. Đưa requestId vào MDC để mọi dòng log trong Thread này tự động có requestId
            MDC.put(MDC_REQUEST_ID_KEY, requestId);

            // 4. Trả ngược lại header X-Request-ID cho Client tiện theo dõi đối chiếu
            response.setHeader(REQUEST_ID_HEADER, requestId);

            // 5. Cho phép request tiếp tục đi qua các filter tiếp theo và vào Controller
            filterChain.doFilter(request, response);

        } finally {
            // 6. QUAN TRỌNG: Dọn dẹp MDC trong khối finally!
            // Giải phóng context khi Thread kết thúc để tránh Thread Pool Pollution và Memory Leak
            MDC.remove(MDC_REQUEST_ID_KEY);
            
            // Hoặc có thể dùng: MDC.clear(); nếu muốn xóa toàn bộ key trong context
        }
    }
}
```

### Các điểm mấu chốt trong thiết kế:
- `@Order(Ordered.HIGHEST_PRECEDENCE)`: Đảm bảo gán `requestId` ngay từ khoảnh khắc đầu tiên request chạm vào application, giúp ghi nhận log kể cả khi request bị chặn bởi Security Filter hoặc Exception Filter.
- `response.setHeader("X-Request-ID", requestId)`: Trả về cho frontend/client để khi người dùng gặp lỗi, họ chỉ cần cung cấp mã `X-Request-ID` là đội ngũ hỗ trợ có thể tra ra toàn bộ nhật ký giao dịch chỉ trong vài giây.
- `MDC.remove(...)` trong `finally`: Đảm bảo dù controller có ném ra bất kỳ `Exception` hoặc `Error` nào, khối `finally` vẫn được thực thi 100%, bảo vệ an toàn cho Thread Pool.

---

## 4. Cấu Hình Pattern `%X{requestId}` Trong `logback-spring.xml`

Trong Logback, cú pháp `%X{key}` được sử dụng để lấy giá trị từ MDC context tương ứng với tên `key`.

Dưới đây là cấu hình mẫu trích đoạn trong `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration scan="true" scanPeriod="30 seconds">

    <!-- Định nghĩa Pattern in ra Console có chứa %X{requestId} -->
    <property name="CONSOLE_LOG_PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [%cyan(%X{requestId:-NO-REQ-ID})] [%magenta(%thread)] %logger{36} - %msg%n"/>

    <!-- Console Appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${CONSOLE_LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- Root Logger -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

</configuration>
```

### Phân tích cú pháp `%X{requestId:-NO-REQ-ID}`:
- `%X{requestId}`: Trích xuất giá trị ứng với key `requestId` trong `MDC`.
- `:-NO-REQ-ID`: Cú pháp **Default Value Fallback** của Logback. Nếu một log được in ra từ Background Thread, Scheduled Task, hoặc lúc khởi động Spring context (khi chưa có HTTP request nào), Logback sẽ hiển thị chuỗi fallback `NO-REQ-ID` thay vì chuỗi rỗng gây lệch cột định dạng log.

---

## 5. Minh Họa Log Thực Tế Sau Khi Áp Dụng MDC

### Khi Client gửi request không kèm header:
`GET http://localhost:8080/api/payment/test-debug`

Log Terminal:
```text
2026-09-28 23:35:10.450 INFO  [9c3b88d2-4318-472e-84ad-e770f1a9cd7b] [http-nio-8080-exec-1] c.s.payment.PaymentController - Processing payment request for account: ACC-123
2026-09-28 23:35:10.455 DEBUG [9c3b88d2-4318-472e-84ad-e770f1a9cd7b] [http-nio-8080-exec-1] c.s.payment.PaymentController - Validating payment gateway balance
2026-09-28 23:35:10.460 INFO  [9c3b88d2-4318-472e-84ad-e770f1a9cd7b] [http-nio-8080-exec-1] c.s.payment.PaymentController - Payment processed successfully
```

### Khi Client truyền Header tùy biến `X-Request-ID: order-req-99999`:
Log Terminal:
```text
2026-09-28 23:35:15.112 INFO  [order-req-99999] [http-nio-8080-exec-2] c.s.payment.PaymentController - Processing payment request for account: ACC-999
```

### Kết luận:
Nhờ MDC, khi kết hợp với các công cụ tìm kiếm log như Kibana hay Loki, kỹ sư chỉ cần nhập câu truy vấn `requestId: "9c3b88d2-4318-472e-84ad-e770f1a9cd7b"` là có thể lọc ra toàn bộ lifecycle của đúng 1 giao dịch duy nhất từ đầu đến cuối mà không bị lẫn lộn giữa hàng triệu dòng log khác.
