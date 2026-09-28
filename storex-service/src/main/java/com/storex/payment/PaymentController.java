package com.storex.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller mô phỏng các hoạt động thanh toán và phục vụ kiểm thử:
 * 1. GET /api/payment/test-debug: Kiểm thử thay đổi Log Level Runtime với Actuator (Bài 1)
 * 2. GET /api/payment/test-error: Kiểm thử Custom Discord Appender bắn lỗi (Bài 3)
 * Cả 2 API đều tự động in Request ID theo vết từ MDC (Bài 2).
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    /**
     * Endpoint kiểm thử Bài 1:
     * - Ban đầu log level của package com.storex.payment là INFO, log DEBUG này sẽ KHÔNG hiển thị.
     * - Sau khi gửi request POST /actuator/loggers/com.storex.payment đổi sang DEBUG, log này sẽ lập tức xuất hiện.
     */
    @GetMapping("/test-debug")
    public ResponseEntity<Map<String, Object>> testDebug() {
        // Ghi log mức DEBUG
        log.debug("DEBUG: Đang xử lý giao dịch thanh toán chi tiết tại com.storex.payment.PaymentController. Check balance, checksum verify OK.");

        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Đã thực thi test-debug. Hãy kiểm tra console xem dòng log DEBUG có xuất hiện không.");
        response.put("hint", "Nếu chưa thấy log, hãy gọi POST /actuator/loggers/com.storex.payment với payload {'configuredLevel': 'DEBUG'}");

        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint kiểm thử Bài 3:
     * - Cố ý ghi 1 dòng log ERROR kèm Exception.
     * - Logback DiscordAppender sẽ bắt sự kiện này và bắn HTTP POST Webhook lên Discord.
     */
    @GetMapping("/test-error")
    public ResponseEntity<Map<String, Object>> testError() {
        try {
            // Giả lập một lỗi ngoại lệ nghiêm trọng khi gọi Cổng thanh toán
            simulatePaymentGatewayTimeout();
        } catch (Exception ex) {
            // In log mức ERROR kèm theo Exception và StackTrace
            log.error("ERROR: Giao dịch thanh toán thất bại nghiêm trọng do Cổng thanh toán Timeout!", ex);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "ERROR");
            errorResponse.put("message", "Đã bắt gặp ngoại lệ thanh toán và ghi log ERROR.");
            errorResponse.put("discordAlert", "Sự kiện lỗi này đã được DiscordAppender gửi tới Webhook.");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }

        return ResponseEntity.ok(Map.of("status", "SUCCESS"));
    }

    private void simulatePaymentGatewayTimeout() {
        throw new RuntimeException("Simulated Payment Gateway Connection Timeout (Error 504) - VNPay/Momo Service Unreachable");
    }
}
