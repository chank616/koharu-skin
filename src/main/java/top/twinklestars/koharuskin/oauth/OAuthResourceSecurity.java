package top.twinklestars.koharuskin.oauth;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import top.twinklestars.koharuskin.account.AccountMapper;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.*;

@Configuration
public class OAuthResourceSecurity {
    @Bean @Order(4)
    SecurityFilterChain oauthResources(HttpSecurity http,OAuthAuthorizations authorizations,AccountMapper users) throws Exception {
        http.securityMatcher(request->request.getRequestURI().startsWith(request.getContextPath()+"/api/")
                        &&!request.getRequestURI().startsWith(request.getContextPath()+top.twinklestars.koharuskin.yggdrasil.UnionOAuth.ROOT)
                        &&!Set.of(request.getContextPath()+"/api/",request.getContextPath()+"/api/site").contains(request.getRequestURI())
                        &&(request.getHeader("Authorization")!=null||org.springframework.web.cors.CorsUtils.isPreFlightRequest(request)))
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable()).csrf(csrf->csrf.disable())
                .oauth2ResourceServer(server->server.opaqueToken(opaque->opaque.introspector(value->{
                    var authorization=authorizations.findByToken(value,OAuth2TokenType.ACCESS_TOKEN);
                    if(authorization==null||authorization.getAccessToken()==null||!authorization.getAccessToken().isActive()
                            ||authorization.getAttribute("koharu.uid")==null) throw new BadOpaqueTokenException("令牌已失效");
                    var user=users.findById(Long.parseLong(authorization.getAttribute("koharu.uid")));
                    var authorities=new ArrayList<org.springframework.security.core.GrantedAuthority>(user.getAuthorities());
                    authorization.getAccessToken().getToken().getScopes().forEach(scope->authorities.add(new SimpleGrantedAuthority("SCOPE_"+scope)));
                    return new DefaultOAuth2AuthenticatedPrincipal(Long.toString(user.uid()),Map.of("account",user,"sub",Long.toString(user.uid())),authorities);
                }).authenticationConverter((token,principal)->UsernamePasswordAuthenticationToken.authenticated((AccountUser)principal.getAttribute("account"),null,principal.getAuthorities()))))
                .cors(cors->cors.configurationSource(request->{
                    var config=new CorsConfiguration(); config.setAllowedOrigins(List.of("*"));
                    config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS")); config.setAllowedHeaders(List.of("Authorization","Content-Type")); return config;
                }))
                .authorizeHttpRequests(auth->{
                    auth.requestMatchers(HttpMethod.GET,"/api/user").hasAuthority("SCOPE_User.Read")
                            .requestMatchers("/api/user/notifications/**").hasAuthority("SCOPE_Notification.Read")
                            .requestMatchers(HttpMethod.GET,"/api/players","/api/players/**").hasAnyAuthority("SCOPE_Player.Read","SCOPE_Player.ReadWrite")
                            .requestMatchers("/api/players","/api/players/**").hasAuthority("SCOPE_Player.ReadWrite")
                            .requestMatchers(HttpMethod.GET,"/api/closet","/api/closet/**").hasAnyAuthority("SCOPE_Closet.Read","SCOPE_Closet.ReadWrite","SCOPE_Closet.ReadWrtie")
                            .requestMatchers("/api/closet","/api/closet/**").hasAnyAuthority("SCOPE_Closet.ReadWrite","SCOPE_Closet.ReadWrtie");
                    var sections=Map.of("users","UsersManagement","players","PlayersManagement","closet","ClosetManagement","reports","ReportsManagement","notifications","Notification");
                    sections.forEach((path,scope)->auth.requestMatchers("/api/admin/"+path,"/api/admin/"+path+"/**").access((authentication,context)->{
                        var granted=authentication.get().getAuthorities().stream().map(a->a.getAuthority()).toList();
                        boolean allowed=granted.contains("SCOPE_"+scope+".ReadWrite")||(context.getRequest().getMethod().equals("GET")&&granted.contains("SCOPE_"+scope+".Read"));
                        return new AuthorizationDecision(granted.contains("ROLE_ADMIN")&&allowed);
                    }));
                    auth.anyRequest().denyAll();
                });
        return http.build();
    }
}
