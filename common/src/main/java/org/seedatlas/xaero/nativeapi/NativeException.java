package org.seedatlas.xaero.nativeapi;

/** Native loading, ABI, or generation failure. */
public class NativeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int errorCode;

    public NativeException(String message) {
        this(message, 0, null);
    }

    public NativeException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    NativeException(String message, int errorCode) {
        this(message, errorCode, null);
    }

    private NativeException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }
}
