package com.wangfugui.notebookagent.core;

/** Application error carrying an HTTP status and a FastAPI-style `detail` body. */
public class ApiException extends RuntimeException {

    private final int status;
    private final Object detail;

    public ApiException(int status, Object detail) {
        super(detail instanceof String s ? s : Json.write(detail));
        this.status = status;
        this.detail = detail;
    }

    public int getStatus() {
        return status;
    }

    public Object getDetail() {
        return detail;
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(422, message);
    }
}
