package top.twinklestars.koharuskin.admin;

import top.twinklestars.koharuskin.account.BusinessException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 原后台搜索语法；SQL 字段仅来自各列表的固定白名单，输入值使用 MyBatis 参数绑定。 */
public record AdminSearch(String where,String order,List<Object> values) {
    public enum ListType {
        USERS("uid email nickname avatar score permission ip last_sign_at register_at verified is_dark_mode","email nickname","last_sign_at register_at","verified is_dark_mode","uid DESC"),
        PLAYERS("pid uid name tid_skin tid_cape last_modified","name","last_modified","","pid DESC"),
        REPORTS("id tid uploader reporter reason status report_at","reason","report_at","","report_at DESC,id DESC");
        final Set<String> columns,dates,booleans;
        final List<String> searchable;
        final String order;
        ListType(String columns,String searchable,String dates,String booleans,String order) {
            this.columns=Set.of(columns.split(" "));this.searchable=List.of(searchable.split(" "));
            this.dates=Set.of(dates.split(" "));this.booleans=Set.of(booleans.split(" "));this.order=order;
        }
    }
    public static AdminSearch parse(ListType type,String text) {
        try { return new Parser(type,text).parse(); }
        catch(DateTimeException error) { throw invalid("日期格式无效，请使用年、年-月、年-月-日或完整日期时间"); }
    }
    private static BusinessException invalid(String message) {
        return new BusinessException("搜索条件无效："+message);
    }
    private record Token(String text,boolean quoted) {}
    private static final class Parser {
        final ListType type;
        final List<Token> tokens=new ArrayList<>();
        final List<Object> values=new ArrayList<>();
        final List<String> order=new ArrayList<>();
        int position;
        Parser(ListType type,String text) {
            this.type=type;
            for(int i=0;i<text.length();) {
                char c=text.charAt(i);
                if(Character.isWhitespace(c)) { i++;continue; }
                if(c=='\''||c=='"') {
                    char quote=c;var value=new StringBuilder();boolean closed=false;i++;
                    while(i<text.length()) {
                        c=text.charAt(i++);
                        if(c==quote) { closed=true;break; }
                        if(c=='\\'&&i<text.length()) c=text.charAt(i++);
                        value.append(c);
                    }
                    if(!closed) throw invalid("引号未闭合");
                    tokens.add(new Token(value.toString(),true));
                } else if("():,=<>!".indexOf(c)>=0) {
                    String operator=""+c;i++;
                    if("<>!".indexOf(c)>=0&&i<text.length()&&text.charAt(i)=='=') {operator+="=";i++;}
                    tokens.add(new Token(operator,false));
                } else {
                    int start=i++;
                    while(i<text.length()&&!Character.isWhitespace(text.charAt(i))&&"():,=<>!\"'".indexOf(text.charAt(i))<0) i++;
                    tokens.add(new Token(text.substring(start,i),false));
                }
            }
        }
        AdminSearch parse() {
            String where=tokens.isEmpty()?"1=1":or();
            if(position!=tokens.size()) throw invalid("多余的符号 "+tokens.get(position).text());
            return new AdminSearch(where.isEmpty()?"1=1":where,order.isEmpty()?type.order:String.join(",",order),List.copyOf(values));
        }
        boolean is(String word) { return position<tokens.size()&&!tokens.get(position).quoted()&&tokens.get(position).text().equals(word); }
        boolean take(String word) { if(!is(word)) return false;position++;return true; }
        void require(String word) { if(!take(word)) throw invalid("缺少 "+word); }
        Token next() { if(position==tokens.size()) throw invalid("条件不完整");return tokens.get(position++); }
        String or() {
            String result=and();
            while(take("or")) result=combine(result,"OR",and());
            return result;
        }
        String and() {
            String result=unary();
            while(position<tokens.size()&&!is(")")&&!is("or")) {
                take("and");result=combine(result,"AND",unary());
            }
            return result;
        }
        String unary() {
            if(take("not")) { String result=unary();return result.isEmpty()?"":"NOT ("+result+")"; }
            if(take("(")) { String result=or();require(")");return result.isEmpty()?"":"("+result+")"; }
            Token token=next();String name=token.text();
            if(!token.quoted()&&name.equals("sort")) {
                require(":");
                do { String value=next().text();boolean descending=value.startsWith("-");
                    order.add(column(descending?value.substring(1):value)+(descending?" DESC":" ASC"));
                } while(take(","));
                return "";
            }
            if(!token.quoted()&&(is(":")||is("=")||is("!=")||is("<")||is(">")||is("<=")||is(">=")||is("in"))) {
                String column=column(name),operator=next().text();
                if(operator.equals("in")) {
                    require("(");var items=new ArrayList<String>();
                    do { items.add(compare(column,"=",value())); } while(take(","));
                    require(")");return "("+String.join(" OR ",items)+")";
                }
                String result=compare(column,operator.equals(":")?"=":operator,value());
                if(operator.equals(":")) while(take(",")) result="("+result+" OR "+compare(column,"=",value())+")";
                return result;
            }
            if(!token.quoted()&&type.booleans.contains(name)) return column(name)+"=TRUE";
            if(!token.quoted()&&type.dates.contains(name)) return column(name)+" IS NOT NULL";
            if(!token.quoted()&&Set.of("and","or",":",",", "=","<",">","<=",">=","!","!=", ")").contains(name)) throw invalid("不应出现 "+name);
            String parameter=parameter("%"+name+"%");
            return "("+String.join(" OR ",type.searchable.stream().map(c->c+" LIKE "+parameter).toList())+")";
        }
        Token value() {
            Token token=next();
            if(!token.quoted()&&Set.of("(",")",",",":","=","<",">","<=",">=","!=").contains(token.text())) throw invalid("缺少筛选值");
            return token;
        }
        String combine(String left,String operator,String right) {
            if(left.isEmpty()) return right;
            if(right.isEmpty()) return left;
            return "("+left+" "+operator+" "+right+")";
        }
        String column(String name) {
            if(type==ListType.PLAYERS&&(name.equals("skin")||name.equals("cape"))) name="tid_"+name;
            if(!type.columns.contains(name)) throw invalid("不支持的字段 "+name);
            return name;
        }
        String parameter(Object value) { int index=values.size();values.add(value);return "#{query.values["+index+"]}"; }
        String compare(String column,String operator,Token value) {
            if(!value.quoted()&&value.text().equals("NULL")) {
                if(!operator.equals("=")&&!operator.equals("!=")) throw invalid("NULL 只支持相等或不等条件");
                return column+(operator.equals("=")?" IS NULL":" IS NOT NULL");
            }
            if(type.dates.contains(column)) {
                var range=date(value.text());var start=range[0];var end=range[1];
                if(start.equals(end)) return column+operator+parameter(start);
                return switch(operator) {
                    case "=" -> "("+column+">="+parameter(start)+" AND "+column+"<"+parameter(end)+")";
                    case "!=" -> "("+column+"<"+parameter(start)+" OR "+column+">="+parameter(end)+")";
                    case ">" -> column+">="+parameter(end);
                    case "<=" -> column+"<"+parameter(end);
                    default -> column+operator+parameter(start);
                };
            }
            Object bound=value.text();
            if(type.booleans.contains(column)) bound=switch(value.text()) { case "true","1"->true;case "false","0"->false;default->throw invalid("布尔字段只能为 true、false、1 或 0"); };
            return column+operator+parameter(bound);
        }
        LocalDateTime[] date(String value) {
            if(Set.of("today","tomorrow","yesterday").contains(value)) {
                var start=LocalDate.now().plusDays(value.equals("tomorrow")?1:value.equals("yesterday")?-1:0).atStartOfDay();
                return new LocalDateTime[]{start,start.plusDays(1)};
            }
            if(value.matches("\\d{4}")) { var start=Year.parse(value).atDay(1).atStartOfDay();return new LocalDateTime[]{start,start.plusYears(1)}; }
            if(value.matches("\\d{4}-\\d{1,2}")) { var start=YearMonth.parse(value,DateTimeFormatter.ofPattern("uuuu-M")).atDay(1).atStartOfDay();return new LocalDateTime[]{start,start.plusMonths(1)}; }
            if(value.matches("\\d{1,2}/\\d{4}")) { var start=YearMonth.parse(value,DateTimeFormatter.ofPattern("M/uuuu")).atDay(1).atStartOfDay();return new LocalDateTime[]{start,start.plusMonths(1)}; }
            if(value.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) { var start=LocalDate.parse(value,format("uuuu-M-d")).atStartOfDay();return new LocalDateTime[]{start,start.plusDays(1)}; }
            String normalized=value.replace('T',' ');
            var start=LocalDateTime.parse(normalized,format("uuuu-M-d H[:mm[:ss]]"));
            int colons=(int)normalized.chars().filter(c->c==':').count();
            return new LocalDateTime[]{start,colons==0?start.plusHours(1):colons==1?start.plusMinutes(1):start};
        }
        DateTimeFormatter format(String pattern) { return DateTimeFormatter.ofPattern(pattern).withResolverStyle(java.time.format.ResolverStyle.STRICT); }
    }
}
