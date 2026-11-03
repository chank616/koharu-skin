package top.twinklestars.koharuskin.report;

import org.apache.ibatis.annotations.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

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
    String FILTER="""
            <where>
            <if test='query.status != null'>AND status=#{query.status}</if>
            <if test='query.tid != null'>AND tid=#{query.tid}</if>
            <if test='query.uploader != null'>AND uploader=#{query.uploader}</if>
            <if test='query.reporter != null'>AND reporter=#{query.reporter}</if>
            <if test='query.id != null'>AND id=#{query.id}</if>
            <if test='query.reason != ""'>AND reason LIKE CONCAT('%',#{query.reason},'%')</if>
            </where>
            """;
    @Select("<script>SELECT "+COLUMNS+" FROM reports "+FILTER+" ORDER BY report_at <choose><when test='query.oldest'>ASC</when><otherwise>DESC</otherwise></choose>,id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Report> list(ReportService.Query query,int limit,long offset);
    @Select("<script>SELECT COUNT(*) FROM reports "+FILTER+"</script>")
    long count(@Param("query") ReportService.Query query);
}
