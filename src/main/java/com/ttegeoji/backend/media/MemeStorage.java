package com.ttegeoji.backend.media;

/** 짤 원본을 올리는 곳. 운영은 S3, 테스트는 가짜 구현을 끼운다 */
public interface MemeStorage {

    /**
     * 같은 키에 같은 바이트를 다시 올려도 된다(해시 키라 내용이 같다).
     *
     * @return 브라우저가 받을 공개 URL
     */
    String put(String key, byte[] bytes, String contentType);
}
