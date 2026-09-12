package com.rufit.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OidcEndpointValidationTests {
    @Test void productionDecoderRejectsPlainHttpKeySourceBeforeServingRequests() {
        assertThatThrownBy(() -> new OidcSecurityConfig().jwtDecoder("https://identity.rufit.test","http://identity.rufit.test/keys","rufit-api",false))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void localHttpRequiresExplicitOptInAndCannotAllowRemoteHttp() {
        for(String host:new String[]{"localhost","127.0.0.1","[::1]"}) {
            String keys="http://"+host+":8080/keys";
            assertThatThrownBy(() -> OidcEndpoints.validate("https://identity.rufit.test",keys,false)).isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> OidcEndpoints.validate("https://identity.rufit.test",keys,true)).doesNotThrowAnyException();
        }
        for(String host:new String[]{"identity.rufit.test","localhost.example.test","127.0.0.1.example.test","192.168.1.1"})
            assertThatThrownBy(() -> OidcEndpoints.validate("https://identity.rufit.test","http://"+host+"/keys",true))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void issuerAndKeyAddressesRejectAmbiguityWithoutLeakingConfiguredValues() {
        for(String issuer:new String[]{"http://identity.rufit.test","https://identity.rufit.test?x=1","https://identity.rufit.test#x",
            "https://user:secret@identity.rufit.test","https://identity.rufit.test:0","https://identity.rufit.test:65536","https:///missing","invalid"})
            assertThatThrownBy(() -> OidcEndpoints.validate(issuer,"https://keys.rufit.test/jwks",false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        for(String keys:new String[]{"https://user:secret@keys.rufit.test","https://keys.rufit.test#x","file:///tmp/keys","https://keys.rufit.test:65536"})
            assertThatThrownBy(() -> OidcEndpoints.validate("https://identity.rufit.test",keys,false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        assertThatCode(() -> OidcEndpoints.validate("https://identity.rufit.test/tenant","https://keys.rufit.test:443/jwks?tenant=1",false))
            .doesNotThrowAnyException();
    }
}
