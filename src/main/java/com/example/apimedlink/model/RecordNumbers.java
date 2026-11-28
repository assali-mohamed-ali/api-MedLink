package com.example.apimedlink.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** US-22: a patient's record number as people read and type it, {@code MED-000042}. */
public final class RecordNumbers {

    private static final Pattern TYPED = Pattern.compile("(?i)^\\s*(med[\\s-]*)?(\\d{1,12})\\s*$");

    private RecordNumbers() {}

    /** {@code MED-000042}; null when the number is not known yet (before the first insert). */
    public static String label(Long recordNumber) {
        return recordNumber == null ? null : String.format(Locale.ROOT, "MED-%06d", recordNumber);
    }

    /**
     * The number a search text names, or null: "MED-000042", "med42", "MED 42" or "000042". Digits alone need at least
     * three, so a short number typed for something else is not read as a record.
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher typed = TYPED.matcher(text);
        if (!typed.matches() || (typed.group(1) == null && typed.group(2).length() < 3)) {
            return null;
        }
        return Long.parseLong(typed.group(2));
    }
}
