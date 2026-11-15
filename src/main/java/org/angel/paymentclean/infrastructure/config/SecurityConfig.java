package org.angel.paymentclean.infrastructure.config;

import jakarta.servlet.DispatcherType;
import org.angel.paymentclean.infrastructure.security.JwtFilter;
import org.angel.paymentclean.infrastructure.security.JwtTokenException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Configuration
public class SecurityConfig {
    // 1. Declarar la dependencia del filtro
    private final JwtFilter jwtFilter;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl = "http://localhost:4200";

    // 2. Inyectar JwtFilter y HandlerExceptionResolver mediante el constructor
    public SecurityConfig(
            JwtFilter jwtFilter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.jwtFilter = jwtFilter;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            // 1. Habilitar CORS con el Bean de configuración
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // 2. Deshabilitar CSRF mediante lambda
            .csrf(AbstractHttpConfigurer::disable)

            // 3. Establecer sesión Stateless (indispensable al usar JWT)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 4. Reglas de autorización de endpoints basadas en roles (ADMIN y CLIENT)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(
                            "/api/v1/auth/login",
                            "/api/v1/auth/register",
                            "/api/v1/auth/refresh",
                            "/auth/login",
                            "/auth/register",
                            "/auth/refresh"
                    ).permitAll()
                    .requestMatchers("/api/v1/products/**").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/cart/**", "/api/v1/order/**").hasAnyRole("CLIENT", "ADMIN")
                    .requestMatchers("/error").permitAll()
                    .requestMatchers(
                            "/scalar/**",
                            "/scalar.html",
                            "/v3/api-docs/**",
                            "/v3/api-docs.yaml",
                            "/swagger-ui/**",
                            "/swagger-ui.html"
                    ).permitAll()
                    .requestMatchers("/api/webhooks/stripe").permitAll()
                    .anyRequest().authenticated()
            )

            // 5. Respuestas JSON consistentes para 401 Unauthorized y 403 Forbidden
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint((request, response, authException) -> {
                        Object errorCode = request.getAttribute(JwtFilter.JWT_ERROR_CODE_ATTR);
                        Object errorMessage = request.getAttribute(JwtFilter.JWT_ERROR_MESSAGE_ATTR);
                        AuthenticationException resolvedEx =
                                (errorCode instanceof String code && errorMessage instanceof String msg)
                                        ? new JwtTokenException(code, msg, authException)
                                        : new JwtTokenException("UNAUTHORIZED", "Autenticación requerida o token ausente.", authException);
                        handlerExceptionResolver.resolveException(request, response, null, resolvedEx);
                    })
                    .accessDeniedHandler((request, response, accessDeniedException) ->
                            handlerExceptionResolver.resolveException(request, response, null, accessDeniedException)
                    )
            )

            // 6. Registrar el filtro de JWT antes del filtro de usuario/contraseña
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> originPatterns = new ArrayList<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*"
        ));
        if (frontendUrl != null && !frontendUrl.isBlank() && !originPatterns.contains(frontendUrl.trim())) {
            originPatterns.add(frontendUrl.trim());
        }
        configuration.setAllowedOriginPatterns(originPatterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "X-User-Role", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Registra CorsFilter con máxima prioridad y para todos los DispatcherTypes (incluido ERROR)
     * para garantizar cabeceras CORS incluso en preflight OPTIONS o errores internos del servidor.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsConfigurationSource corsConfigurationSource) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(corsConfigurationSource));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        return registration;
    }
}
