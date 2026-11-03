package top.twinklestars.koharuskin.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.player.*;
import top.twinklestars.koharuskin.site.SiteOptions;

@Service
@Transactional
public class AdminPlayersService {
    private final AccountMapper accounts;
    private final PlayerMapper players;
    private final PlayerService service;
    private final AdminPlayersMapper admin;
    private final SiteOptions options;

    public AdminPlayersService(AccountMapper accounts, PlayerMapper players, PlayerService service,
                               AdminPlayersMapper admin, SiteOptions options) {
        this.accounts=accounts; this.players=players; this.service=service;
        this.admin=admin; this.options=options;
    }

    private Player authorize(long actorUid, long pid) {
        options.lockRegistration();
        var player=players.find(pid);
        if (player==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"角色不存在");
        checkOwner(actorUid,player.uid());
        return player;
    }
    private void checkOwner(long actorUid, long ownerUid) {
        var actor=accounts.lockById(actorUid);
        var owner=accounts.lockById(ownerUid);
        if (owner==null) throw new BusinessException("角色所属用户不存在");
        if (actor==null || actor.permission()<1 || (ownerUid!=actorUid && owner.permission()>=actor.permission()))
            throw new AccessDeniedException("没有权限管理此用户的角色");
    }

    public void name(long actor, long pid, String name) {
        var player=authorize(actor,pid);
        service.rename(player.uid(),pid,name);
    }
    public void owner(long actor, long pid, long uid) {
        authorize(actor,pid);
        checkOwner(actor,uid);
        admin.owner(pid,uid);
    }
    public void texture(long actor, long pid, String type, int tid) {
        var player=authorize(actor,pid);
        if (tid>0) {
            String textureType=players.textureType(tid);
            if (textureType==null) throw new BusinessException("材质不存在");
            if (type.equals("cape")!=textureType.equals("cape")) throw new BusinessException("皮肤和披风的材质类型不匹配");
        }
        players.textures(pid,type.equals("skin")?tid:player.tidSkin(),type.equals("cape")?tid:player.tidCape());
    }
    public void delete(long actor, long pid) {
        authorize(actor,pid);
        // 原后台允许管理员移除角色，不走用户端的单角色限制或积分退款。
        players.delete(pid);
    }
}
