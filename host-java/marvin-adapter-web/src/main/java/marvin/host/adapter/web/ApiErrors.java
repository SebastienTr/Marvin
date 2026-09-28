// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.settings.InvalidSettingException;

/** Errors as the app expects them: {@code {"error": "..."}}, 400 for a bad request. */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler({ApiController.BadRequest.class, InvalidSettingException.class,
            VoiceSettings.InvalidVoiceSettingException.class})
    public ResponseEntity<byte[]> badRequest(RuntimeException e) {
        return Responses.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> failed(Exception e) {
        log.error("request failed", e);
        return Responses.error(500, "internal error");
    }
}
