package top.twinklestars.koharuskin.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ValidationErrors {
    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> business(BusinessException exception) {
        return Map.of("code", 1, "message", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> validation(MethodArgumentNotValidException exception) {
        Map<String, List<String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(error -> error.getField(),
                        Collectors.mapping(error -> error.getDefaultMessage(), Collectors.toList())));
        return Map.of("message", "请检查输入内容", "errors", errors);
    }
}
