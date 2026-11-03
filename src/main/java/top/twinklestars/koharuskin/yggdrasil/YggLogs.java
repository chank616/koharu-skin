package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.util.List;

@Service
public class YggLogs {
    private final YggLogMapper rows;
    private final SiteOptions options;
    private final boolean defaultEnabled;

    public YggLogs(YggLogMapper rows,SiteOptions options,@Value("${YGG_VERBOSE_LOG:false}") boolean defaultEnabled) {
        this.rows=rows;this.options=options;this.defaultEnabled=defaultEnabled;
    }
    public boolean enabled() { return options.enabled("ygg_verbose_log",defaultEnabled); }
    public void record(String action,long uid,long pid,String ip) {
        record(action,uid,pid,ip,"[]");
    }
    public void record(String action,long uid,long pid,String ip,String parameters) {
        if(enabled()) rows.insert(action,uid,pid,ip,parameters);
    }
    public Page page(int requested) {
        long total=rows.count(),last=Math.max(1,(total+9)/10);
        long current=Math.max(1,Math.min(requested,last));
        return new Page(rows.list((current-1)*10),total,current,last);
    }
    public record Page(List<YggLogMapper.Entry> entries,long total,long currentPage,long lastPage) {}
}
