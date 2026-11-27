package com.example.apimedlink.dto;

/** US-25: a practitioner publishes an examination result. Checked by the service (lengths), after the role check. */
public record PublishResultRequest(String title, String content) {

    /** Never print a medical text, whoever logs this object. */
    @Override
    public String toString() {
        return "PublishResultRequest[title=***, content=***]";
    }
}
