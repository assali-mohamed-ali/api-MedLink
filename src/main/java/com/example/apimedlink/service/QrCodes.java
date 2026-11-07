package com.example.apimedlink.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * MLink-62: the enrolment URL as a QR code, a PNG in a data URI that React shows in an {@code <img>}. ZXing
 * draws the modules; the JDK writes the PNG, so only ZXing's {@code core} is needed.
 */
public final class QrCodes {

    private static final int SIZE = 240; // pixels; a phone reads it from a screen at arm's length
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN, 2);

    private QrCodes() {}

    public static String pngDataUri(String text) {
        BitMatrix modules;
        try {
            modules = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, HINTS);
        } catch (WriterException tooLong) {
            throw new IllegalArgumentException("Text too long for a QR code", tooLong);
        }
        BufferedImage image = new BufferedImage(modules.getWidth(), modules.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < modules.getHeight(); y++) {
            for (int x = 0; x < modules.getWidth(); x++) {
                image.setRGB(x, y, modules.get(x, y) ? BLACK : WHITE);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException("PNG writing in memory failed", e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }
}
