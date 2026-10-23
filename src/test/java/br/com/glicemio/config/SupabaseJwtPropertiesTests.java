package br.com.glicemio.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SupabaseJwtPropertiesTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void missingJwtConfigurationFailsClosed() {
        context.run(application -> assertThat(application).hasFailed()
                .getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void unsupportedAlgorithmFailsClosed() {
        context.withPropertyValues(
                "glicemio.auth.supabase.issuer=https://test-project.supabase.co/auth/v1",
                "glicemio.auth.supabase.jwk-set-uri=https://test-project.supabase.co/auth/v1/.well-known/jwks.json",
                "glicemio.auth.supabase.audience=authenticated",
                "glicemio.auth.supabase.jws-algorithm=HS256")
                .run(application -> assertThat(application).hasFailed()
                        .getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SupabaseJwtProperties.class)
    static class PropertiesConfiguration {}
}
