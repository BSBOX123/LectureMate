package com.lecturemate.client;

import com.lecturemate.config.FastApiProperties;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import org.springframework.http.MediaType;
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
  public record MaterialParseRequest(Long material_id, Long course_id, String pdf_path) {}

  /** SPEC §2.2-1 응답. */
  public record MaterialParseResponse(Integer total_pages, String status) {}

  public MaterialParseResponse parseMaterial(Long materialId, Long courseId, String pdfPath) {
    return restClient
        .post()
        .uri("/ai/v1/materials/parse")
        .body(new MaterialParseRequest(materialId, courseId, pdfPath))
        .retrieve()
        .body(MaterialParseResponse.class);
  }

  /** SPEC §2.2-2 요청/응답. */
  public record TranscribeRequest(Long course_id, String audio_path) {}

  public record TranscribeResponse(String task_id, String status) {}

  public TranscribeResponse transcribe(Long recordingId, Long courseId, String audioPath) {
    return restClient
        .post()
        .uri("/ai/v1/recordings/{recordingId}/transcribe", recordingId)
        .body(new TranscribeRequest(courseId, audioPath))
        .retrieve()
        .body(TranscribeResponse.class);
  }

  /** SPEC §2.2-5 요청/응답 (녹음 요약). 동기 호출이며 실측 35초 정도다. */
  public record SummarizeRequest(String title) {}

  public record SummarizeResponse(String summary) {}

  public SummarizeResponse summarize(Long recordingId, String title) {
    return restClient
        .post()
        .uri("/ai/v1/recordings/{recordingId}/summarize", recordingId)
        .body(new SummarizeRequest(title))
        .retrieve()
        .body(SummarizeResponse.class);
  }

  /** SPEC §2.2-3 요청. */
  public record RagQueryRequest(
      Long course_id, String question, Integer top_k, java.util.List<ChatTurn> history) {}

  /** 이전 대화 한 마디. 서버는 대화를 저장하지 않고 클라이언트가 매번 보낸다. */
  public record ChatTurn(String role, String text) {}

  /**
   * RAG SSE 스트림을 그대로 읽어 소비자에게 넘긴다 (SPEC §2.2-3 → §2.1-11 중계).
   *
   * <p>이벤트를 해석하지 않고 바이트를 그대로 흘려보내므로, 형식이 바뀌어도 중계는 영향을 받지 않는다.
   */
  public void streamRagQuery(
      Long courseId,
      String question,
      int topK,
      java.util.List<ChatTurn> history,
      OutputStream out) {
    restClient
        .post()
        .uri("/ai/v1/rag/query")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .body(new RagQueryRequest(courseId, question, topK, history))
        .exchange(
            (request, response) -> {
              try (InputStream in = response.getBody()) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                  out.write(buffer, 0, read);
                  out.flush(); // 버퍼링하면 스트리밍 의미가 사라진다
                }
              }
              return null;
            });
  }
}
