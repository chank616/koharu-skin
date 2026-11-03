package top.twinklestars.koharuskin.site;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

@Configuration
public class FormRequests implements WebMvcConfigurer {
    private final JsonMapper json;
    public FormRequests(JsonMapper json) { this.json=json; }
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.addFirst(new AbstractHttpMessageConverter<Object>(MediaType.APPLICATION_FORM_URLENCODED) {
            @Override protected boolean supports(Class<?> type) { return type.isRecord()&&type.getPackageName().startsWith("top.twinklestars.koharuskin"); }
            @Override public boolean canWrite(Class<?> type,MediaType media) { return false; }
            @Override protected Object readInternal(Class<?> type,HttpInputMessage message) throws IOException {
                var form=new FormHttpMessageConverter().read(null,message);
                var values=new LinkedHashMap<String,Object>();
                form.forEach((name,entries)->{
                    boolean array=name.endsWith("[]");
                    values.put(array?name.substring(0,name.length()-2):name,array||entries.size()!=1?entries:entries.getFirst());
                });
                try { return json.convertValue(values,type); }
                catch(IllegalArgumentException error) { throw new HttpMessageNotReadableException("表单参数格式不正确",error,message); }
            }
            @Override protected void writeInternal(Object value,HttpOutputMessage output) { throw new UnsupportedOperationException("此转换器只读取旧接口的表单请求"); }
        });
    }
}
