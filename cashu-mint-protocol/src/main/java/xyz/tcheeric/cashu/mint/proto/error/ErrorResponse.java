package xyz.tcheeric.cashu.entities.rest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Simple error response carrying a machine-readable code and a human-readable message.
 */
public record ErrorResponse(String code, String message) {

    private static final Properties MESSAGES = new Properties();

    static {
        try (InputStream is = ErrorResponse.class.getClassLoader().getResourceAsStream("messages.properties")) {
            if (is != null) {
                MESSAGES.load(is);
            }
        } catch (IOException ignored) {
        }
    }

    public ErrorResponse(String code) {
        this(code, MESSAGES.getProperty(code, code));
    }

    public String toJson() {
        return String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, message);
    }
}

