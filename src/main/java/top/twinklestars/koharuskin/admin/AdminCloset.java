package top.twinklestars.koharuskin.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.closet.*;
import top.twinklestars.koharuskin.texture.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.util.*;

@Service
public class AdminCloset {
    private final AccountMapper accounts;
    private final ProfileMapper profiles;
    private final ClosetMapper closet;
    private final TextureMapper textures;
    private final SiteOptions options;
    public AdminCloset(AccountMapper accounts,ProfileMapper profiles,ClosetMapper closet,TextureMapper textures,SiteOptions options) {
        this.accounts=accounts; this.profiles=profiles; this.closet=closet; this.textures=textures; this.options=options;
    }
    private void authorize(long actor,long uid) {
        var admin=accounts.findById(actor);
        if(admin==null||admin.permission()<1) throw new AccessDeniedException("需要管理员权限");
        if(accounts.findById(uid)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
    }
    public List<ClosetItem> list(long actor,long uid) { authorize(actor,uid); return closet.all(uid); }
    @Transactional
    public Map<String,Object> change(long actor,long uid,int tid,boolean add) {
        options.lockRegistration(); authorize(actor,uid);
        var texture=textures.find(tid);
        if(texture==null) throw new BusinessException("材质不存在");
        if(add) {
            if(closet.ids(uid).contains(tid)) throw new BusinessException("衣柜里已有此材质");
            closet.add(uid,tid,texture.name());
        } else {
            if(closet.remove(uid,tid)==0) throw new BusinessException("衣柜里没有此材质");
            closet.clearPlayerTextures(uid,tid);
        }
        // 原后台代操作不改变积分和点赞数。
        return Map.of("code",0,"message","","data",Map.of("user",profiles.find(uid),"texture",texture));
    }
}
