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
 * Filter bắt mọi HTTP Request để thiết lập Request ID vào MDC (Mapped Diagnostic Context).
 * Giúp theo vết toàn bộ log của từng request riêng biệt.
 * Đồng thời dọn dẹp MDC ở khối finally để phòng tránh Memory Leak và Thread Pool Pollution.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_REQUEST_ID_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. Lấy requestId từ header client gửi lên (nếu có)
            String requestId = request.getHeader(REQUEST_ID_HEADER);

            // 2. Nếu không có hoặc để trống, tự sinh mới UUID
            if (!StringUtils.hasText(requestId)) {
                requestId = UUID.randomUUID().toString();
            }

            // 3. Đưa requestId vào MDC context của Thread hiện tại
            MDC.put(MDC_REQUEST_ID_KEY, requestId);

            // 4. Gắn header phản hồi lại cho client
            response.setHeader(REQUEST_ID_HEADER, requestId);

            // 5. Chuyển tiếp request cho filter tiếp theo trong chuỗi
            filterChain.doFilter(request, response);

        } finally {
            // 6. BẮT BUỘC: Dọn dẹp key khỏi MDC trong khối finally khi kết thúc request
            // Tránh việc Thread trong Thread Pool bị nhiễm dữ liệu cũ sang các request sau
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }
}
