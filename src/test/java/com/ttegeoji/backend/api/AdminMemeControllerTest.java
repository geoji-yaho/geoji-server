package com.ttegeoji.backend.api;

import com.ttegeoji.backend.media.MediaProperties;
import com.ttegeoji.backend.media.MemeStorage;
import com.ttegeoji.backend.repository.MemeImageRepository;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ttegeoji.backend.api.PostVerdictControllerTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 10 §16.5 관리자 짤 업로드. 저장소는 가짜를 끼워 S3 없이 계약만 본다 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "geoji.media.bucket=test-bucket",
        "geoji.media.region=ap-northeast-2",
        "geoji.media.key-prefix=memes",
        "geoji.media.public-base-url=https://cdn.example.com",
        "geoji.media.admin-ids=" + AdminMemeControllerTest.ADMIN_ID
})
class AdminMemeControllerTest extends PostgresContainerSupport {

    static final String ADMIN_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID ADMIN = UUID.fromString(ADMIN_ID);
    private static final UUID OUTSIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TestConfiguration
    static class Storage {
        @Bean
        @Primary
        MemeStorage fakeMemeStorage(MediaProperties properties) {
            return (key, bytes, contentType) -> {
                RECORDED.add(key);
                return properties.publicUrl(key);
            };
        }
    }

    static final List<String> RECORDED = new ArrayList<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemeImageRepository repository;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "whatever.png", "image/png", bytes);
    }

    @Test
    @DisplayName("10 §16.5 관리자가 PNG 를 올리면 201 과 후보 1건 — 태그·감정·키워드가 그대로 들어간다")
    void uploadsPng() throws Exception {
        byte[] bytes = png(64, 64);
        mockMvc.perform(multipart("/api/admin/memes").file(file(bytes))
                        .param("tag", "NOT_GUILTY")
                        .param("emotions", "CELEBRATION")
                        .param("keywords", "무죄,축하")
                        .with(as(ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tag").value("NOT_GUILTY"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.startsWith("https://cdn.example.com/memes/")));

        assertThat(repository.findByTagAndActiveTrue(com.ttegeoji.backend.domain.enums.MemeTag.NOT_GUILTY))
                .anySatisfy(row -> {
                    assertThat(row.getEmotions()).containsExactly("CELEBRATION");
                    assertThat(row.getKeywords()).containsExactly("무죄", "축하");
                });
    }

    @Test
    @DisplayName("10 §16.5 같은 파일을 다시 올리면 새 후보를 만들지 않고 200 으로 기존 것을 준다")
    void sameFileDoesNotDuplicate() throws Exception {
        byte[] bytes = png(40, 41);
        mockMvc.perform(multipart("/api/admin/memes").file(file(bytes))
                .param("tag", "APPROVED").with(as(ADMIN))).andExpect(status().isCreated());
        long before = repository.count();

        mockMvc.perform(multipart("/api/admin/memes").file(file(bytes))
                        .param("tag", "APPROVED").with(as(ADMIN)))
                .andExpect(status().isOk());

        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("10 §16.5 allowlist 에 없는 사용자는 403 — 파일을 저장소에 올리지 않는다")
    void outsiderForbidden() throws Exception {
        RECORDED.clear();
        mockMvc.perform(multipart("/api/admin/memes").file(file(png(10, 10)))
                        .param("tag", "GUILTY_LIGHT").with(as(OUTSIDER)))
                .andExpect(status().isForbidden());
        assertThat(RECORDED).isEmpty();
    }

    @Test
    @DisplayName("PNG·JPEG 가 아니면 415 — 확장자가 아니라 바이트로 판정한다")
    void rejectsNonImage() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "hack.png", "image/png",
                "이건 이미지가 아니다".getBytes());
        mockMvc.perform(multipart("/api/admin/memes").file(fake)
                        .param("tag", "GUILTY_LIGHT").with(as(ADMIN)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("모르는 tag·emotions 는 400")
    void rejectsUnknownVocabulary() throws Exception {
        mockMvc.perform(multipart("/api/admin/memes").file(file(png(8, 8)))
                        .param("tag", "NOPE").with(as(ADMIN)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/admin/memes").file(file(png(9, 9)))
                        .param("tag", "GUILTY_LIGHT").param("emotions", "기쁨")
                        .with(as(ADMIN)))
                .andExpect(status().isBadRequest());
    }
}
