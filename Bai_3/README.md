# BÁO CÁO BÀI TẬP 3: XÂY DỰNG CUSTOM LOGBACK APPENDER BẮN CẢNH BÁO LÊN DISCORD / SLACK WEBHOOK

## 1. Giới Thiệu & Kiến Trúc Custom Appender Trong Logback

Trong hệ sinh thái Java / Spring Boot, **Logback** là framework ghi nhật ký (logging framework) mặc định đi kèm với SLF4J.
Kiến trúc Logback được phân chia thành các thành phần chính:
- **Logger**: Nhận các lệnh ghi log từ code ứng dụng (`log.info`, `log.error`).
- **Appender**: Quyết định **đích đến (destination)** của log (Console, File, Socket, Database, CloudWatch, hoặc Webhook bên thứ 3).
- **Layout/Encoder**: Chịu trách nhiệm định dạng thông điệp log (Plain text, JSON, XML).

Khi xảy ra lỗi nghiêm trọng ở mức `ERROR` (như sập kết nối Database, cổng thanh toán bên thứ ba lỗi, hoặc `NullPointerException` bất ngờ), đội ngũ On-call / DevOps cần nhận được thông báo tức thời (Instant Alerting) qua kênh chat nội bộ như **Discord** hoặc **Slack** để phản ứng nhanh nhất.

Thay vì phải cài thêm các công cụ Agent phức tạp, ta có thể viết một **Custom Logback Appender** kế thừa `ch.qos.logback.core.AppenderBase<ILoggingEvent>`.

---

## 2. Thiết Kế Class `DiscordAppender`

### Mã Nguồn Hoàn Chỉnh:

```java
package com.storex.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Custom Logback Appender lắng nghe các sự kiện log ERROR
 * và gửi thông báo qua Discord Webhook bằng Java 11+ HttpClient.
 */
public class DiscordAppender extends AppenderBase<ILoggingEvent> {

    // Tham số cấu hình nhận từ logback-spring.xml
    private String webhookUrl;
    private String applicationName = "storex-service";

    // Sử dụng Java 11+ HttpClient chuẩn, độc lập với Spring Context
    private HttpClient httpClient;

    @Override
    public void start() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            addWarn("DiscordAppender: webhookUrl chưa được cấu hình, appender sẽ không hoạt động!");
            return;
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        super.start();
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        // 1. Chỉ lọc và xử lý các sự kiện ở mức ERROR
        if (eventObject.getLevel() != Level.ERROR) {
            return;
        }

        // Bỏ qua nếu appender chưa khởi động hoặc chưa có webhookUrl
        if (!isStarted() || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            // 2. Trích xuất thông tin từ ILoggingEvent và MDC
            String loggerName = eventObject.getLoggerName();
            String message = eventObject.getFormattedMessage();
            Map<String, String> mdcPropertyMap = eventObject.getMDCPropertyMap();
            String requestId = (mdcPropertyMap != null && mdcPropertyMap.containsKey("requestId"))
                    ? mdcPropertyMap.get("requestId")
                    : "N/A";

            // 3. Trích xuất tóm tắt Stack Trace (nếu có Exception)
            String stackTraceSummary = extractStackTrace(eventObject.getThrowableProxy());

            // 4. Đóng gói Payload theo cấu trúc Discord Embeds JSON
            String jsonPayload = buildDiscordEmbedPayload(
                    applicationName,
                    requestId,
                    loggerName,
                    message,
                    stackTraceSummary
            );

            // 5. Gửi HTTP POST bất đồng bộ (sendAsync) tránh block request chính
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() >= 400) {
                            // Dùng addWarn của Logback, KHÔNG dùng logger.error!
                            addWarn("Gửi Discord Webhook thất bại. Status Code: " + res.statusCode() + " Body: " + res.body());
                        }
                    })
                    .exceptionally(ex -> {
                        addWarn("Lỗi mạng khi gửi Discord Webhook: " + ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            // Ghi nhận lỗi nội bộ vào hệ thống Logback bằng addError
            addError("Lỗi trong quá trình thực thi DiscordAppender", e);
        }
    }

    private String extractStackTrace(IThrowableProxy throwableProxy) {
        if (throwableProxy == null) {
            return "None";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(throwableProxy.getClassName()).append(": ").append(throwableProxy.getMessage()).append("\n");
        
        StackTraceElementProxy[] stepArray = throwableProxy.getStackTraceElementProxyArray();
        if (stepArray != null) {
            int maxLines = Math.min(stepArray.length, 5); // Lấy tối đa 5 dòng đầu tiên
            for (int i = 0; i < maxLines; i++) {
                sb.append("   at ").append(stepArray[i].toString()).append("\n");
            }
        }
        return escapeJson(sb.toString());
    }

    private String buildDiscordEmbedPayload(String app, String reqId, String logger, String msg, String stackTrace) {
        String title = "🚨 [" + app + "] PRODUCTION ERROR ALERT";
        String colorRed = "15158332"; // Mã màu HEX #E74C3C dưới dạng số nguyên

        return String.format("""
        {
          "username": "StoreX Alert Bot",
          "avatar_url": "https://cdn-icons-png.flaticon.com/512/564/564619.png",
          "embeds": [
            {
              "title": "%s",
              "color": %s,
              "timestamp": "%s",
              "fields": [
                {
                  "name": "🆔 Request ID",
                  "value": "`%s`",
                  "inline": true
                },
                {
                  "name": "📍 Logger",
                  "value": "`%s`",
                  "inline": true
                },
                {
                  "name": "💬 Error Message",
                  "value": "```%s```",
                  "inline": false
                },
                {
                  "name": "💥 Stack Trace (Top)",
                  "value": "```%s```",
                  "inline": false
                }
              ],
              "footer": {
                "text": "StoreX Monitoring System"
              }
            }
          ]
        }
        """,
        escapeJson(title),
        colorRed,
        Instant.now().toString(),
        escapeJson(reqId),
        escapeJson(logger),
        escapeJson(msg),
        stackTrace
        );
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    // Getter và Setter để Logback tiêm giá trị cấu hình từ XML
    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }
}
```

