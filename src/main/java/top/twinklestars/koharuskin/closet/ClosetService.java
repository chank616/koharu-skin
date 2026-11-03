package top.twinklestars.koharuskin.closet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.site.SiteOptions;
import top.twinklestars.koharuskin.texture.TextureMapper;

@Service
public class ClosetService {
    private final ClosetMapper closet;
    private final TextureMapper textures;
    private final PlayerMapper users;
    private final SiteOptions options;
    public ClosetService(ClosetMapper closet, TextureMapper textures, PlayerMapper users, SiteOptions options) {
        this.closet = closet; this.textures = textures; this.users = users; this.options = options;
    }

    @Transactional
    public void add(AccountUser user, int tid, String name) {
        options.lockRegistration();
        var texture = textures.find(tid);
        if (texture == null) throw new BusinessException("材质不存在");
        if (!texture.visible() && texture.uploader() != user.uid() && user.permission() < 1) throw new BusinessException("这是私密材质");
        int score = users.lockScore(user.uid());
        int cost = options.number("score_per_closet_item", 0);
        if (score < cost) throw new BusinessException("积分不足");
        if (users.inCloset(user.uid(), tid) > 0) throw new BusinessException("衣柜里已有此材质");
        closet.add(user.uid(), tid, name);
        users.changeScore(user.uid(), -cost);
        closet.likes(tid, 1);
        if (texture.uploader() != user.uid()) users.changeScore(texture.uploader(), options.number("score_award_per_like", 0));
    }

    @Transactional
    public void remove(long uid, int tid) {
        options.lockRegistration();
        users.lockScore(uid);
        int removed = closet.remove(uid, tid);
        if (removed == 0) throw new BusinessException("衣柜里没有此材质");
        closet.clearPlayerTextures(uid, tid);
        if (options.enabled("return_score", true)) users.changeScore(uid, options.number("score_per_closet_item", 0));
        var texture = textures.find(tid);
        if (texture != null) {
            closet.likes(tid, -1);
            if (texture.uploader() != uid) users.changeScore(texture.uploader(), -options.number("score_award_per_like", 0));
        }
    }

    public void rename(long uid, int tid, String name) {
        if (closet.rename(uid, tid, name) == 0) throw new BusinessException("衣柜里没有此材质");
    }
}
