package top.twinklestars.koharuskin.report;

import org.apache.ibatis.annotations.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import top.twinklestars.koharuskin.admin.AdminSearch;

@Mapper
public interface ReportMapper {
    record Report(long id,int tid,long uploader,long reporter,String reason,int status,@JsonProperty("report_at") LocalDateTime reportAt) {}
    String COLUMNS="id,tid,uploader,reporter,reason,status,report_at";
    @Select("SELECT "+COLUMNS+" FROM reports WHERE id=#{id} FOR UPDATE")
    Report lock(long id);
    @Select("SELECT COUNT(*) FROM reports WHERE reporter=#{uid} AND tid=#{tid}")
    int exists(long uid,int tid);
    @Insert("INSERT INTO reports(tid,uploader,reporter,reason,status,report_at) VALUES(#{tid},#{uploader},#{reporter},#{reason},0,CURRENT_TIMESTAMP)")
    void insert(int tid,long uploader,long reporter,String reason);
    @Update("UPDATE reports SET status=#{status} WHERE id=#{id}")
    void status(long id,int status);
    @Select("SELECT "+COLUMNS+" FROM reports WHERE ${query.where} ORDER BY ${query.order} LIMIT #{limit} OFFSET #{offset}")
    List<Report> list(@Param("query") AdminSearch query,int limit,long offset);
    @Select("SELECT COUNT(*) FROM reports WHERE ${query.where}")
    long count(@Param("query") AdminSearch query);
}
