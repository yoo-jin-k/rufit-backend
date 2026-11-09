package com.rufit.support;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Test classpath only: reject non-test databases before data source or Flyway initialization. */
public class TestDatabaseGuard implements EnvironmentPostProcessor, Ordered {

  static void validate(String url, String username) {
    try {
      if (
        url == null || !url.startsWith("jdbc:postgresql://") || !"rufit_test".equals(username)
      ) throw new IllegalArgumentException();
      URI uri = URI.create(url.substring(5));
      if (
        !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()) ||
        !"/rufit_test".equals(uri.getRawPath()) ||
        uri.getRawUserInfo() != null ||
        uri.getRawQuery() != null ||
        uri.getRawFragment() != null ||
        uri.getPort() == 0 ||
        uri.getPort() > 65535
      ) throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new IllegalStateException(
        "Test DB guard: use a loopback PostgreSQL database and role both named rufit_test; URL overrides are not allowed."
      );
    }
  }

  @Override
  public void postProcessEnvironment(
    ConfigurableEnvironment environment,
    SpringApplication application
  ) {
    String url = environment.getProperty("spring.datasource.url");
    String username = environment.getProperty("spring.datasource.username");
    validate(url, username);
    for (String key : new String[] { "spring.flyway.url", "spring.datasource.hikari.jdbc-url" }) {
      String override = environment.getProperty(key);
      if (override != null && !override.isBlank()) validate(override, username);
    }
    for (String key : new String[] { "spring.flyway.user", "spring.datasource.hikari.username" }) {
      String override = environment.getProperty(key);
      if (override != null && !override.isBlank()) validate(url, override);
    }
    for (String key : new String[] {
      "spring.datasource.jndi-name",
      "spring.datasource.hikari.data-source-class-name",
      "spring.datasource.hikari.data-source-j-n-d-i",
    }) {
      String override = environment.getProperty(key);
      if (override != null && !override.isBlank()) throw new IllegalStateException(
        "Test DB guard: indirect data sources are not allowed in tests."
      );
    }
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }
}
