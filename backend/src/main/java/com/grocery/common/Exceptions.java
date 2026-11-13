package com.grocery.common;

public class Exceptions {
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
    }
    public static class BusinessException extends RuntimeException {
        public BusinessException(String m) { super(m); }
    }
}
