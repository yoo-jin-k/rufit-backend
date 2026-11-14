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
  @Test
  void webRouteIsOptInAndRestrictedToExactLocalCallback() {
    var env = new MockEnvironment();
    var providers = new SocialProviders(env);
    env.setProperty("rufit.social.web-return-uri", "http://localhost:8100/auth/callback.html");
    assertThatThrownBy(() -> providers.returnUri("WEB", "WEB_LOGIN")).isInstanceOf(AuthFailure.class);
    env.setProperty("rufit.social.web-enabled", "true");
    assertThat(providers.returnUri("WEB", "WEB_LOGIN"))
      .isEqualTo("http://localhost:8100/auth/callback.html");
    assertThatThrownBy(() -> providers.returnUri("IOS", "WEB_LOGIN")).isInstanceOf(AuthFailure.class);
    for (String value : List.of("https://evil.test/auth/callback.html", "http://localhost.evil:8100/auth/callback.html",
      "http://localhost:8100/other", "http://localhost:8100/auth/callback.html?next=evil",
      "http://user@localhost:8100/auth/callback.html", "http://localhost/auth/callback.html", "")) {
      env.setProperty("rufit.social.web-return-uri", value);
      assertThatThrownBy(() -> providers.returnUri("WEB", "WEB_LOGIN")).isInstanceOf(AuthFailure.class);
    }
  }

  @Test
  void deployedWebCallbackMustUseHttpsOriginRegisteredForCors() {
    var env = new MockEnvironment();
    var providers = new SocialProviders(env);
    env.setProperty("rufit.social.web-enabled", "true");
    env.setProperty("rufit.social.web-return-uri", "https://app.rufit.test/auth/callback.html");
    // CORS 허용 목록에 없으면 https 주소라도 거절합니다.
    assertThatThrownBy(() -> providers.returnUri("WEB", "WEB_LOGIN")).isInstanceOf(AuthFailure.class);
    env.setProperty("rufit.allowed-origins", "http://localhost:8081, https://app.rufit.test");
    assertThat(providers.returnUri("WEB", "WEB_LOGIN")).isEqualTo("https://app.rufit.test/auth/callback.html");
    for (String value : List.of("http://app.rufit.test/auth/callback.html", "https://app.rufit.test.evil/auth/callback.html",
      "https://app.rufit.test:8443/auth/callback.html", "https://app.rufit.test/other",
      "https://app.rufit.test/auth/callback.html?next=evil", "https://user@app.rufit.test/auth/callback.html")) {
      env.setProperty("rufit.social.web-return-uri", value);
      assertThatThrownBy(() -> providers.returnUri("WEB", "WEB_LOGIN")).isInstanceOf(AuthFailure.class);
    }
  }
}
