package top.twinklestars.koharuskin.yggdrasil;

public class YggException extends RuntimeException {
    final int status;
    final String error;
    public YggException(int status, String error, String message) { super(message); this.status=status; this.error=error; }
    static YggException invalid() { return new YggException(403,"ForbiddenOperationException","Invalid token."); }
    static YggException forbidden(String message) { return new YggException(403,"ForbiddenOperationException",message); }
    static YggException badRequest(String message) { return new YggException(400,"IllegalArgumentException",message); }
}
