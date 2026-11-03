package top.twinklestars.koharuskin.player;

import org.springframework.stereotype.Service;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.site.SiteOptions;

@Service
public class PlayerNames {
    private final SiteOptions options;
    public PlayerNames(SiteOptions options) { this.options = options; }

    public void validate(String name) {
        int min = options.number("player_name_length_min", 3);
        int max = Math.min(options.number("player_name_length_max", 16), 50);
        if (name == null || name.codePointCount(0, name.length()) < min || name.codePointCount(0, name.length()) > max) {
            throw new BusinessException("角色名长度应为 " + min + "～" + max + " 个字符");
        }
        String rule = options.text("player_name_rule", "official");
        boolean valid = switch (rule) {
            case "official" -> name.matches("[A-Za-z0-9_]+");
            case "cjk" -> name.matches("[A-Za-z0-9_§\\x{4e00}-\\x{9fff}]+");
            case "utf8" -> !name.matches("(?s).*\\s.*");
            case "custom" -> {
                String regex = options.text("custom_player_name_regexp", "");
                if (regex.startsWith("/") && regex.lastIndexOf('/') > 0) regex = regex.substring(1, regex.lastIndexOf('/'));
                yield regex.isEmpty() || java.util.regex.Pattern.compile(regex).matcher(name).find();
            }
            default -> throw new IllegalStateException("未知角色名规则：" + rule);
        };
        if (!valid) throw new BusinessException("角色名不符合站点的命名规则");
    }
}
