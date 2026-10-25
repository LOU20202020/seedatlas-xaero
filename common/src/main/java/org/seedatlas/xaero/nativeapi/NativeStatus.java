package org.seedatlas.xaero.nativeapi;

/** Result of probing the bundled native library without creating a seed context. */
public record NativeStatus(
    boolean available,
    String engineVersion,
    String platform,
    String message
) {}
