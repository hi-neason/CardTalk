package com.cardtalk.api;

public class ApiException extends RuntimeException {
    final int status;
    final String code;
    public ApiException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public ApiException(int status, String code, String message, Throwable cause) { super(message,cause); this.status=status; this.code=code; }
}
