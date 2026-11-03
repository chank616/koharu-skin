package top.twinklestars.koharuskin.extension.henan;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.player.PlayerNames;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.time.LocalDateTime;

@Service
public class HenanAccounts {
    private final AccountMapper users;
    private final RegistrationMapper registrations;
    private final SiteOptions options;
    private final PlayerNames names;
    private final OfficialExtensions extensions;
    public HenanAccounts(AccountMapper users,RegistrationMapper registrations,SiteOptions options,PlayerNames names,OfficialExtensions extensions) {
        this.users=users; this.registrations=registrations; this.options=options; this.names=names; this.extensions=extensions;
    }
    public void requireExisting(String email) {
        var user=users.findByEmail(email);
        if(user==null) throw new BusinessException("此学校账号尚未注册，请先使用统一认证注册");
        if(user.permission()<0) throw new BusinessException("账号已被封禁");
    }
    @Transactional
    public AccountUser login(String email) {
        options.lockRegistration(); requireExisting(email);
        var user=users.lockById(users.findByEmail(email).uid());
        if(user.permission()<0||!user.email().equalsIgnoreCase(email)) throw new BusinessException("账号信息已变更，请重新登录");
        users.verify(user.uid());
        return user;
    }
    @Transactional
    public AccountUser register(String email,String passwordHash,String playerName,String ip) {
        options.lockRegistration(); extensions.checkEmail(email); names.validate(playerName);
        if(users.findByEmail(email)!=null) throw new BusinessException("此学校账号已经注册，请直接登录");
        if(registrations.countName(playerName)>0) throw new BusinessException("此角色名已被使用");
        if(registrations.countIp(ip)>=options.number("regs_per_ip",3)) throw new BusinessException("此 IP 的注册数量已达上限");
        registrations.create(email,playerName,passwordHash,options.number("user_initial_score",1000),ip,LocalDateTime.now().minusDays(1));
        var user=users.findByEmail(email);
        users.verify(user.uid()); registrations.createPlayer(user.uid(),playerName);
        return user;
    }
}
