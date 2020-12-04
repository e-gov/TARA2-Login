package ee.ria.taraauthserver.error.exceptions;

import lombok.Getter;

@Getter
public class TaraAuthenticationException extends RuntimeException {

    private final String errorMessageKey;

    public TaraAuthenticationException(String errorMessageKey, String exceptionMessage) {
        super(exceptionMessage);
        this.errorMessageKey = errorMessageKey;
    }

    public TaraAuthenticationException(String errorMessageKey, String exceptionMessage, Throwable cause) {
        super(exceptionMessage, cause);
        this.errorMessageKey = errorMessageKey;
    }
}