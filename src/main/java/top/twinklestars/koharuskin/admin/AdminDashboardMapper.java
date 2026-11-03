package top.twinklestars.koharuskin.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminDashboardMapper {
    record Totals(long users,long players,long textures,long storage) {}
    @Select("""
            SELECT (SELECT COUNT(*) FROM users) AS users,(SELECT COUNT(*) FROM players) AS players,
                   (SELECT COUNT(*) FROM textures) AS textures,(SELECT COALESCE(SUM(size),0) FROM textures) AS storage
            """)
    Totals totals();
    record DailyCount(LocalDate statDate,long total) {}
    @Select("SELECT CAST(register_at AS DATE) AS stat_date,COUNT(*) AS total FROM users WHERE register_at>=#{start} AND register_at<#{end} GROUP BY CAST(register_at AS DATE)")
    List<DailyCount> registrations(LocalDateTime start,LocalDateTime end);
    @Select("SELECT CAST(upload_at AS DATE) AS stat_date,COUNT(*) AS total FROM textures WHERE upload_at>=#{start} AND upload_at<#{end} GROUP BY CAST(upload_at AS DATE)")
    List<DailyCount> uploads(LocalDateTime start,LocalDateTime end);
}
