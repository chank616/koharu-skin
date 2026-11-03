package top.twinklestars.koharuskin.oauth;

import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class OAuthScopes {
    private final OAuthClientMapper clients;
    public OAuthScopes(OAuthClientMapper clients) { this.clients=clients; }
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
        return scopes.entrySet().stream().map(entry->new Scope(entry.getKey(),entry.getValue())).toList();
    }
}
