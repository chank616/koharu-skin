package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import java.util.Map;

@Service
public class UnionPlayerSync {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(UnionPlayerSync.class);
    private final UnionMember union;
    private final OfficialExtensions extensions;
    public UnionPlayerSync(UnionMember union,OfficialExtensions extensions) { this.union=union; this.extensions=extensions; }
    public boolean enabled() { return union.networkEnabled()&&extensions.enabled("yggdrasil-connect"); }
    public void afterCommit(String method,String uuid,String name) {
        String path=method.equals("POST")?"/profile":"/profile/"+uuid;
        Object data=switch(method) {
            case "POST" -> Map.of("id",uuid,"name",name);
            case "PUT" -> Map.of("name",name);
            case "DELETE" -> null;
            default -> throw new IllegalArgumentException(method);
        };
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { union.request(method,path,data); }
                catch(ResponseStatusException error) {
                    // 本地变更已经提交；保留失败记录，由后台的联盟同步操作补偿。
                    log.warn("角色 {} 联盟同步失败（{}，HTTP {}），请在后台重新同步",uuid,method,error.getStatusCode().value());
                }
            }
        });
    }
}
