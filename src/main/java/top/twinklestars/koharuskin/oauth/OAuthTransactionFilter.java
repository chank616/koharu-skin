package top.twinklestars.koharuskin.oauth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class OAuthTransactionFilter extends OncePerRequestFilter {
    private final PlatformTransactionManager transactions;
    public OAuthTransactionFilter(PlatformTransactionManager transactions) { this.transactions=transactions; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var transaction=transactions.getTransaction(new DefaultTransactionDefinition());
        try { chain.doFilter(request,response); transactions.commit(transaction); }
        catch(ServletException|IOException|RuntimeException error) { if(!transaction.isCompleted()) transactions.rollback(transaction); throw error; }
    }
}
