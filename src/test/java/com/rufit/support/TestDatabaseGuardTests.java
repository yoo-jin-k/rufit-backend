package com.rufit.support;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

class TestDatabaseGuardTests {

  @Test
  void permitsOnlyExplicitLocalTestDatabaseAndRole() {
    for (String host : new String[] { "localhost", "127.0.0.1", "[::1]" })
      assertThatCode(() ->
        TestDatabaseGuard.validate("jdbc:postgresql://" + host + ":5435/rufit_test", "rufit_test")
      ).doesNotThrowAnyException();
    for (String url : new String[] {
      "jdbc:postgresql://localhost/rufit",
      "jdbc:postgresql://remote.example/rufit_test",
      "jdbc:postgresql://localhost/rufit_test?currentSchema=other",
      "jdbc:postgresql://localhost/rufit_test?user=other",
      "jdbc:postgresql://user:secret@localhost/rufit_test",
      "jdbc:postgresql://localhost/rufit_test#other",
      "jdbc:postgresql://localhost:0/rufit_test",
      "jdbc:postgresql://localhost:65536/rufit_test",
      "jdbc:h2:mem:test",
      "",
      null,
    })
      assertThatThrownBy(() -> TestDatabaseGuard.validate(url, "rufit_test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Test DB guard:");
    assertThatThrownBy(() ->
      TestDatabaseGuard.validate("jdbc:postgresql://localhost/rufit_test", "rufit")
    ).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void checksSeparateMigrationAndPoolOverridesToo() {
    for (String key : new String[] {
      "spring.flyway.url",
      "spring.datasource.hikari.jdbc-url",
      "spring.flyway.user",
      "spring.datasource.hikari.username",
      "spring.datasource.jndi-name",
      "spring.datasource.hikari.data-source-class-name",
    }) {
      var environment = new org.springframework.mock.env.MockEnvironment()
        .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/rufit_test")
        .withProperty("spring.datasource.username", "rufit_test")
        .withProperty(key, "not-a-test-database");
      assertThatThrownBy(() ->
        new TestDatabaseGuard().postProcessEnvironment(
          environment,
          new SpringApplication(Probe.class)
        )
      )
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Test DB guard:");
    }
  }

  @Test
  void registeredGuardRejectsStartupBeforeAnyApplicationBeanIsCreated() {
    Probe.created.set(0);
    SpringApplication application = new SpringApplication(Probe.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    application.setLogStartupInfo(false);
    assertThatThrownBy(() -> {
      try (
        var ignored = application.run(
          "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not_a_test_database",
          "--spring.datasource.username=rufit_test"
        )
      ) {
      }
    })
      .isInstanceOf(IllegalStateException.class)
      .hasMessageStartingWith("Test DB guard:");
    assertThat(Probe.created.get()).isZero();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Probe {

    static final AtomicInteger created = new AtomicInteger();

    @Bean
    Object marker() {
      created.incrementAndGet();
      return new Object();
    }
  }
}
