package top.twinklestars.koharuskin.report;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.player.PlayerService;
import top.twinklestars.koharuskin.site.SiteOptions;
import top.twinklestars.koharuskin.texture.*;
import java.util.*;

@Service
public class ReportService {
    private final ReportMapper reports;
    private final TextureMapper textures;
    private final TextureService textureService;
    private final PlayerMapper users;
    private final AccountMapper accounts;
    private final ProfileMapper profiles;
    private final SiteOptions options;
    private final PlayerService players;
    public ReportService(ReportMapper reports,TextureMapper textures,TextureService textureService,PlayerMapper users,
                         AccountMapper accounts,ProfileMapper profiles,SiteOptions options,PlayerService players) {
        this.reports=reports; this.textures=textures; this.textureService=textureService; this.users=users;
        this.accounts=accounts; this.profiles=profiles; this.options=options;
        this.players=players;
    }

    @Transactional
    public void submit(AccountUser user,int tid,String reason) {
        options.lockRegistration();
        players.requireVerified(user.uid());
        var texture=textureService.readable(tid,user);
        if(reports.exists(user.uid(),tid)>0) throw new BusinessException("你已经举报过此材质");
        int change=options.number("reporter_score_modification",0);
        int balance=users.lockScore(user.uid());
        if(change<0&&(long)balance<-(long)change) throw new BusinessException("积分不足，无法提交举报");
        users.changeScore(user.uid(),change);
        reports.insert(tid,texture.uploader(),user.uid(),reason);
    }

    @Transactional
    public int review(AccountUser actor,long id,String action) {
        options.lockRegistration();
        var admin=accounts.lockById(actor.uid());
        if(admin==null||admin.permission()<1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var report=reports.lock(id);
        if(report==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"举报不存在");
        int change=options.number("reporter_score_modification",0);
        boolean pending=report.status()==0;
        if(action.equals("reject")) {
            if(pending&&change>0) users.changeScore(report.reporter(),-change);
            reports.status(id,2); return 2;
        }
        boolean award=true;
        if(action.equals("delete")) {
            if(textures.find(report.tid())!=null) textureService.delete(admin,report.tid());
            else award=false;
        } else if(action.equals("ban")) {
            var uploader=accounts.lockById(report.uploader());
            if(uploader==null) throw new BusinessException("上传者账号不存在");
            if(admin.permission()<=uploader.permission()) throw new BusinessException("没有权限封禁此用户");
            // 与管理后台一致，封禁后现有网页会话和外置登录令牌都会失效。
            users.ban(report.uploader());
        } else throw new BusinessException("未知审核操作");
        if(pending) {
            if(change<0) users.changeScore(report.reporter(),-change);
            if(award) users.changeScore(report.reporter(),options.number("reporter_reward_score",0));
        }
        reports.status(id,1); return 1;
    }

    public Map<String,Object> manage(String search,int page) {
        var query=top.twinklestars.koharuskin.admin.AdminSearch.parse(top.twinklestars.koharuskin.admin.AdminSearch.ListType.REPORTS,search);
        int current=Math.max(1,page); long total=reports.count(query);
        var data=reports.list(query,9,(current-1L)*9).stream().map(report->{
            var row=new LinkedHashMap<String,Object>();
            row.put("id",report.id()); row.put("tid",report.tid()); row.put("uploader",report.uploader()); row.put("reporter",report.reporter());
            row.put("reason",report.reason()); row.put("status",report.status()); row.put("report_at",report.reportAt());
            row.put("texture",textures.find(report.tid())); row.put("texture_uploader",profiles.find(report.uploader())); row.put("informer",profiles.find(report.reporter()));
            return row;
        }).toList();
        return Map.of("data",data,"current_page",current,"last_page",Math.max(1,(total+8)/9),"total",total,"per_page",9);
    }
    public List<ReportMapper.Report> mine(long uid,int page) { return reports.list(mineQuery(uid),10,(Math.max(1,page)-1L)*10); }
    public long minePages(long uid) { return Math.max(1,(reports.count(mineQuery(uid))+9)/10); }
    private top.twinklestars.koharuskin.admin.AdminSearch mineQuery(long uid) {
        return top.twinklestars.koharuskin.admin.AdminSearch.parse(top.twinklestars.koharuskin.admin.AdminSearch.ListType.REPORTS,"reporter:"+uid);
    }
}
