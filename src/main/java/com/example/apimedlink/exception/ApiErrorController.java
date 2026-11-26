package com.example.apimedlink.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The container's error path for failures raised before a controller method exists: the multipart
 * limit is enforced while the request is parsed, so {@code GlobalExceptionHandler} cannot see it and
 * Spring Boot would return its default body without a {@code code}. This controller keeps the API's
 * one error shape and maps a too-large upload to {@code FILE_TOO_LARGE}.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        HttpStatus status = status(request);
        if (status == HttpStatus.CONTENT_TOO_LARGE) {
            return ResponseEntity.status(status).body(ErrorBody.of(status, "FILE_TOO_LARGE",
                    "Le fichier dépasse la taille maximale de 10 Mo.", null));
        }
        return ResponseEntity.status(status).body(ErrorBody.of(status, code(status), message(status), null));
    }

    private static HttpStatus status(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer code) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String code(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case CONTENT_TOO_LARGE -> "FILE_TOO_LARGE";
            case BAD_REQUEST -> "VALIDATION_FAILED";
            case CONFLICT -> "CONFLICT";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
    }

    private static String message(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "Authentification requise.";
            case FORBIDDEN -> "Vous n'avez pas accès à cette ressource.";
            case NOT_FOUND -> "La ressource demandée n'existe pas.";
            case METHOD_NOT_ALLOWED -> "Cette méthode n'est pas autorisée.";
            case UNSUPPORTED_MEDIA_TYPE -> "Ce type de contenu n'est pas accepté.";
            case CONTENT_TOO_LARGE -> "Le fichier dépasse la taille maximale de 10 Mo.";
            case BAD_REQUEST -> "La requête est mal formée.";
            case CONFLICT -> "La requête entre en conflit avec l'état actuel.";
            case SERVICE_UNAVAILABLE -> "Le service est temporairement indisponible.";
            default -> "Une erreur inattendue est survenue. Réessayez plus tard.";
        };
    }
}
