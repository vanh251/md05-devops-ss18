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
 * Custom Logback Appender lắng nghe các sự kiện log mức ERROR
 * và gửi cảnh báo tự động lên Discord Webhook channel.
 */
public class DiscordAppender extends AppenderBase<ILoggingEvent> {

    private String webhookUrl;
    private String applicationName = "storex-service";
    private HttpClient httpClient;

    @Override
    public void start() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            addWarn("DiscordAppender: Chưa thiết lập webhookUrl. Appender sẽ tạm thời vô hiệu hóa.");
            return;
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        super.start();
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        // Chỉ gửi thông báo khi mức độ nghiêm trọng là ERROR
        if (eventObject.getLevel() != Level.ERROR) {
            return;
        }

        if (!isStarted() || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            String loggerName = eventObject.getLoggerName();
            String message = eventObject.getFormattedMessage();

            // Trích xuất Request ID từ MDC
            Map<String, String> mdcMap = eventObject.getMDCPropertyMap();
            String requestId = (mdcMap != null && mdcMap.containsKey("requestId"))
                    ? mdcMap.get("requestId")
                    : "NO-REQ-ID";

            // Trích xuất StackTrace (nếu có Exception kèm theo)
            String stackTraceSummary = extractStackTrace(eventObject.getThrowableProxy());

            // Xây dựng JSON payload chuẩn theo định dạng Discord Webhook Embeds
            String payload = buildDiscordPayload(
                    applicationName,
                    requestId,
                    loggerName,
                    message,
                    stackTraceSummary
            );

            // Gửi HTTP POST bất đồng bộ tới Discord Webhook
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 400) {
                            // CẢNH BÁO: Dùng addWarn của AppenderBase, TUYỆT ĐỐI KHÔNG dùng log.error()
                            // để tránh vòng lặp vô hạn (Infinite Recursive Logging Loop)
                            addWarn("Gửi Discord Webhook thất bại: HTTP " + response.statusCode() + " - " + response.body());
                        }
                    })
                    .exceptionally(ex -> {
                        addWarn("Lỗi mạng khi bắn thông báo Discord: " + ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            // Ghi nhận lỗi nội bộ vào hệ thống Logback
            addError("Lỗi xử lý trong DiscordAppender: " + e.getMessage(), e);
        }
    }

    private String extractStackTrace(IThrowableProxy throwableProxy) {
        if (throwableProxy == null) {
            return "Không có ngoại lệ (No Exception)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(throwableProxy.getClassName()).append(": ").append(throwableProxy.getMessage()).append("\n");

        StackTraceElementProxy[] stepArray = throwableProxy.getStackTraceElementProxyArray();
        if (stepArray != null) {
            int maxLines = Math.min(stepArray.length, 6);
            for (int i = 0; i < maxLines; i++) {
                sb.append("    at ").append(stepArray[i].toString()).append("\n");
            }
            if (stepArray.length > 6) {
                sb.append("    ... and ").append(stepArray.length - 6).append(" more lines");
            }
        }

        return escapeJson(sb.toString());
    }

    private String buildDiscordPayload(String app, String reqId, String logger, String msg, String stackTrace) {
        String title = "🚨 [" + app.toUpperCase() + "] ERROR DETECTED";
        String colorRed = "15158332"; // Mã màu #E74C3C

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
                  "name": "📍 Logger Name",
                  "value": "`%s`",
                  "inline": true
                },
                {
                  "name": "💬 Thông điệp lỗi",
                  "value": "```%s```",
                  "inline": false
                },
                {
                  "name": "💥 Exception & StackTrace",
                  "value": "```%s```",
                  "inline": false
                }
              ],
              "footer": {
                "text": "Hệ thống giám sát StoreX DevOps"
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

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }
}
