package br.com.glicemio.config;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(SupabaseJwtProperties.class)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/actuator/health").permitAll()
				.anyRequest().authenticated())
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults())
				.authenticationEntryPoint((request, response, error) -> {
					response.setStatus(401);
					response.setHeader("WWW-Authenticate", "Bearer");
					response.setContentType("application/problem+json");
					response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"A valid bearer token is required.\"}");
				}))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(SupabaseJwtProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
			.jwsAlgorithm(SignatureAlgorithm.ES256)
			.build();
		decoder.setJwtValidator(jwtValidator(properties));
		return decoder;
	}

	OAuth2TokenValidator<Jwt> jwtValidator(SupabaseJwtProperties properties) {
		return new DelegatingOAuth2TokenValidator<>(
			JwtValidators.createDefaultWithIssuer(properties.issuer()),
			new JwtClaimValidator<Instant>(JwtClaimNames.EXP, expiration -> expiration != null),
			new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
				audience -> audience != null && audience.contains(properties.audience())),
			new JwtClaimValidator<String>("role", "authenticated"::equals),
			new JwtClaimValidator<Boolean>("is_anonymous", anonymous -> Boolean.FALSE.equals(anonymous)),
			subjectValidator());
	}

	private OAuth2TokenValidator<Jwt> subjectValidator() {
		OAuth2Error error = new OAuth2Error("invalid_token", "The token subject must be a UUID.", null);
		return jwt -> {
			try {
				UUID.fromString(jwt.getSubject());
				return OAuth2TokenValidatorResult.success();
			}
			catch (IllegalArgumentException | NullPointerException exception) {
				return OAuth2TokenValidatorResult.failure(error);
			}
		};
	}
}
