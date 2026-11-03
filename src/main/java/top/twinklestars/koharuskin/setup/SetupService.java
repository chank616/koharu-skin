package top.twinklestars.koharuskin.setup;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.time.*;

@Service
public class SetupService {
    private final SetupMapper users;
    private final SiteOptions options;
    private final PasswordEncoder passwords;
    private final Clock clock;
    public SetupService(SetupMapper users,SiteOptions options,PasswordEncoder passwords,Clock clock) {
        this.users=users;this.options=options;this.passwords=passwords;this.clock=clock;
    }
    public boolean installed() { return options.enabled("koharu_installed",true); }
    public void requirePending() {
        if(installed()||users.hasUsers()) throw new ResponseStatusException(HttpStatus.CONFLICT,"站点已安装，不能重复创建管理员");
    }
    @Transactional
    public void install(String siteName,String email,String nickname,String password,String ip) {
        options.lockRegistration();requirePending();
        users.administrator(email,nickname,passwords.encode(password),options.number("user_initial_score",1000),ip,
                LocalDateTime.now(clock).minusDays(1));
        save("site_name",siteName);save("plugins_enabled","[\"yggdrasil-api\"]");
        options.update("koharu_installed","true");
    }
    private void save(String name,String value) {
        if(options.get(name)==null) options.insert(name,value); else options.update(name,value);
    }
}
