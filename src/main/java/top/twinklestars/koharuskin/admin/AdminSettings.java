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
    public AdminSettings(SiteOptions options) { this.options=options; }

    public List<Group> groups(String page) {
        return switch(page) {
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
                    new Field("content_policy","上传内容规范","textarea",""))));
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
            String value=options.text(field.key(),field.fallback());
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
        options.lockRegistration();
        values.forEach((key,value)-> { if(options.get(key)==null) options.insert(key,value); else options.update(key,value); });
    }

    private void validateRegex(String regex) {
        if(regex.startsWith("/")&&regex.lastIndexOf('/')>0) regex=regex.substring(1,regex.lastIndexOf('/'));
        try { Pattern.compile(regex); }
        catch(PatternSyntaxException error) { throw new BusinessException("正则表达式格式无效"); }
    }
}
