package br.com.glicemio.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class SupabaseJwtDecoderTests {

	private static final String ISSUER = "https://test-project.supabase.co/auth/v1";
	private final SupabaseJwtProperties properties = new SupabaseJwtProperties(
		ISSUER,
		"https://test-project.supabase.co/auth/v1/.well-known/jwks.json",
		"authenticated",
		"ES256");
	private JwtEncoder encoder;
	private JwtDecoder decoder;

	@BeforeEach
	void setUp() throws Exception {
		ECKey key = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
		encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));

		DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
			new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()))));
		NimbusJwtDecoder nimbusDecoder = new NimbusJwtDecoder(processor);
		nimbusDecoder.setJwtValidator(new SecurityConfiguration().jwtValidator(properties));
		decoder = nimbusDecoder;
	}

	@Test
	void acceptsValidSignedSupabaseAccessToken() {
		String subject = UUID.randomUUID().toString();

		Jwt jwt = decoder.decode(token(subject, ISSUER, List.of("authenticated"), "authenticated", false,
			Instant.now().plusSeconds(300)));

		assertThat(jwt.getSubject()).isEqualTo(subject);
	}

	@Test
	void rejectsExpiredToken() {
		assertThatThrownBy(() -> decoder.decode(token(UUID.randomUUID().toString(), ISSUER,
			List.of("authenticated"), "authenticated", false, Instant.now().minusSeconds(120))))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void rejectsTokenWithAlteredSignature() {
		String token = token(UUID.randomUUID().toString(), ISSUER, List.of("authenticated"), "authenticated", false,
			Instant.now().plusSeconds(300));

		int signatureCharacter = token.length() - 10;
		String alteredToken = token.substring(0, signatureCharacter) + "x" + token.substring(signatureCharacter + 1);

		assertThatThrownBy(() -> decoder.decode(alteredToken))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void rejectsUnexpectedIssuer() {
		assertThatThrownBy(() -> decoder.decode(token(UUID.randomUUID().toString(),
			"https://another-project.supabase.co/auth/v1", List.of("authenticated"), "authenticated", false,
			Instant.now().plusSeconds(300))))
			.isInstanceOf(JwtException.class);
	}

	@Test
	void rejectsTokenWithoutExpiration() {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(ISSUER).subject(UUID.randomUUID().toString()).audience(List.of("authenticated"))
			.issuedAt(Instant.now().minusSeconds(1)).claim("role", "authenticated").claim("is_anonymous", false)
			.build();
		String token = encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(SignatureAlgorithm.ES256).keyId("test-key").build(), claims)).getTokenValue();
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void rejectsUnexpectedAudienceRoleAnonymousUserAndSubject() {
		assertThatThrownBy(() -> decoder.decode(token(UUID.randomUUID().toString(), ISSUER, List.of("anon"),
			"authenticated", false, Instant.now().plusSeconds(300)))).isInstanceOf(JwtException.class);
		assertThatThrownBy(() -> decoder.decode(token(UUID.randomUUID().toString(), ISSUER, List.of("authenticated"),
			"anon", false, Instant.now().plusSeconds(300)))).isInstanceOf(JwtException.class);
		assertThatThrownBy(() -> decoder.decode(token(UUID.randomUUID().toString(), ISSUER, List.of("authenticated"),
			"authenticated", true, Instant.now().plusSeconds(300)))).isInstanceOf(JwtException.class);
		assertThatThrownBy(() -> decoder.decode(token("not-a-uuid", ISSUER, List.of("authenticated"),
			"authenticated", false, Instant.now().plusSeconds(300)))).isInstanceOf(JwtException.class);
	}

	private String token(String subject, String issuer, List<String> audience, String role, boolean anonymous,
			Instant expiresAt) {
		Instant issuedAt = expiresAt.isBefore(Instant.now())
			? expiresAt.minusSeconds(60)
			: Instant.now().minusSeconds(1);
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject(subject)
			.audience(audience)
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.claim("role", role)
			.claim("is_anonymous", anonymous)
			.build();
		JwsHeader headers = JwsHeader.with(SignatureAlgorithm.ES256).keyId("test-key").build();
		return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
	}
}
