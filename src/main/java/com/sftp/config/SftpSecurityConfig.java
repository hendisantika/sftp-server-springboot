package com.sftp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SftpSecurityConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    @Value("${app.session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    @Value("${app.session.max-sessions:1}")
    private int maxSessions;

    // Web users configuration (format: username:password:role,username:password:role)
    @Value("${app.web.users:}")
    private String webUsersConfig;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/files/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protection - enabled for all state-changing operations
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/**") // Only disable for API endpoints if needed
                )
                // Security headers
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> {
                        })
                        .xssProtection(xss -> xss.disable()) // Modern browsers don't need this
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(permissions -> permissions
                                .policy("geolocation=(), microphone=(), camera=()"))
                )
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers("/", "/home", "/login", "/css/**", "/js/**", "/images/**", "/error").permitAll()
                    .requestMatchers("/files/**").authenticated()
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
                .defaultSuccessUrl("/files", true)
                    .failureUrl("/login?error=true")
            )
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout=true")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .invalidSessionUrl("/login?expired=true")
                    .maximumSessions(maxSessions)
                    .maxSessionsPreventsLogin(false)
                    .expiredUrl("/login?expired=true")
            );

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        List<UserDetails> users = new ArrayList<>();

        if (webUsersConfig == null || webUsersConfig.isBlank()) {
            // Default users for development - should be overridden in production
            users.add(User.builder()
                    .username("user")
                    .password(passwordEncoder().encode("password"))
                    .roles("USER")
                    .build());

            users.add(User.builder()
                    .username("admin")
                    .password(passwordEncoder().encode("adminpass"))
                    .roles("ADMIN", "USER")
                    .build());
        } else {
            // Parse users from configuration: username:password:ROLE,username:password:ROLE
            for (String userEntry : webUsersConfig.split(",")) {
                String[] parts = userEntry.trim().split(":");
                if (parts.length >= 2) {
                    String username = parts[0].trim();
                    String password = parts[1].trim();
                    String role = parts.length > 2 ? parts[2].trim() : "USER";

                    // If password doesn't start with $2a$ (BCrypt prefix), encode it
                    String encodedPassword = password.startsWith("$2a$")
                            ? password
                            : passwordEncoder().encode(password);

                    users.add(User.builder()
                            .username(username)
                            .password(encodedPassword)
                            .roles(role.split("\\|"))
                            .build());
                }
            }
        }

        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
