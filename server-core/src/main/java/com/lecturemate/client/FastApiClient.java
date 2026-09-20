package com.lecturemate.client;

import com.lecturemate.config.FastApiProperties;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** FastAPI AI 엔진 호출 (SPEC §2.2). */
@Component
public class FastApiClient {

  private final RestClient restClient;

  public FastApiClient(FastApiProperties properties) {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                // uvicorn(h11)은 h2c 업그레이드를 지원하지 않아 HTTP/1.1 로 고정한다
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build());
    requestFactory.setReadTimeout(properties.readTimeout());
    this.restClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl().toString())
            .requestFactory(requestFactory)
            .build();
  }

  /** SPEC §2.2-1 요청. FastAPI 는 snake_case 를 쓴다. */
  public record PdfParseRequest(Long lecture_id, String pdf_path) {}

  /** SPEC §2.2-1 응답. */
  public record PdfParseResponse(Integer total_pages, String status) {}

  /** SPEC §2.2-2 요청/응답. */
  public record AnalyzeBatchRequest(String audio_path) {}

  public record AnalyzeBatchResponse(String task_id, String status) {}

  public AnalyzeBatchResponse analyzeBatch(Long lectureId, String audioPath) {
    return restClient
        .post()
        .uri("/ai/v1/lectures/{lectureId}/analyze-batch", lectureId)
        .body(new AnalyzeBatchRequest(audioPath))
        .retrieve()
        .body(AnalyzeBatchResponse.class);
  }

  public PdfParseResponse parsePdf(Long lectureId, String pdfPath) {
    return restClient
        .post()
        .uri("/ai/v1/pdf/parse")
        .body(new PdfParseRequest(lectureId, pdfPath))
        .retrieve()
        .body(PdfParseResponse.class);
  }
}
