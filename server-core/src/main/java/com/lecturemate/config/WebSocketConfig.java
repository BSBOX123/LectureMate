package com.lecturemate.config;

import com.lecturemate.websocket.AudioStreamWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** 클라이언트 오디오 스트림 WebSocket 등록 (SPEC §2.1-2). */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final AudioStreamWebSocketHandler audioStreamHandler;
  private final String webClientOrigin;

  public WebSocketConfig(
      AudioStreamWebSocketHandler audioStreamHandler,
      @Value("${lecturemate.web-client-origin}") String webClientOrigin) {
    this.audioStreamHandler = audioStreamHandler;
    this.webClientOrigin = webClientOrigin;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(audioStreamHandler, "/ws/v1/lectures/*/audio")
        .setAllowedOrigins(webClientOrigin);
  }

  /**
   * 오디오 청크(3~5초 PCM)는 기본 버퍼(8KB)보다 크므로 한도를 올린다.
   *
   * <p>이 빈은 실제 서블릿 컨테이너가 있어야 만들 수 있다. MOCK 환경 테스트에서는 ServerContainer 가 없어 실패하므로
   * test 프로필에서는 제외한다.
   */
  @Profile("!test")
  @Bean
  ServletServerContainerFactoryBean webSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxBinaryMessageBufferSize(512 * 1024);
    container.setMaxTextMessageBufferSize(64 * 1024);
    return container;
  }
}
