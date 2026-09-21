package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SigV4 签名器。
 *
 * <p>R2 / S3 的 API 不接受静态 Bearer 令牌，GetObject 必须带按请求算出的
 * {@code AWS4-HMAC-SHA256} 签名头，所以这里必须和自己的实现之外的东西对齐。</p>
 *
 * <p>用 AWS 官方文档的 IAM {@code ListUsers} 示例做锚点：输入（访问密钥、密钥、
 * us-east-1、2015-08-30T12:36:00Z、空体 SHA-256）与期望签名都来自文档，
 * 期望值另用一份独立实现（Node 的 crypto）按文档算法算出，避免自证。</p>
 */
class AwsSigV4SignerTest {

    /** AWS 文档 IAM 示例的固定输入。 */
    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String EMPTY_BODY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Instant SIGNING_TIME = Instant.parse("2015-08-30T12:36:00Z");

    private static final String EXPECTED =
        "AWS4-HMAC-SHA256 "
            + "Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, "
            + "SignedHeaders=content-type;host;x-amz-date, "
            + "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7";

    @Test
    void matchesTheDocumentedIamVector() {
        // AWS 文档示例的完整输入：路径 /、查询串 Action=ListUsers&Version=2010-05-08，
        // 且只签 content-type、host、x-amz-date（没有 content-sha256 头）。
        var signer = new AwsSigV4Signer(ACCESS_KEY, SECRET_KEY, "us-east-1", "iam",
            EMPTY_BODY_SHA256, false);

        String header = signer.authorizationHeader("GET",
            URI.create("https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08"),
            Map.of("content-type", "application/x-www-form-urlencoded; charset=utf-8"),
            SIGNING_TIME);

        assertThat(header).isEqualTo(EXPECTED);
    }

    @Test
    void signsS3GetWithRegionAutoAndUnsignedPayload() {
        var signer = new AwsSigV4Signer("AKIDEXAMPLE", SECRET_KEY);

        String header = signer.authorizationHeader("GET",
            URI.create("https://bucket.abc.r2.cloudflarestorage.com/a.webm"), Map.of(),
            SIGNING_TIME);

        assertThat(header)
            .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/auto/s3/aws4_request, ")
            .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date")
            .containsPattern("Signature=[0-9a-f]{64}$");
    }

    @Test
    void includesRangeInSignedHeadersSoPartialRequestsWork() {
        var signer = new AwsSigV4Signer("AKIDEXAMPLE", SECRET_KEY);

        String header = signer.authorizationHeader("GET",
            URI.create("https://bucket.abc.r2.cloudflarestorage.com/demo.webm"),
            Map.of("range", "bytes=0-2047"), SIGNING_TIME);

        assertThat(header)
            .contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date");
    }

    @Test
    void sameInputsProduceTheSameSignature() {
        var signer = new AwsSigV4Signer("key", "secret");
        URI uri = URI.create("https://bucket.abc.r2.cloudflarestorage.com/a.webm?x=1");

        String first = signer.authorizationHeader("GET", uri, Map.of(), SIGNING_TIME);
        String second = signer.authorizationHeader("GET", uri, Map.of(), SIGNING_TIME);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void canonicalisesUriAndQuery() {
        assertThat(AwsSigV4Signer.canonicalUri(URI.create("https://h/a/b%20c.webm")))
            .isEqualTo("/a/b%20c.webm");
        assertThat(AwsSigV4Signer.canonicalUri(URI.create("https://h"))).isEqualTo("/");
        assertThat(AwsSigV4Signer.canonicalQuery(URI.create("https://h/p"))).isEmpty();
        // 参数按名字排序，空值写成 name=
        assertThat(AwsSigV4Signer.canonicalQuery(URI.create("https://h/p?b=2&a=1&c"))).isEqualTo("a=1&b=2&c=");
        assertThat(AwsSigV4Signer.canonicalQuery(URI.create("https://h/p?k=a%20b")))
            .isEqualTo("k=a%20b");
    }

    @Test
    void includesPortInSignedHostWhenNotDefault() {
        var signer = new AwsSigV4Signer("key", "secret");
        String header = signer.authorizationHeader("GET",
            URI.create("https://minio.example.com:9000/a.webm"), Map.of(), SIGNING_TIME);

        // host 参与签名；端口非默认时必须带上，否则与存储端算出的签名不一致。
        assertThat(header).contains("SignedHeaders=host;");
        assertThat(signer.authorizationHeader("GET", URI.create("https://minio.example.com/a.webm"),
            Map.of(), SIGNING_TIME)).isNotEqualTo(header);
    }
}

