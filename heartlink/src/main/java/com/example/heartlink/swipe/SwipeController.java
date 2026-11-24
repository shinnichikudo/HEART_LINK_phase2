package com.example.heartlink.swipe;

import com.example.heartlink.common.security.CurrentUser;
import com.example.heartlink.swipe.dto.SwipeRequest;
import com.example.heartlink.swipe.dto.SwipeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/swipes")
@SecurityRequirement(name = "bearerAuth")
public class SwipeController {
    private final SwipeService service;

    public SwipeController(SwipeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Quẹt like/pass", description = "Mỗi hồ sơ chỉ quẹt một lần. Gửi Idempotency-Key ngay từ lần đầu để retry trả lại kết quả gốc. Key có phạm vi theo tài khoản; dùng lại key với target_id/action khác trả 409 IDEMPOTENCY_KEY_REUSED. Nếu truyền key ở cả header và body thì hai giá trị phải giống nhau.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Đã lưu lượt quẹt (hoặc trả lại kết quả đã tạo nếu retry)"),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ hoặc tự quẹt"),
            @ApiResponse(responseCode = "401", description = "Chưa xác thực"),
            @ApiResponse(responseCode = "404", description = "Hồ sơ đích không tồn tại"),
            @ApiResponse(responseCode = "409", description = "ALREADY_SWIPED hoặc IDEMPOTENCY_KEY_REUSED")
    })
    public SwipeResponse swipe(
            @Parameter(hidden = true) @RequestAttribute(CurrentUser.ID) Long userId,
            @Parameter(description = "Key gồm 1–128 ký tự ASCII hiển thị, không chứa khoảng trắng", required = false,
                    schema = @Schema(minLength = 1, maxLength = 128, pattern = "[!-~]+"))
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SwipeRequest request) {
        return service.swipe(userId, request, idempotencyKey);
    }
}
