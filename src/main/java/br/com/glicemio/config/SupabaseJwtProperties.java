package br.com.glicemio.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("glicemio.auth.supabase")
public record SupabaseJwtProperties(
	@NotBlank String issuer,
	@NotBlank String jwkSetUri,
	@NotBlank String audience,
	@NotBlank @Pattern(regexp = "ES256") String jwsAlgorithm) {
}
