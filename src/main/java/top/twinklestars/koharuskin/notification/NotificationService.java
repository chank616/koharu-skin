package top.twinklestars.koharuskin.notification;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.account.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class NotificationService {
    static final String USER_MODEL="App\\Models\\User";
    static final String MESSAGE_TYPE="App\\Notifications\\SiteMessage";
    private final NotificationMapper notifications;
    private final AccountMapper accounts;
    private final JsonMapper json;
    private final Parser markdown;
    private final HtmlRenderer html;
    public NotificationService(NotificationMapper notifications,AccountMapper accounts,JsonMapper json) {
        this.notifications=notifications; this.accounts=accounts; this.json=json;
        List<Extension> extensions=List.of(TablesExtension.create(),StrikethroughExtension.create(),AutolinkExtension.create(),TaskListItemsExtension.create());
        markdown=Parser.builder().extensions(extensions).build();
        html=HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build();
    }
    public record Summary(String id,String title) {}
    public String unreadJson(long uid) { return json.writeValueAsString(unread(uid)); }
    public List<Summary> unread(long uid) {
        return notifications.unread(uid,USER_MODEL).stream()
                .map(message->new Summary(message.id(),json.readTree(message.data()).path("title").asString())).toList();
    }
    public record Detail(String title,String content,String time) {}
    @Transactional
    public Detail read(long uid,String id) {
        var message=notifications.lockUnread(id,uid,USER_MODEL);
        if(message==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未读通知不存在");
        var data=json.readTree(message.data());
        String content=data.path("content").isTextual()?data.path("content").asString():"";
        var result=new Detail(data.path("title").asString(),html.render(markdown.parse(content)),
                message.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        notifications.markRead(id);
        return result;
    }
    @Transactional
    public int send(AccountUser actor,String receiver,Long uid,String email,String title,String content) {
        var admin=accounts.lockById(actor.uid());
        if(admin==null||admin.permission()<1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(!Set.of("all","normal","uid","email").contains(receiver)) throw new BusinessException("请选择接收者");
        if(title==null||title.isBlank()||title.codePointCount(0,title.length())>20) throw new BusinessException("通知标题不能为空，且不能超过 20 个字");
        if(receiver.equals("uid")&&uid==null) throw new BusinessException("请填写用户 ID");
        if(receiver.equals("email")&&(email==null||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) throw new BusinessException("请填写有效邮箱");
        var recipients=notifications.recipients(receiver,uid,email);
        if((receiver.equals("uid")||receiver.equals("email"))&&recipients.isEmpty()) throw new BusinessException("接收用户不存在");
        String data=json.writeValueAsString(Map.of("title",title,"content",content==null?"":content));
        for(long target:recipients) notifications.insert(UUID.randomUUID().toString(),MESSAGE_TYPE,USER_MODEL,target,data);
        return recipients.size();
    }
}
