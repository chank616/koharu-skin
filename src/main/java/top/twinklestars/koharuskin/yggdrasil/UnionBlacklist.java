package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class UnionBlacklist {
    private final UnionMember union;
    public UnionBlacklist(UnionMember union) { this.union=union; }
    public JsonNode query(String query,int page) {
        if(page<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"页码必须大于零");
        return union.request("GET","/blacklist/query?q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&page="+page,null);
    }
    public Page page(String query,int page) {
        var result=query(query,page);
        if(!(result.path("data").isArray()||result.path("data").isObject())||result.path("current_page").asInt()<1||result.path("last_page").asInt()<1)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟黑名单响应格式无效");
        var entries=result.get("data").valueStream().map(item->new Entry(item.path("id").asLong(),item.path("email").asString(),
                item.path("source").asString(),item.path("reason").asString(),item.path("created_at").asString(),item.path("valid_until").asString())).toList();
        return new Page(entries,result.get("current_page").asInt(),result.get("last_page").asInt(),result.path("total").asLong());
    }
    public void create(String email,String reason) { union.request("POST","/blacklist/restful",Map.of("email",email,"reason",reason)); }
    public void change(String action,long id) {
        if(id<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"黑名单记录 ID 无效");
        switch(action) {
            case "invalidate" -> union.request("PUT","/blacklist/invalidate/"+id,null);
            case "delete" -> union.request("DELETE","/blacklist/restful/"+id,null);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
    public record Entry(long id,String email,String source,String reason,String createdAt,String validUntil) {}
    public record Page(List<Entry> entries,int currentPage,int lastPage,long total) {}
}
