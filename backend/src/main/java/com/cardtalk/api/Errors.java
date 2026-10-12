package com.cardtalk.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> known(ApiException ex) { return ResponseEntity.status(ex.status).body(Map.of("code",ex.code,"message",ex.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception ex) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","请求格式或字段不符合要求")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception ex) { return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","服务暂时不可用，请重试")); }
}
