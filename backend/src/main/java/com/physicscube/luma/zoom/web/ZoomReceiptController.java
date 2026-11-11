package com.physicscube.luma.zoom.web;

import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.zoom.ZoomReceiptAdminService;
import com.physicscube.luma.zoom.ZoomReceiptView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ZoomReceiptController {
    private final ZoomReceiptAdminService receipts;

    ZoomReceiptController(ZoomReceiptAdminService receipts) {
        this.receipts = receipts;
    }

    record ReplayRequest(@NotBlank @Size(max = 300) String reason) {
    }

    @GetMapping("/api/v1/zoom/receipts")
    List<ZoomReceiptView> list(JwtAuthenticationToken authentication, @RequestParam(defaultValue = "DEAD") String status,
                               @RequestParam(defaultValue = "50") int limit) {
        return receipts.list(CurrentAccount.from(authentication), status, limit);
    }

    @PostMapping("/api/v1/zoom/receipts/{receiptId}/replay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void replay(JwtAuthenticationToken authentication, @PathVariable UUID receiptId, @Valid @RequestBody ReplayRequest request) {
        receipts.replay(CurrentAccount.from(authentication), receiptId, request.reason());
    }
}
