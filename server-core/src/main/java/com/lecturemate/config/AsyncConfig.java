package com.lecturemate.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** PDF 파싱 등 백그라운드 작업을 위한 @Async 활성화. */
@Configuration
@EnableAsync
public class AsyncConfig {}
