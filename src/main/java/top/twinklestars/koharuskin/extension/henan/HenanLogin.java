package top.twinklestars.koharuskin.extension.henan;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.player.PlayerNames;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class HenanLogin {
    public static final Map<String,String> SCHOOLS=Map.of("ncwu","华北水利水电大学","zzu","郑州大学");
    private static final String PREFIX="henan_auth.mfa.";
    private final OfficialExtensions extensions;
    private final HenanAccounts accounts;
    private final NcwuAuth ncwu;
    private final ZzuAuth zzu;
    private final PasswordEncoder passwords;
    private final PlayerNames names;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public HenanLogin(OfficialExtensions extensions,HenanAccounts accounts,NcwuAuth ncwu,ZzuAuth zzu,PasswordEncoder passwords,PlayerNames names,Clock clock) {
        this.extensions=extensions; this.accounts=accounts; this.ncwu=ncwu; this.zzu=zzu; this.passwords=passwords; this.names=names; this.clock=clock;
    }
    public void requireEnabled() { if(!extensions.enabled("auth-henan")) throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    public record Start(AccountUser user,String token) {}
    public record Pending(String operation,String school,String identification,String playerName,String passwordHash,
                          ZzuAuth.Challenge challenge,String destination,Instant expiresAt,Instant sentAt) implements java.io.Serializable {}
    public Start start(String operation,String school,String identification,String password,String sitePassword,String playerName,String ip,HttpSession session) {
        requireEnabled();
        String email=email(school,identification);
        String hash=null;
        if(operation.equals("register")) {
            if(sitePassword==null||sitePassword.length()<8||sitePassword.length()>32) throw new BusinessException("皮肤站密码长度应为 8～32 个字符");
            names.validate(playerName); hash=passwords.encode(sitePassword);
        } else accounts.requireExisting(email);
        var result=school.equals("ncwu")?new ZzuAuth.Login(ncwu.login(identification,password),null,""):zzu.login(identification,password);
        if(result.challenge()!=null) {
            byte[] bytes=new byte[32]; random.nextBytes(bytes); String token=HexFormat.of().formatHex(bytes);
            session.setAttribute(PREFIX+token,new Pending(operation,school,identification,playerName,hash,result.challenge(),result.destination(),clock.instant().plusSeconds(300),null));
            return new Start(null,token);
        }
        if(!result.authenticated()) throw new BusinessException("学校账号或密码错误");
        return new Start(operation.equals("register")?accounts.register(email,hash,playerName,ip):accounts.login(email),null);
    }
    public Pending pending(HttpSession session,String token) {
        requireEnabled();
        if(!token.matches("[a-f0-9]{64}")) return null;
        var pending=(Pending)session.getAttribute(PREFIX+token);
        if(pending!=null&&!clock.instant().isBefore(pending.expiresAt())) { session.removeAttribute(PREFIX+token); return null; }
        return pending;
    }
    public void send(HttpSession session,String token) {
        synchronized(session) {
            var pending=required(session,token);
            if(pending.sentAt()!=null) {
                long remaining=60-Duration.between(pending.sentAt(),clock.instant()).getSeconds();
                if(remaining>0) throw new BusinessException("请等待 "+remaining+" 秒后再发送短信");
            }
            var updated=zzu.send(pending.challenge());
            session.setAttribute(PREFIX+token,new Pending(pending.operation(),pending.school(),pending.identification(),pending.playerName(),pending.passwordHash(),updated,pending.destination(),pending.expiresAt(),clock.instant()));
        }
    }
    public AccountUser verify(HttpSession session,String token,String code,String ip) {
        synchronized(session) {
            var pending=required(session,token);
            if(pending.sentAt()==null) throw new BusinessException("请先发送短信验证码");
            var result=zzu.verifyAndLogin(pending.identification(),pending.challenge(),code);
            if(result==ZzuAuth.Verification.INVALID_CODE) throw new BusinessException("短信验证码错误，请重试");
            session.removeAttribute(PREFIX+token);
            if(!clock.instant().isBefore(pending.expiresAt())) throw new Expired();
            if(result==ZzuAuth.Verification.LOGIN_REJECTED) throw new BusinessException("学校认证失败，请重新登录");
            String email=email(pending.school(),pending.identification());
            return pending.operation().equals("register")?accounts.register(email,pending.passwordHash(),pending.playerName(),ip):accounts.login(email);
        }
    }
    public String cancel(HttpSession session,String token) {
        synchronized(session) {
            var pending=pending(session,token); session.removeAttribute(PREFIX+token);
            return pending==null?"login":pending.operation();
        }
    }
    private Pending required(HttpSession session,String token) {
        var pending=pending(session,token);
        if(pending==null) throw new Expired();
        return pending;
    }
    public static class Expired extends BusinessException { public Expired() { super("安全验证已过期，请重新登录"); } }
    private String email(String school,String identification) {
        if(!SCHOOLS.containsKey(school)) throw new BusinessException("请选择支持的学校");
        if(identification.isBlank()||identification.contains("@")) throw new BusinessException("请输入学号，不含邮箱后缀");
        String email=identification+"@stu."+school+".edu.cn";
        if(email.length()>100) throw new BusinessException("学号过长");
        return email;
    }
}
