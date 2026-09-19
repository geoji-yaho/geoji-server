package com.ttegeoji.backend.api;

import com.ttegeoji.backend.media.MediaRejection;
import com.ttegeoji.backend.media.MemeUploadService;
import com.ttegeoji.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 10 §16.5 관리자 짤 업로드. 모델을 부르지 않아 OpenAI·xAI 키 없이 동작한다.
 * 권한은 JWT 확인 뒤 geoji.media.admin-ids allowlist 로 한 번 더 본다.
 */
@RestController
@RequiredArgsConstructor
public class AdminMemeController {

    private final MemeUploadService service;

    @PostMapping(value = "/api/admin/memes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam("tag") String tag,
                                    @RequestParam(name = "strategies", required = false) String strategies,
                                    @RequestParam(name = "emotions", required = false) String emotions,
                                    @RequestParam(name = "keywords", required = false) String keywords) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return message(HttpStatus.BAD_REQUEST, "파일을 읽지 못했습니다.");
        }

        MemeUploadService.Uploaded uploaded;
        try {
            uploaded = service.upload(CurrentUser.idOf(jwt), bytes, tag, strategies, emotions, keywords);
        } catch (MediaRejection e) {
            return message(e.getStatus(), e.getMessage());
        }

        // 같은 파일을 다시 올렸으면 새로 만든 것이 아니라 200 이다
        return ResponseEntity.status(uploaded.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(Map.of(
                        "id", uploaded.id().toString(),
                        "tag", uploaded.tag(),
                        "imageUrl", uploaded.imageUrl(),
                        "assetKey", uploaded.assetKey(),
                        "active", uploaded.active()));
    }

    private static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
