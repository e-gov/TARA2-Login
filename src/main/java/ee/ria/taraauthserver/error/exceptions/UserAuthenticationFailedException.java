package ee.ria.taraauthserver.error.exceptions;

import lombok.Getter;

@Getter
public class UserAuthenticationFailedException extends TaraAuthenticationException {

    public UserAuthenticationFailedException(String errorMessageKey, String exceptionMessage) {
        super(errorMessageKey, exceptionMessage);
    }

    public UserAuthenticationFailedException(String errorMessageKey, String exceptionMessage, Throwable cause) {
        super(errorMessageKey, exceptionMessage, cause);
    }
}