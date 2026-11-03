package top.twinklestars.koharuskin.extension;

import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.site.SiteOptions;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialExtensions {
    public record Extension(String name,String title,String description,boolean enabled,String configUrl) {}
    private static final Map<String,String> TITLES=Map.of("yggdrasil-api","外置登录","single-player-limit","单角色绑定","restricted-email-domains","邮箱域名限制","skin-api-extension","皮肤 API 扩展");
    private final SiteOptions options;
    private final JsonMapper json;

    public OfficialExtensions(SiteOptions options, JsonMapper json) {
        this.options = options;
        this.json = json;
    }

    public boolean enabled(String name) {
        var configured = json.readTree(options.text("plugins_enabled", "[]"));
        return configured.isObject() ? configured.has(name) : configured.valueStream()
                .anyMatch(item -> (item.isObject() ? item.path("name").asString() : item.asString()).equals(name));
    }

    public List<Extension> list() {
        return List.of(
                new Extension("skin-api-extension",TITLES.get("skin-api-extension"),"使用私有 API 令牌查询邮箱域名下的角色 UUID，或获取角色及用户头像。",enabled("skin-api-extension"),"/admin/extensions/config#skin-api-extension"),
                new Extension("yggdrasil-api",TITLES.get("yggdrasil-api"),"为启动器提供外置登录、服务器进服验证和签名材质。",enabled("yggdrasil-api"),"/admin/extensions/config#yggdrasil-api"),
                new Extension("single-player-limit",TITLES.get("single-player-limit"),"要求每个账号绑定一个角色。已有多个角色的用户自行选择保留的角色，不会自动删除。",enabled("single-player-limit"),""),
                new Extension("restricted-email-domains",TITLES.get("restricted-email-domains"),"通过允许和禁止名单限制注册、修改邮箱时可用的邮箱域名。",enabled("restricted-email-domains"),"/admin/extensions/config#restricted-email-domains"));
    }

    @Transactional
    public void setEnabled(String name, boolean enabled) {
        if(!TITLES.containsKey(name)) throw new BusinessException("此官方扩展不存在");
        options.lockRegistration();
        var configured=json.readTree(options.text("plugins_enabled","[]"));
        var names=new LinkedHashSet<String>();
        if(configured.isObject()) configured.propertyNames().forEach(names::add);
        else configured.valueStream().forEach(item->names.add(item.isObject()?item.path("name").asString():item.asString()));
        if(enabled) names.add(name); else names.remove(name);
        String value=json.writeValueAsString(names);
        if(options.get("plugins_enabled")==null) options.insert("plugins_enabled",value); else options.update("plugins_enabled",value);
    }

    public void checkEmail(String email) {
        if (!enabled("restricted-email-domains")) return;
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
        List<String> allow = domains("allow");
        List<String> deny = domains("deny");
        if ((!allow.isEmpty() && !allow.contains(domain)) || deny.contains(domain)) {
            throw new BusinessException("此邮箱域名不允许注册，请使用站点允许的邮箱");
        }
    }

    private List<String> domains(String type) {
        return json.readTree(options.text("restricted-email-domains." + type, "[]")).valueStream()
                .map(item -> item.asString().toLowerCase(java.util.Locale.ROOT)).toList();
    }
}
