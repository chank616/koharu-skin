package top.twinklestars.koharuskin.site;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

@Service
public class SiteTranslations {
    private final TranslationMapper lines;
    private final JsonMapper json;
    private final Map<String,Map<String,Object>> bundled=new HashMap<>();
    public SiteTranslations(TranslationMapper lines,JsonMapper json) throws IOException {
        this.lines=lines;this.json=json;
        for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath*:i18n/*/*.yml")) {
            String path=resource.getURL().getPath();
            String name=path.substring(path.lastIndexOf("/i18n/")+6,path.length()-4);
            try(var input=resource.getInputStream()) { bundled.put(name,new Yaml().load(input)); }
        }
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> group(String name) {
        return group(name,SiteLocale.current());
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> group(String name,String locale) {
        var base=bundled.getOrDefault(locale+"/"+name,bundled.getOrDefault("en/"+name,Map.of()));
        Map<String,Object> result=json.convertValue(base,Map.class);
        for(var line:lines.group(name)) {
            var texts=texts(line);
            String text=texts.getOrDefault(locale,texts.get("en"));
            if(text!=null) put(result,line.key(),text);
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private void put(Map<String,Object> root,String key,String text) {
        String[] path=key.split("\\.",-1);
        Map<String,Object> parent=root;
        for(int i=0;i<path.length-1;i++) {
            if(!(parent.get(path[i]) instanceof Map)) parent.put(path[i],new LinkedHashMap<>());
            parent=(Map<String,Object>)parent.get(path[i]);
        }
        parent.put(path[path.length-1],text);
    }
    public String text(String key) {
        int split=key.indexOf('.');
        for(String locale:List.of(SiteLocale.current(),"en")) {
            Object value=group(key.substring(0,split),locale);
            for(String segment:key.substring(split+1).split("\\.",-1)) value=value instanceof Map<?,?> map?map.get(segment):null;
            if(value instanceof String text) return text;
        }
        return key;
    }
    public String text(String key,Map<String,?> parameters) {
        String text=text(key);
        for(var entry:parameters.entrySet()) text=text.replace(":"+entry.getKey(),String.valueOf(entry.getValue()));
        return text;
    }
    public Map<String,Object> page(int page) {
        int current=Math.max(1,page);long total=lines.count();
        var data=lines.page((current-1L)*10).stream().map(line->Map.of("id",line.id(),"group",line.group(),"key",line.key(),"text",texts(line))).toList();
        return Map.of("data",data,"current_page",current,"last_page",Math.max(1,(total+9)/10),"per_page",10,"total",total);
    }
    @SuppressWarnings("unchecked")
    private Map<String,String> texts(TranslationMapper.Line line) { return json.readValue(line.text(),Map.class); }
    public void create(String group,String key,String text) {
        lines.insert(group,key,json.writeValueAsString(Map.of(SiteLocale.current(),text)));
    }
    @Transactional
    public void update(long id,String text) {
        var line=lines.lock(id);
        if(line==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var texts=texts(line);texts.put(SiteLocale.current(),text);
        lines.update(id,json.writeValueAsString(texts));
    }
    public void delete(long id) {
        if(lines.delete(id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
