package com.rufit.auth;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProviderConfigurationTests {

  @Test
  void fakeProviderRequiresTestProfileAndExplicitLoopback() {
    var env = new MockEnvironment().withProperty(
      "rufit.social.test-provider-base-url",
      "http://127.0.0.1:8098"
    );
    assertThatThrownBy(() -> new SocialProviders(env)).isInstanceOf(IllegalStateException.class);
    env.setActiveProfiles("test", "social");
    for (String url : List.of(
      "http://evil.test:8098",
      "http://localhost:8098",
      "http://127.0.0.1",
      "http://127.0.0.1:8098/path",
      "http://user@127.0.0.1:8098"
    )) {
      env.setProperty("rufit.social.test-provider-base-url", url);
      assertThatThrownBy(() -> new SocialProviders(env)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void productionProviderDestinationsAreFixedAndMissingRegistrationFailsClosed() {
    var env = new MockEnvironment();
    var providers = new SocialProviders(env);
    assertThatThrownBy(() -> providers.provider("GOOGLE")).isInstanceOfSatisfying(
      AuthFailure.class,
      e -> assertThat(e.code()).isEqualTo("AUTH_PROVIDER_UNAVAILABLE")
    );
    env.setProperty("rufit.social.google.client-id", "example");
    env.setProperty("rufit.social.google.client-secret", "synthetic");
    for (String url : List.of(
      "http://127.0.0.1:8080/api/v1/auth/callback/google",
      "https://rufit.test/other",
      "https://user@rufit.test/api/v1/auth/callback/google",
      "https://rufit.test/api/v1/auth/callback/google?redirect=evil"
    )) {
      env.setProperty("rufit.social.google.callback-uri", url);
      assertThatThrownBy(() -> providers.provider("GOOGLE")).isInstanceOf(AuthFailure.class);
    }
    env.setProperty(
      "rufit.social.google.callback-uri",
      "https://api.rufit.test/api/v1/auth/callback/google"
    );
    env.setProperty("rufit.social.google.token-url", "https://evil.test");
    assertThat(providers.provider("GOOGLE").token()).isEqualTo(
      "https://oauth2.googleapis.com/token"
    );
    assertThat(providers.provider("GOOGLE").toString()).doesNotContain("synthetic");
  }

  @Test
  void appRouteIsServerConfiguredAndNeverTakenFromCaller() {
    var env = new MockEnvironment();
    var providers = new SocialProviders(env);
    for (String url : List.of(
      "http://evil.test",
      "javascript:alert(1)",
      "rufit://auth/callback?x=1",
      "rufit://auth/callback#x",
      "rufit://user@login/callback"
    )) {
      env.setProperty("rufit.social.ios-return-uri", url);
      assertThatThrownBy(() -> providers.returnUri("IOS", "APP_LOGIN")).isInstanceOf(
        AuthFailure.class
      );
    }
    env.setProperty("rufit.social.ios-return-uri", "rufit://auth/callback");
    assertThat(providers.returnUri("IOS", "APP_LOGIN")).isEqualTo("rufit://auth/callback");
    assertThatThrownBy(() -> providers.returnUri("WEB", "APP_LOGIN")).isInstanceOf(
      AuthFailure.class
    );
    assertThatThrownBy(() -> providers.returnUri("IOS", "https://evil.test")).isInstanceOf(
      AuthFailure.class
    );
  }
}
