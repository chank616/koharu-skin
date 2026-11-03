package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;

@Configuration
public class YggSecurity {
    @Bean @Order(0)
    SecurityFilterChain unionMemberSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(request->{
                    String path=request.getRequestURI().substring(request.getContextPath().length());
                    return (path.equals("/api/union/member")||path.startsWith("/api/union/member/"))
                            &&!(path.equals(UnionOAuth.ROOT)||path.startsWith(UnionOAuth.ROOT+"/"));
                })
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable()).csrf(csrf->csrf.disable())
                .authorizeHttpRequests(auth->auth.anyRequest().permitAll()).build();
    }
    @Bean @Order(1)
    SecurityFilterChain yggdrasil(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/yggdrasil/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .cors(cors -> cors.configurationSource(request -> {
                    var configuration=new CorsConfiguration();
                    configuration.setAllowedOrigins(List.of("*"));
                    configuration.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
                    configuration.setAllowedHeaders(List.of("Content-Type","Authorization"));
                    return configuration;
                })).build();
    }
}
