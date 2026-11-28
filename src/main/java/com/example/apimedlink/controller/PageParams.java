package com.example.apimedlink.controller;

import com.example.apimedlink.exception.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * {@code page} and {@code size} read as text after the role check, with the rules of {@code GET /api/appointments/me}:
 * page from 0, size from 1 to {@value #MAX_SIZE}. One 400 naming each invalid parameter.
 */
record PageParams(int page, int size) {

    static final int MAX_SIZE = 50;

    static PageParams read(String pageText, String sizeText) {
        Map<String, String> errors = new LinkedHashMap<>();
        Integer page = number("page", pageText, errors);
        Integer size = number("size", sizeText, errors);
        if (page != null && page < 0) {
            errors.put("page", "Le numéro de page ne peut pas être négatif.");
        }
        if (size != null && (size < 1 || size > MAX_SIZE)) {
            errors.put("size", "La taille de page doit être comprise entre 1 et 50.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", errors);
        }
        return new PageParams(page, size);
    }

    private static Integer number(String name, String text, Map<String, String> errors) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException | NullPointerException notANumber) {
            errors.put(name, "Ce paramètre doit être un nombre entier.");
            return null;
        }
    }
}
