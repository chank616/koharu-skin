package top.twinklestars.koharuskin.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class AccountSessionFilter extends OncePerRequestFilter {
    private final AccountMapper users;
    private final SecurityContextRepository contexts;
    private final String rememberKey;
    public AccountSessionFilter(AccountMapper users, SecurityContextRepository contexts, String rememberKey) {
        this.users = users; this.contexts = contexts; this.rememberKey = rememberKey;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var context = SecurityContextHolder.getContext();
        var authentication = context.getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountUser previous) {
            var current = users.findById(previous.uid());
            if (current == null || !current.isEnabled() || !current.password().equals(previous.password()) || !current.email().equals(previous.email())) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
            } else if (!current.equals(previous)) {
                var updated = authentication instanceof RememberMeAuthenticationToken
                        ? new RememberMeAuthenticationToken(rememberKey, current, current.getAuthorities())
                        : UsernamePasswordAuthenticationToken.authenticated(current, null, current.getAuthorities());
                updated.setDetails(authentication.getDetails());
                context.setAuthentication(updated);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
