package com.fundaxis.config;

import com.fundaxis.security.CustomAuthenticationSuccessHandler;
import com.fundaxis.security.CustomUserDetailsService;
import com.fundaxis.security.MustChangePasswordFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;
    private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    private final MustChangePasswordFilter mustChangePasswordFilter;

    // Constructor injection
    public SecurityConfig(
            CustomUserDetailsService customUserDetailsService,
            CustomAuthenticationSuccessHandler authenticationSuccessHandler,
            MustChangePasswordFilter mustChangePasswordFilter) {

        this.customUserDetailsService = customUserDetailsService;
        this.authenticationSuccessHandler = authenticationSuccessHandler;
        this.mustChangePasswordFilter = mustChangePasswordFilter;
    }

    ///==================== Password Encoding ====================///

    // PasswordEncoder used to securely hash user passwords, and plain-text passwords must never be stored in the database.
    // BCrypt creates a one-way hash that will be stored in: UserAccount.passwordHash
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    ///==================== Authentication Provider ====================///

    // Bridges CustomUserDetailsService and PasswordEncoder for Spring Security.
    //
    // DaoAuthenticationProvider:
    // 1. Fetches user details (username, password hash, roles) using customUserDetailsService.
    // 2. Verifies the user-entered raw password against the stored BCrypt hash using passwordEncoder().
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authenticationProvider =
                new DaoAuthenticationProvider(customUserDetailsService);

        // Compares the submitted password with the stored BCrypt hash
        authenticationProvider.setPasswordEncoder(passwordEncoder());

        return authenticationProvider;
    }

    ///==================== Security Filter Chains ====================///

    ///---------- API SECURITY (@Order(1)) ----------///
    // Handles requests starting with /api/** using stateless HTTP Basic authentication.
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {

        http
                // This chain only applies to requests starting with /api/
                .securityMatcher("/api/**")

                // Register our database authentication provider
                .authenticationProvider(authenticationProvider())

                // CSRF is disabled for the REST API because it is stateless
                .csrf(csrf -> csrf.disable())

                // Stateless: no HttpSession is created
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Define what endpoints are public or protected
                .authorizeHttpRequests(auth -> auth
                        // Public API endpoints
                        .requestMatchers(
                                "/api/login",
                                "/api/home")
                        .permitAll()

                        // Role-restricted API endpoints
                        .requestMatchers("/api/user-accounts/**").hasAnyRole("ADMIN", "IT_SUPPORT")
                        .requestMatchers("/api/roles/**").hasRole("ADMIN")

                        // All other API endpoints require authentication
                        .anyRequest().authenticated()
                )

                // Enable HTTP Basic Authentication for protected API requests
                .httpBasic(Customizer.withDefaults())

                // Enforce mustChangePassword for API users after authentication
                .addFilterAfter(mustChangePasswordFilter, BasicAuthenticationFilter.class);

        return http.build();
    }

    ///---------- WEB FRONTEND / THYMELEAF SECURITY (@Order(2)) ----------///
    // Handles browser pages, forms, and session-based authentication.
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {

        http
                // Register our database authentication provider
                .authenticationProvider(authenticationProvider())

                // Keep browser CSRF protection enabled and give failed token
                // checks a useful recovery message without exposing internals.
                .csrf(csrf -> csrf.withObjectPostProcessor(
                        new org.springframework.security.config.ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public CsrfFilter postProcess(CsrfFilter filter) {
                                filter.setAccessDeniedHandler((request, response, denied) -> {
                                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                    response.setContentType("text/plain;charset=UTF-8");
                                    response.getWriter().write("Your security token expired or could not be verified. Reload the page and try again. No changes were saved.");
                                });
                                return filter;
                            }
                        }))

                // Define which requests are public and which require authentication
                .authorizeHttpRequests(auth -> auth
                        // Public home page, login page, and static frontend resources
                        .requestMatchers(
                                "/home",
                                "/login",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/forgot-password",
                                "/favicon.ico",
                                "/error")
                        .permitAll()

                        // Pages requiring standard authentication
                        // Only logged-in users can access /change-password
                        .requestMatchers(
                                "/change-password",
                                "/dashboard")
                        .authenticated()

                        // Role-restricted frontend pages
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/it-support/**").hasRole("IT_SUPPORT")
                        .requestMatchers("/user/**").hasRole("USER")

                        // Every other request requires authentication
                        .anyRequest().authenticated()
                )

                // Custom FundAxis form login configuration
                .formLogin(form -> form
                        .loginPage("/login")                              // Custom login page
                        .loginProcessingUrl("/login")                       // Login processing URL
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/home", true)
                        .successHandler(authenticationSuccessHandler)
                        .failureUrl("/login?error")     // Failed login
                        .permitAll()
                )

                // Secure logout configuration
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )

                // Check mustChangePassword after web authentication
                .addFilterAfter(mustChangePasswordFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