---

## 3. Cấu Hình Trong `logback-spring.xml`

Để kích hoạt `DiscordAppender` và tích hợp `AsyncAppender` chuẩn của Logback:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration scan="true" scanPeriod="30 seconds">

    <!-- Đọc biến môi trường DISCORD_WEBHOOK_URL, nếu không có thì dùng URL mặc định/giả định -->
    <springProperty scope="context" name="DISCORD_WEBHOOK" source="discord.webhook.url"
                    defaultValue="https://discord.com/api/webhooks/dummy-url"/>

    <!-- Định nghĩa Console Pattern với %X{requestId} -->
    <property name="CONSOLE_LOG_PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [%cyan(%X{requestId:-NO-REQ-ID})] [%magenta(%thread)] %logger{36} - %msg%n"/>

    <!-- 1. Console Appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${CONSOLE_LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- 2. Custom Discord Appender -->
    <appender name="DISCORD" class="com.storex.logging.DiscordAppender">
        <webhookUrl>${DISCORD_WEBHOOK}</webhookUrl>
        <applicationName>storex-service</applicationName>
    </appender>

    <!-- 3. Async Wrapper để bảo vệ hiệu năng ứng dụng -->
    <appender name="ASYNC_DISCORD" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="DISCORD"/>
        <queueSize>512</queueSize>
        <!-- Không bỏ qua log ERROR khi hàng đợi đầy (discardingThreshold = 0) -->
        <discardingThreshold>0</discardingThreshold>
        <!-- Bật includeCallerData để giữ lại thông tin caller khi chạy async -->
        <includeCallerData>true</includeCallerData>
    </appender>

    <!-- Root Logger -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_DISCORD"/>
    </root>

</configuration>
```

---

## 4. Các Cạm Bẫy Trọng Yếu Khi Lập Trình Custom Appender (Best Practices)

### 4.1. Cạm bẫy "Vòng lặp vô tận chết người" (Infinite Recursive Logging Loop)
- **Kịch bản sự cố**: Khi mạng chập chờn hoặc Discord Webhook URL bị 404, request HTTP gửi đi bị fail. Nếu bên trong phương thức `append()` lập trình viên viết:
  ```java
  // TUYỆT ĐỐI KHÔNG LÀM NHƯ THẾ NÀY:
  catch (Exception ex) {
      log.error("Không gửi được webhook Discord!", ex);
  }
  ```
- **Hậu quả**: Khi `log.error` được gọi, Logback lại chuyển sự kiện này vào chính `DiscordAppender.append()`, lại bị lỗi mạng, lại gọi `log.error`... Quá trình lặp vô hạn dẫn tới `StackOverflowError` làm **sập ngay lập tức JVM**.
- **Giải pháp**: Luôn sử dụng các phương thức có sẵn của `AppenderBase` như `addWarn(...)`, `addError(...)` hoặc in ra `System.err`. Các phương thức này ghi trực tiếp vào `StatusManager` nội bộ của Logback mà không kích hoạt chuỗi appender.

### 4.2. Tránh làm chậm API chính (Blocking I/O Bottleneck)
- Nếu gọi HTTP đồng bộ bên trong `append()`, mỗi khi controller có lỗi, request người dùng sẽ phải chờ thêm 2 - 5 giây mạng chỉ để hoàn tất gửi Webhook sang Discord.
- **Giải pháp**: Sử dụng `HttpClient.sendAsync(...)` hoặc bọc appender qua `ch.qos.logback.classic.AsyncAppender` với hàng đợi (In-memory Queue / BlockingQueue).

### 4.3. Bão Cảnh Báo (Alert Fatigue & Rate Limiting)
- Nếu Database bị sập, 5000 request ập đến sinh ra 5000 log ERROR trong 1 giây. Nếu đẩy toàn bộ lên Discord, tài khoản bot sẽ bị Discord khóa IP vì vi phạm Rate Limit (HTTP 429).
- **Giải pháp**:
  - Tích hợp bộ đệm (Sliding Window hoặc Token Bucket Rate Limiter).
  - Hoặc cấu hình giới hạn tối đa 5 thông báo / phút, các lỗi trùng lặp chỉ tổng hợp số đếm (Aggregated Count).
