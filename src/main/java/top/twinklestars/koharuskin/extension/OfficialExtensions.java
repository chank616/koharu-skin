package top.twinklestars.koharuskin.extension;

import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.site.SiteOptions;

import java.util.List;

@Service
public class OfficialExtensions {
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
