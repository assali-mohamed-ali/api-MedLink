package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.MfaSetupResponse;
import com.example.apimedlink.service.MfaService;
import com.example.apimedlink.service.QrCodes;

/** Staff code enrolment to API shape (endpoint 8). Called by controllers: services never see a DTO. */
public final class MfaMapper {

    private MfaMapper() {}

    public static MfaSetupResponse toSetupResponse(MfaService.Enrolment enrolment) {
        return new MfaSetupResponse(
                enrolment.otpauthUrl(), QrCodes.pngDataUri(enrolment.otpauthUrl()), enrolment.secret());
    }
}
