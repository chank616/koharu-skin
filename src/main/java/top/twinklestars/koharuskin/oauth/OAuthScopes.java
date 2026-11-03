package top.twinklestars.koharuskin.oauth;

import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class OAuthScopes {
    private final OAuthClientMapper clients;
    private final top.twinklestars.koharuskin.extension.OfficialExtensions extensions;
    public OAuthScopes(OAuthClientMapper clients,top.twinklestars.koharuskin.extension.OfficialExtensions extensions) { this.clients=clients; this.extensions=extensions; }
    public static final String SELECT="Yggdrasil.PlayerProfiles.Select", READ="Yggdrasil.PlayerProfiles.Read", JOIN="Yggdrasil.Server.Join";
    public static final java.util.Set<String> YGG=java.util.Set.of("openid","profile","email","offline_access",SELECT,READ,JOIN);
    public static boolean validYgg(java.util.Set<String> scopes) {
        return java.util.Collections.disjoint(scopes,YGG)||(scopes.contains("openid")
                &&!(scopes.contains(SELECT)&&scopes.contains(READ))&&(!scopes.contains(JOIN)||scopes.contains(SELECT)));
    }
    public record Scope(String id,String description) {}

    public List<Scope> list() {
        var scopes=new LinkedHashMap<String,String>();
        scopes.put("User.Read","读取账号资料");
        scopes.put("Notification.Read","读取站内通知并标记已读");
        scopes.put("Notification.ReadWrite","发送站内通知（需要管理员权限）");
        for(var entry:List.of(new Scope("Player","自己的角色"),new Scope("Closet","自己的衣柜"),
                new Scope("UsersManagement","站点用户（需要管理员权限）"),new Scope("PlayersManagement","站点角色（需要管理员权限）"),
                new Scope("ClosetManagement","用户衣柜（需要管理员权限）"),new Scope("ReportsManagement","举报记录（需要管理员权限）"))) {
            scopes.put(entry.id()+".Read","读取"+entry.description());
            scopes.put(entry.id()+".ReadWrite","读取和修改"+entry.description());
        }
        // 原站注册的权限名有此拼写，保留旧应用的兼容入口。
        scopes.put("Closet.ReadWrtie","读取和修改自己的衣柜（旧版权限名）");
        for(var scope:clients.scopes()) scopes.put(scope.id(),scope.description());
        if(extensions.enabled("yggdrasil-connect")) {
            scopes.put("openid","确认账号身份"); scopes.put("profile","读取昵称和头像"); scopes.put("email","读取邮箱和验证状态");
            scopes.put("offline_access","在离线期间保留访问授权"); scopes.put(READ,"读取全部游戏角色");
            scopes.put(SELECT,"选择一个游戏角色"); scopes.put(JOIN,"使用所选角色加入游戏服务器");
        } else YGG.forEach(scopes::remove);
        return scopes.entrySet().stream().map(entry->new Scope(entry.getKey(),entry.getValue())).toList();
    }
}
