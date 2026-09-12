package com.rufit.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ClientAccessConfigTests {
    @Test void rejectsWildcardCredentialsPathsAndNonHttpOrigins() {
        for (String origin : new String[]{"*", "https://*.example.test", "https://app.test/path", "https://app.test/",
            "https://user:pass@app.test", "https://app.test?q=1", "https://app.test#x", "file:///tmp/app", "null"})
            assertThrows(IllegalArgumentException.class, () -> ClientAccessConfig.validateOrigin(origin), origin);
        assertDoesNotThrow(() -> ClientAccessConfig.validateOrigin("http://localhost:8081"));
    }
    @Test void emptyConfigurationRejectsCrossOriginAndOidcNeverAllowsCookiesEvenWithLocalProfile() {
        var config = new ClientAccessConfig();
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        var empty = config.corsConfigurationSource("", new MockEnvironment()).getCorsConfiguration(request);
        assertNotNull(empty);
        assertNull(empty.checkOrigin("https://app.test"));
        var local = new MockEnvironment().withProperty("unused", "unused");
        local.setActiveProfiles("local");
        assertTrue(config.corsConfigurationSource("http://localhost:8081", local).getCorsConfiguration(request).getAllowCredentials());
        local.setActiveProfiles("local", "oidc");
        assertFalse(config.corsConfigurationSource("http://localhost:8081", local).getCorsConfiguration(request).getAllowCredentials());
    }
}
