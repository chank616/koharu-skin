package top.twinklestars.koharuskin.yggdrasil;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface YggLogMapper {
    @Insert("INSERT INTO ygg_log(action,user_id,player_id,parameters,ip,time) VALUES(#{action},#{uid},#{pid},#{parameters},#{ip},CURRENT_TIMESTAMP)")
    void insert(String action,long uid,long pid,String ip,String parameters);

    @Select("SELECT id,action,user_id,player_id,parameters,ip,time FROM ygg_log ORDER BY time DESC,id DESC LIMIT 10 OFFSET #{offset}")
    List<Entry> list(long offset);

    @Select("SELECT COUNT(*) FROM ygg_log")
    long count();

    record Entry(long id,String action,long userId,long playerId,String parameters,String ip,LocalDateTime time) {
        public String label() {
            return switch(action) {
                case "authenticate" -> "登录";
                case "refresh" -> "刷新令牌";
                case "validate" -> "验证令牌";
                case "signout" -> "登出";
                case "invalidate" -> "吊销令牌";
                case "join" -> "请求加入服务器";
                case "has_joined" -> "进入服务器";
                default -> action;
            };
        }
    }
}
