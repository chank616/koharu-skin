package top.twinklestars.koharuskin.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.util.List;

@Configuration
public class SecurityConfiguration {
    @Bean
    java.time.Clock clock() { return java.time.Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    AuthenticationManager authenticationManager(AccountService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(HttpSessionCsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    JsonRememberMeServices rememberMeServices(@Value("${koharu.remember-key}") String key, AccountService users) {
        return new JsonRememberMeServices(key, users);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
                                            HttpSessionCsrfTokenRepository csrf, JsonRememberMeServices remember,
                                            @Value("${koharu.remember-key}") String key, AccountMapper users) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/bind").authenticated()
                .requestMatchers("/", "/auth/**", "/app/**", "/api", "/api/", "/api/site", "/error", "/textures/**", "/csl/textures/**", "/raw/**", "/skinlib/info/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/texture/*", "/preview/**", "/avatar/**", "/*.json", "/csl/*.json", "/official/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.HEAD, "/*.json", "/csl/*.json", "/official/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/skinlib", "/skinlib/list", "/skinlib/show/*").permitAll()
                .requestMatchers("/admin/**","/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
                .securityContext(context -> context.securityContextRepository(contexts))
                .addFilterBefore(new AccountSessionFilter(users, contexts, key), org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .csrf(config -> config.csrfTokenRepository(csrf).ignoringRequestMatchers("/api","/api/"))
                .rememberMe(config -> config.key(key).rememberMeServices(remember))
                .exceptionHandling(config -> config.authenticationEntryPoint((request, response, error) -> {
                    if (request.getHeader("Accept") != null && request.getHeader("Accept").contains("application/json")) {
                        response.setStatus(401);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":1,\"message\":\"请先登录\"}");
                    } else response.sendRedirect(request.getContextPath() + "/auth/login");
                }))
                .logout(config -> config.logoutUrl("/auth/logout").logoutSuccessHandler((request, response, auth) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":0,\"message\":\"已退出登录\"}");
                }));
        return http.build();
    }
}
