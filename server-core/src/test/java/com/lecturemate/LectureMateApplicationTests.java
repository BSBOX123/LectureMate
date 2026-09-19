package com.lecturemate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 로컬 postgres 컨테이너가 떠 있어야 한다 ({@code docker compose up -d postgres}). */
@SpringBootTest
class LectureMateApplicationTests {

  @Test
  void contextLoads() {}
}
