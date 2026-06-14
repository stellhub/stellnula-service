package io.github.stellnula;

import io.github.stellnula.config.DataPlaneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(DataPlaneProperties.class)
public class StellnulaServiceApplication {

  public static void main(String[] args) {
    System.setProperty("log.stdout", "true");
    SpringApplication.run(StellnulaServiceApplication.class, args);
  }
}
