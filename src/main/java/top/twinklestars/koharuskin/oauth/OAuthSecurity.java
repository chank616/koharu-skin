package top.twinklestars.koharuskin.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.PlatformTransactionManager;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.site.SiteOptions;

@Configuration
public class OAuthSecurity {
    @Bean com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> oauthSigningKeys(top.twinklestars.koharuskin.yggdrasil.YggKeys keys) {
        return (selector,context)->{
            var pair=keys.pair();
            var rsa=new com.nimbusds.jose.jwk.RSAKey.Builder((java.security.interfaces.RSAPublicKey)pair.getPublic())
                    .privateKey(pair.getPrivate()).keyID(OAuthAuthorizations.hash(java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))).build();
            return selector.select(new com.nimbusds.jose.jwk.JWKSet(rsa));
        };
    }
    @Bean AuthorizationServerSettings authorizationServerSettings(@Value("${koharu.public-url}") String publicUrl) {
        return AuthorizationServerSettings.builder().issuer(publicUrl.replaceAll("/+$",""))
                .authorizationEndpoint("/oauth/authorize").tokenEndpoint("/oauth/token")
                .tokenRevocationEndpoint("/oauth/revoke").tokenIntrospectionEndpoint("/oauth/introspect").build();
    }
    @Bean @Order(3)
    SecurityFilterChain oauthAuthorization(HttpSecurity http,SecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf,
                                           AccountMapper users,SiteOptions options,@Value("${koharu.remember-key}") String key,
                                           PlatformTransactionManager transactions,YggConnect connect) throws Exception {
        http.oauth2AuthorizationServer(server->{
            http.securityMatcher(server.getEndpointsMatcher());
            server.authorizationEndpoint(endpoint->endpoint.consentPage("/oauth/consent").authenticationProviders(providers->providers.forEach(provider->{
                // 原插件把 openid 用作转交外部 OIDC 服务的 OAuth 权限；保留标准回调地址与权限校验。
                if(provider instanceof org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider code)
                    code.setAuthenticationValidator(new org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator());
            })));
            server.clientAuthentication(client->client.authenticationProviders(providers->providers.forEach(provider->{
                if(provider instanceof org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider secret)
                    secret.setPasswordEncoder(new org.springframework.security.crypto.password.PasswordEncoder() {
                        private final org.springframework.security.crypto.password.PasswordEncoder delegate=org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
                        @Override public String encode(CharSequence raw) { return delegate.encode(raw); }
                        @Override public boolean matches(CharSequence raw,String encoded) { return delegate.matches(raw,encoded); }
                        // 旧管理页需要显示原密钥，不能在认证时自动改写旧表。
                        @Override public boolean upgradeEncoding(String encoded) { return false; }
                    });
            })));
        });
        http.authorizeHttpRequests(auth->auth.anyRequest().authenticated())
                .securityContext(context->context.securityContextRepository(contexts))
                .csrf(config->config.csrfTokenRepository(csrf))
                .addFilterBefore(new OAuthTransactionFilter(transactions),SecurityContextHolderFilter.class)
                .addFilterBefore(new AccountSessionFilter(users,contexts,key),LogoutFilter.class)
                .addFilterAfter(new OAuthBrowserFilter(csrf,users,options,connect),AccountSessionFilter.class);
        return http.build();
    }
}
