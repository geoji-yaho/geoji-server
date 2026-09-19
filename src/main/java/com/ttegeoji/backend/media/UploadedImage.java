package com.ttegeoji.backend.media;

import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 업로드 바이트 검증 결과(10 §16.5). 실제로 디코딩해 보고 통과한 것만 만들어진다.
 *
 * <p>보낸 사람 말(파일명·Content-Type)을 믿지 않는다. 바이트를 직접 읽어 형식을 정하고,
 * 저장 키도 서버가 해시로 짓는다. 같은 파일을 두 번 올리면 키가 같아 카탈로그가 늘지 않는다.
 *
 * @param contentType 바이트에서 정한 형식. 보낸 헤더가 아니다
 * @param sha256      소문자 hex. 저장 키와 중복 판정에 쓴다
 */
public record UploadedImage(byte[] bytes, String contentType, String extension, String sha256,
                            int width, int height) {

    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int MAX_SIDE = 4096;
    static final long MAX_PIXELS = 16L * 1000 * 1000;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

    /**
     * @throws MediaRejection 형식·크기·해상도 중 하나라도 어긋나면
     */
    public static UploadedImage of(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST, "빈 파일입니다.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new MediaRejection(HttpStatus.CONTENT_TOO_LARGE, "이미지는 8MiB 이하여야 합니다.");
        }

        String contentType;
        String extension;
        if (startsWith(bytes, PNG_MAGIC)) {
            contentType = "image/png";
            extension = "png";
        } else if (startsWith(bytes, JPEG_MAGIC)) {
            contentType = "image/jpeg";
            extension = "jpg";
        } else {
            throw new MediaRejection(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PNG 또는 JPEG 만 올릴 수 있습니다.");
        }

        // 헤더만 맞고 내용이 깨진 파일을 거른다. 디코딩에 실패하면 이미지가 아니다
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST, "이미지를 읽을 수 없습니다.");
        }
        if (decoded == null) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST, "이미지를 읽을 수 없습니다.");
        }

        int width = decoded.getWidth();
        int height = decoded.getHeight();
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST, "가로·세로는 각각 4096px 이하여야 합니다.");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new MediaRejection(HttpStatus.BAD_REQUEST, "전체 화소는 16Mpx 이하여야 합니다.");
        }

        return new UploadedImage(bytes, contentType, extension, sha256(bytes), width, height);
    }

    /** 저장 키. 사용자 파일명은 쓰지 않는다(경로 조작 방지, 10 §16.5) */
    public String storageKey(String prefix) {
        String folder = prefix == null || prefix.isBlank() ? "" : prefix.replaceAll("/+$", "") + "/";
        return folder + sha256 + "." + extension;
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
