package com.rufit.auth;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VerifiedLinkHintTests {
  @Test void kakaoReauthenticationDoesNotRequireEmailPermission() {
    var kakao=new SocialProviders.Provider("KAKAO","issuer","https://kauth.kakao.com/oauth/authorize","token","jwks","client","secret","callback",true);
    assertThat(kakao.authorize("state","nonce","challenge"))
      .contains("prompt=login", "scope=openid").doesNotContain("account_email");
    assertThat(kakao.authorize("state","nonce","challenge",true)).contains("account_email");
    var google=new SocialProviders.Provider("GOOGLE","issuer","https://accounts.google.com","token","jwks","client","secret","callback",true);
    assertThat(google.authorize("state","nonce","challenge")).doesNotContain("prompt=");
  }
  @Test void verifiedKakaoConflictReturnsEmailWithoutLoggingIt() {
    var error = AuthFailure.verifiedLinkConflict("KAKAO", "member@example.test");
    var response = new AuthErrors().failure(error);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody().getProperties()).containsEntry("verifiedKakaoEmail", "member@example.test");
    assertThat(error.toString()).doesNotContain("member@example.test");
  }
  @Test void unverifiedErrorsAndOtherProvidersNeverExposeIdentity() {
    for (var error : new AuthFailure[] {AuthFailure.proof(), new AuthFailure(409,"LOGIN_METHOD_CONFLICT"),
        AuthFailure.verifiedLinkConflict("GOOGLE","123456789"), AuthFailure.verifiedLinkConflict("KAKAO","invalid")}) {
      assertThat(error.verifiedKakaoEmail()).isNull();
      assertThat(new AuthErrors().failure(error).getBody().getProperties()).doesNotContainKey("verifiedKakaoMemberId");
    }
  }
  @Test void missingEmailIsExplicitAndMemberNumberIsNotEmail() {
    for (String value : new String[] {"123456789", "invalid", "bad\n@example.test", "a".repeat(255)+"@example.test"}) {
      assertThat(ProviderGateway.displayEmail(value)).isNull();
      var error=AuthFailure.verifiedLinkConflict("KAKAO",value);
      assertThat(new AuthErrors().failure(error).getBody().getProperties())
        .containsEntry("kakaoEmailUnavailable",true).doesNotContainKey("verifiedKakaoEmail");
    }
    assertThat(ProviderGateway.displayEmail(null)).isNull();
    assertThat(ProviderGateway.displayEmail(123)).isNull();
  }
}
