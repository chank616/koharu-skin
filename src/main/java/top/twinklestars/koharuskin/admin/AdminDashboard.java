package top.twinklestars.koharuskin.admin;

import jakarta.servlet.ServletContext;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminDashboard {
    private final AdminDashboardMapper statistics;
    private final Clock clock;
    private final DataSource database;
    private final BuildProperties build;
    private final Environment environment;
    private final ServletContext servlet;
    public AdminDashboard(AdminDashboardMapper statistics,Clock clock,DataSource database,BuildProperties build,Environment environment,ServletContext servlet) {
        this.statistics=statistics; this.clock=clock; this.database=database; this.build=build; this.environment=environment; this.servlet=servlet;
    }
    public AdminDashboardMapper.Totals totals() { return statistics.totals(); }
    public record Chart(List<String> labels,List<String> xAxis,List<List<Long>> data) {}
    @Transactional(readOnly=true)
    public Chart chart() {
        // 旧库时间戳按站点本地时间存储；日期窗口与注册、上传记录使用同一时区。
        var today=LocalDate.ofInstant(clock.instant(),ZoneId.systemDefault());
        var start=today.minusDays(30); var end=today.plusDays(1);
        var days=start.datesUntil(end).toList();
        return new Chart(List.of("用户注册","材质上传"),days.stream().map(DateTimeFormatter.ofPattern("yyyy/M/d")::format).toList(),
                List.of(align(days,statistics.registrations(start.atStartOfDay(),end.atStartOfDay())),
                        align(days,statistics.uploads(start.atStartOfDay(),end.atStartOfDay()))));
    }
    private List<Long> align(List<LocalDate> days,List<AdminDashboardMapper.DailyCount> counts) {
        var indexed=counts.stream().collect(Collectors.toMap(AdminDashboardMapper.DailyCount::statDate,AdminDashboardMapper.DailyCount::total));
        return days.stream().map(day->indexed.getOrDefault(day,0L)).toList();
    }
    public Map<String,Map<String,String>> status() throws SQLException {
        var sections=new LinkedHashMap<String,Map<String,String>>();
        var application=new LinkedHashMap<String,String>();
        application.put("Koharu Skin",build.getVersion());
        application.put("Spring Boot",SpringBootVersion.getVersion());
        String[] profiles=environment.getActiveProfiles();
        application.put("运行配置",String.join(", ",profiles.length==0?environment.getDefaultProfiles():profiles));
        application.put("调试模式",environment.getProperty("debug",Boolean.class,false)?"开启":"关闭");
        application.put("虚拟线程",environment.getProperty("spring.threads.virtual.enabled",Boolean.class,false)?"开启":"关闭");
        sections.put("应用",application);
        var server=new LinkedHashMap<String,String>();
        server.put("Java",System.getProperty("java.runtime.version")); server.put("Web 服务器",servlet.getServerInfo());
        server.put("操作系统",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        server.put("时区",ZoneId.systemDefault().getId());
        sections.put("服务器",server);
        try(var connection=database.getConnection()) {
            var metadata=connection.getMetaData();
            var details=new LinkedHashMap<String,String>();
            details.put("类型",metadata.getDatabaseProductName()); details.put("版本",metadata.getDatabaseProductVersion());
            details.put("数据库",connection.getCatalog()); details.put("用户名",metadata.getUserName());
            sections.put("数据库",details);
        }
        return sections;
    }
}
