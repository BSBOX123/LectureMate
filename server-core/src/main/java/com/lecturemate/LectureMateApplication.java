package com.lecturemate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LectureMateApplication {

  public static void main(String[] args) {
    SpringApplication.run(LectureMateApplication.class, args);
  }
}
