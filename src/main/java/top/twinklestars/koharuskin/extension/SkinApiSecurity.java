package top.twinklestars.koharuskin.extension;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SkinApiSecurity {
    @Bean @Order(2)
    SecurityFilterChain skinApi(HttpSecurity http) throws Exception {
        return http.securityMatcher("/ext/get-uids-by-suffix","/ext/get-avatar-by-game-id","/ext/get-avatar-by-uid")
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable()).csrf(csrf->csrf.disable())
                .authorizeHttpRequests(auth->auth.anyRequest().permitAll()).build();
    }
}
