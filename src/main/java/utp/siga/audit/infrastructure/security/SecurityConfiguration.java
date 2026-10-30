package utp.siga.audit.infrastructure.security;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import utp.siga.audit.presentation.error.Problems;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${audit.security.jwks-uri}") String jwks,
            @Value("${audit.security.issuer}") String issuer,
            @Value("${audit.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> jwt.getAudience().contains(audience)
                && jwt.getExpiresAt() != null && validUuidSubject(jwt.getSubject())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    public SecurityFilterChain security(HttpSecurity http) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/audit-events")
                    .hasAuthority("AUDIT_READ")
                .requestMatchers(HttpMethod.GET, "/api/v1/notifications", "/api/v1/notifications/unread-count")
                    .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/notifications/*/read")
                    .authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, x) -> Problems.write(req, res, 401, "AUTH_INVALID"))
                .accessDeniedHandler((req, res, x) -> Problems.write(req, res, 403, "AUTH_FORBIDDEN")))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((req, res, x) -> Problems.write(req, res, 401, "AUTH_INVALID"))
                .accessDeniedHandler((req, res, x) -> Problems.write(req, res, 403, "AUTH_FORBIDDEN")));

        return http.build();
    }

    private static boolean validUuidSubject(String subject) {
        try {
            UUID.fromString(subject);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
