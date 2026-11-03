package top.twinklestars.koharuskin.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class AdminSettings {
    public record Field(String key,String label,String type,String fallback) {}
    public record Group(String key,String title,List<Field> fields) {}
    private final SiteOptions options;
    private final tools.jackson.databind.json.JsonMapper json;
    public AdminSettings(SiteOptions options,tools.jackson.databind.json.JsonMapper json) { this.options=options; this.json=json; }

    public List<Group> groups(String page) {
        return switch(page) {
            case "customize" -> List.of(new Group("homepage","首页与页脚",List.of(
                    new Field("home_pic_url","首页背景图片地址","text","/official/site/bg.webp"),
                    new Field("favicon_url","站点图标地址","text","/official/site/favicon.ico"),
                    new Field("transparent_navbar","首页导航栏透明","checkbox","false"),
                    new Field("hide_intro","隐藏首页功能介绍","checkbox","false"),
                    new Field("fixed_bg","固定首页背景","checkbox","false"),
                    new Field("copyright_prefer","驱动说明样式（0～6）","number","0"),
                    new Field("copyright_text","页脚内容（支持 HTML，以及 {year}、{site_name}、{site_url}）","textarea","Copyright &copy; {year} {site_name}."))),
                    new Group("color","导航栏颜色",List.of(new Field("navbar_color","导航栏颜色","color","cyan"))),
                    new Group("customJsCss","自定义样式与脚本",List.of(
                            new Field("custom_css","CSS（直接填写代码，不含 style 标签）","textarea",""),
                            new Field("custom_js","JavaScript（直接填写代码，不含 script 标签）","textarea",""))));
            case "resource" -> List.of(new Group("resources","静态资源 CDN",List.of(
                    new Field("cdn_address","CDN 根地址（留空使用本站，CDN 需提供 app 目录及跨域资源访问）","text",""))));
            case "extensions" -> List.of(
                    new Group("ygg-connect","OIDC 角色授权",List.of(new Field("ygg_connect_server_url","OIDC 服务根地址（留空表示未配置）","text",""))),
                    new Group("union","联合认证",List.of(
                            new Field("union_api_root","联盟 API 根地址","text",""),
                            new Field("union_member_key","成员密钥（留空保留现有密钥）","password",""),
                            new Field("union_enable_oauth2","启用联盟授权（向联盟提供用户 ID、昵称及邮箱）","checkbox","false"))),
                    new Group("skin-api-extension","皮肤 API 扩展",List.of(
                            new Field("skin_api_tokens","API 令牌（至少 16 字符，多个用逗号分隔；留空保留现有令牌）","password",""))),
                    new Group("yggdrasil-api","外置登录",List.of(
                            new Field("ygg_uuid_algorithm","新角色 UUID 算法（已有 UUID 保留）","uuid","v4"),
                            new Field("ygg_token_expire_1","登录令牌有效期（秒）","number","259200"),
                            new Field("ygg_token_expire_2","登录令牌可刷新期限（秒）","number","604800"),
                            new Field("ygg_tokens_limit","每个账号保留的登录令牌数","number","10"),
                            new Field("ygg_rate_limit","同一账号登录间隔（毫秒）","number","1000"),
                            new Field("ygg_skin_domain","额外材质域名（逗号分隔）","text",""),
                            new Field("ygg_search_profile_max","一次查询角色数量上限","number","5"),
                            new Field("ygg_restore_api","启用 Restore API（为调用方提交的材质值签名，配合修改版 MultiLogin 使用）","checkbox","false"),
                            new Field("ygg_show_config_section","在用户中心显示外置登录地址","checkbox","true"))),
                    new Group("restricted-email-domains","邮箱域名限制",List.of(
                            new Field("restricted-email-domains.allow","允许的域名（每行一个，留空允许所有域名）","textarea","[]"),
                            new Field("restricted-email-domains.deny","禁止的域名（每行一个，优先于允许名单）","textarea","[]"))));
            case "options" -> List.of(new Group("general","基础设置",List.of(
                    new Field("site_name","站点名称","text","Koharu Skin"),
                    new Field("site_description","站点介绍","text","Minecraft 皮肤站"),
                    new Field("register_with_player_name","注册时填写角色名","checkbox","true"),
                    new Field("require_verification","要求验证邮箱","checkbox","false"),
                    new Field("regs_per_ip","每个 IP 可注册账号数","number","3"),
                    new Field("max_upload_file_size","上传文件大小上限（KB）","number","1024"),
                    new Field("max_texture_width","材质宽度上限（像素）","number","8192"),
                    new Field("player_name_rule","角色命名规则","rule","official"),
                    new Field("custom_player_name_regexp","自定义角色名正则","text",""),
                    new Field("player_name_length_min","角色名最短长度","number","3"),
                    new Field("player_name_length_max","角色名最长长度","number","16"),
                    new Field("allow_downloading_texture","允许下载材质","checkbox","true"),
                    new Field("status_code_for_private","私密材质访问状态码","status","403"),
                    new Field("texture_name_regexp","材质名称正则","text",""),
                    new Field("content_policy","上传内容规范","textarea",""))),
                    new Group("announ","用户中心公告",List.of(new Field("announcement","公告（支持 Markdown、HTML 和 {version}）","textarea","欢迎使用 Koharu Skin {version}！"))),
                    new Group("meta","页面元信息",List.of(new Field("meta_keywords","关键词","text",""),
                            new Field("meta_description","搜索结果中的站点描述","text",""),new Field("meta_extras","额外 meta 标签","textarea",""))),
                    new Group("recaptcha","reCAPTCHA v2",List.of(new Field("recaptcha_sitekey","Site key（清空并保存以停用，恢复图片验证码）","text",""),
                            new Field("recaptcha_secretkey","Secret key（留空保留现有密钥）","password",""),
                            new Field("recaptcha_invisible","使用隐形 reCAPTCHA（需对应类型的 v2 密钥）","checkbox","false"))));
            case "score" -> List.of(
                    new Group("rate","积分消耗",List.of(
                            new Field("score_per_storage","公开材质每 KB 消耗","number","1"),
                            new Field("private_score_per_storage","私密材质每 KB 消耗","number","10"),
                            new Field("score_per_closet_item","每次收藏消耗","number","0"),
                            new Field("score_per_player","每个角色消耗","number","100"),
                            new Field("user_initial_score","新用户初始积分","number","1000"),
                            new Field("return_score","删除后退还消耗的积分","checkbox","true"))),
                    new Group("sign","签到",List.of(
                            new Field("sign_score_from","签到奖励下限","number","10"),
                            new Field("sign_score_to","签到奖励上限","number","100"),
                            new Field("sign_gap_time","签到间隔（小时）","number","24"),
                            new Field("sign_after_zero","每天零点重置签到","checkbox","false"))),
                    new Group("sharing","分享奖励",List.of(
                            new Field("score_award_per_texture","上传公开材质奖励","number","0"),
                            new Field("score_award_per_like","被收藏奖励","number","0"),
                            new Field("take_back_scores_after_deletion","删除公开材质时收回上传奖励","checkbox","true"))),
                    new Group("report","举报积分",List.of(
                            new Field("reporter_score_modification","提交举报的积分变动","integer","0"),
                            new Field("reporter_reward_score","举报成立奖励","number","0"))));
            default -> throw new IllegalArgumentException("未知配置页面");
        };
    }

    public Map<String,String> values(String page) {
        var values=new LinkedHashMap<String,String>();
        for(var group:groups(page)) for(var field:group.fields()) {
            String value=localizedField(field.key())?options.localized(field.key(),field.fallback()):options.text(field.key(),field.fallback());
            if(field.type().equals("password")) value="";
            if(field.key().startsWith("restricted-email-domains.")) value=String.join("\n",json.readTree(value).valueStream().map(item->item.asString()).toList());
            if(field.type().equals("number")) value=switch(value){case "true"->"1";case "false"->"0";default->value;};
            values.put(field.key(),value);
        }
        if(page.equals("score")) {
            String[] range=options.text("sign_score","10,100").split(",");
            values.put("sign_score_from",range[0]); values.put("sign_score_to",range[1]);
        }
        return values;
    }

    @Transactional
    public void save(String page, String groupKey, Map<String,String> submitted) {
        var group=groups(page).stream().filter(item->item.key().equals(groupKey)).findFirst()
                .orElseThrow(()->new BusinessException("未知配置分组"));
        var values=new LinkedHashMap<String,String>();
        for(var field:group.fields()) {
            String value=submitted.getOrDefault(field.key(),"");
            if(field.type().equals("checkbox")) value="true".equals(value)||"on".equals(value)||"1".equals(value)?"true":"false";
            if(field.type().equals("number")||field.type().equals("integer")) {
                if(!value.matches("-?\\d{1,10}")) throw new BusinessException(field.label()+"必须为整数");
                long number=Long.parseLong(value);
                if(number<Integer.MIN_VALUE||number>Integer.MAX_VALUE || (field.type().equals("number")&&number<0))
                    throw new BusinessException(field.label()+"超出允许范围");
                value=Long.toString(number);
            }
            values.put(field.key(),value);
        }
        if(groupKey.equals("general")) {
            if(values.get("site_name").isBlank()) throw new BusinessException("站点名称不能为空");
            if(!Set.of("official","cjk","utf8","custom").contains(values.get("player_name_rule"))) throw new BusinessException("角色命名规则无效");
            if(!Set.of("403","404").contains(values.get("status_code_for_private"))) throw new BusinessException("私密材质状态码无效");
            int min=Integer.parseInt(values.get("player_name_length_min")),max=Integer.parseInt(values.get("player_name_length_max"));
            if(min<1||max<min||max>50) throw new BusinessException("角色名长度范围应在 1～50 之间，且下限不大于上限");
            if(Integer.parseInt(values.get("max_upload_file_size"))<1||Integer.parseInt(values.get("max_upload_file_size"))>102400)
                throw new BusinessException("上传大小应为 1～102400 KB");
            if(Integer.parseInt(values.get("max_texture_width"))<64) throw new BusinessException("材质宽度上限不能低于 64 像素");
            validateRegex(values.get("custom_player_name_regexp")); validateRegex(values.get("texture_name_regexp"));
        }
        if(groupKey.equals("sign")) {
            int min=Integer.parseInt(values.get("sign_score_from")),max=Integer.parseInt(values.get("sign_score_to"));
            if(max<min||max==Integer.MAX_VALUE) throw new BusinessException("签到奖励范围无效");
            if(Integer.parseInt(values.get("sign_gap_time"))<1) throw new BusinessException("签到间隔至少为 1 小时");
            values.remove("sign_score_from"); values.remove("sign_score_to"); values.put("sign_score",min+","+max);
        }
        if(groupKey.equals("yggdrasil-api")) {
            if(!Set.of("v3","v4").contains(values.get("ygg_uuid_algorithm"))) throw new BusinessException("UUID 算法无效");
            int valid=Integer.parseInt(values.get("ygg_token_expire_1")),refresh=Integer.parseInt(values.get("ygg_token_expire_2"));
            if(valid<1||refresh<valid) throw new BusinessException("可刷新期限不能短于有效期，且有效期必须大于零");
            if(Integer.parseInt(values.get("ygg_tokens_limit"))<1||Integer.parseInt(values.get("ygg_search_profile_max"))<1)
                throw new BusinessException("令牌数和角色查询上限必须大于零");
        }
        if(groupKey.equals("restricted-email-domains")) values.replaceAll((key,value)->json.writeValueAsString(
                value.lines().map(String::trim).filter(line->!line.isEmpty()).map(line->line.toLowerCase(Locale.ROOT)).distinct().toList()));
        if(groupKey.equals("skin-api-extension")) {
            String tokens=values.get("skin_api_tokens");
            if(tokens.isBlank()) values.clear();
            else {
                var list=Arrays.stream(tokens.split(",",-1)).map(String::trim).toList();
                if(list.stream().anyMatch(token->token.length()<16||token.chars().anyMatch(Character::isWhitespace)))
                    throw new BusinessException("每个 API 令牌至少 16 字符，且不能含空白字符");
                values.put("skin_api_tokens",String.join(",",list));
            }
        }
        if(groupKey.equals("homepage")&&Integer.parseInt(values.get("copyright_prefer"))>6)
            throw new BusinessException("驱动说明样式应为 0～6");
        if(groupKey.equals("union")) {
            String root=values.get("union_api_root").trim().replaceAll("/+$","");
            try {
                var uri=java.net.URI.create(root);
                if(!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
                    throw new IllegalArgumentException();
            } catch(IllegalArgumentException error) { throw new BusinessException("请填写完整的 HTTP 或 HTTPS 联盟 API 根地址"); }
            values.put("union_api_root",root);
            String key=values.get("union_member_key").trim();
            if(key.isEmpty()) key=options.text("union_member_key","");
            if(key.isBlank()) throw new BusinessException("请填写联盟成员密钥");
            values.put("union_member_key",key);
        }
        if(groupKey.equals("ygg-connect")) {
            String root=values.get("ygg_connect_server_url").trim().replaceAll("/+$","");
            if(!root.isEmpty()) {
                try {
                    var uri=java.net.URI.create(root);
                    if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),""))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
                        throw new IllegalArgumentException();
                } catch(IllegalArgumentException error) { throw new BusinessException("请填写完整的 HTTP 或 HTTPS OIDC 服务根地址"); }
            }
            values.put("ygg_connect_server_url",root);
        }
        if(groupKey.equals("recaptcha")) {
            String sitekey=values.get("recaptcha_sitekey").trim();
            String secret=values.get("recaptcha_secretkey").trim();
            values.put("recaptcha_sitekey",sitekey);
            if(sitekey.isEmpty()) { values.put("recaptcha_secretkey",""); values.put("recaptcha_invisible","false"); }
            else {
                if(secret.isEmpty()) secret=options.text("recaptcha_secretkey","");
                if(secret.isBlank()) throw new BusinessException("启用 reCAPTCHA 时需要同时设置 Site key 和 Secret key");
                values.put("recaptcha_secretkey",secret);
            }
        }
        if(groupKey.equals("color")&&!top.twinklestars.koharuskin.site.SitePresentation.COLORS.containsKey(values.get("navbar_color")))
            throw new BusinessException("导航栏颜色无效");
        if(groupKey.equals("resources")) {
            String cdn=values.get("cdn_address").trim().replaceAll("/+$","");
            if(!cdn.isEmpty()) {
                java.net.URI uri;
                try { uri=java.net.URI.create(cdn); } catch(IllegalArgumentException error) { throw new BusinessException("CDN 地址格式无效"); }
                if((!"http".equals(uri.getScheme())&&!"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
                    throw new BusinessException("CDN 地址应为完整 HTTP(S) 地址，不能带账号、查询参数或片段");
            }
            values.put("cdn_address",cdn);
        }
        options.lockRegistration();
        values.forEach((key,value)-> { if(options.get(key)==null) options.insert(key,value); else options.update(key,value); });
        values.forEach((key,value)-> { if(localizedField(key)) { String localized=key+"_zh_CN";
            if(options.get(localized)==null) options.insert(localized,value); else options.update(localized,value); } });
    }

    private boolean localizedField(String key) { return Set.of("site_name","site_description","content_policy","announcement","copyright_text","copyright_prefer").contains(key); }

    private void validateRegex(String regex) {
        if(regex.startsWith("/")&&regex.lastIndexOf('/')>0) regex=regex.substring(1,regex.lastIndexOf('/'));
        try { Pattern.compile(regex); }
        catch(PatternSyntaxException error) { throw new BusinessException("正则表达式格式无效"); }
    }
}
