package com.hardwarebilling.config;

import com.hardwarebilling.domain.AppUser;
import com.hardwarebilling.repo.AppUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService userDetailsService(AppUserRepository repo) {
        return username -> {
            AppUser u = repo.findByUsernameIgnoreCase(username).orElseThrow(() -> new UsernameNotFoundException("bad credentials"));
            return User.withUsername(u.getUsername())
                    .password(u.getPasswordHash())
                    .roles(u.getRole().name())
                    .disabled(!u.isActive())
                    .build();
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginRateLimitFilter rateLimit, SetupRedirectFilter setupFilter) {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/setup/**", "/login", "/brand/logo", "/error", "/css/**", "/js/**", "/webjars/**", "/actuator/health/**", "/favicon.ico").permitAll()
                // read-only users may never change anything (services enforce this again with @PreAuthorize)
                .requestMatchers("/settings/**", "/users/**", "/banks/**", "/audit/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/logout", "/account/password").authenticated()
                .requestMatchers(HttpMethod.POST, "/**").hasAnyRole("ADMIN", "STAFF")
                .anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
            .sessionManagement(s -> s.sessionFixation(f -> f.migrateSession()))
            .headers(h -> h
                .frameOptions(f -> f.sameOrigin())
                .contentTypeOptions(c -> {})
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
            .addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimit, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
