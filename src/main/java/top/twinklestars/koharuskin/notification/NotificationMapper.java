package top.twinklestars.koharuskin.notification;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotificationMapper {
    record Message(String id,String data,LocalDateTime createdAt) {}
    @Select("SELECT id,data,created_at FROM notifications WHERE notifiable_id=#{uid} AND notifiable_type=#{model} AND read_at IS NULL ORDER BY created_at DESC,id")
    List<Message> unread(long uid,String model);
    @Select("SELECT id,data,created_at FROM notifications WHERE id=#{id} AND notifiable_id=#{uid} AND notifiable_type=#{model} AND read_at IS NULL FOR UPDATE")
    Message lockUnread(String id,long uid,String model);
    @Update("UPDATE notifications SET read_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void markRead(String id);
    @Select("""
            <script>SELECT uid FROM users
            <choose>
                <when test='receiver == "normal"'>WHERE permission=0</when>
                <when test='receiver == "uid"'>WHERE uid=#{uid}</when>
                <when test='receiver == "email"'>WHERE email=#{email}</when>
            </choose> ORDER BY uid</script>
            """)
    List<Long> recipients(String receiver,Long uid,String email);
    @Insert("INSERT INTO notifications(id,type,notifiable_type,notifiable_id,data,created_at,updated_at) VALUES(#{id},#{type},#{model},#{uid},#{data},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    void insert(String id,String type,String model,long uid,String data);
}
