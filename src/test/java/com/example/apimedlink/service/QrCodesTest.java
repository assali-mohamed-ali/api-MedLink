package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * MLink-62: the enrolment answer carries the otpauth URL as a PNG QR code in a data URI, which React shows
 * in an {@code <img>}. Proven by reading the image back: what a phone scans is exactly the URL.
 */
class QrCodesTest {

    static final String URL = Totp.otpauthUrl("staff.dev@medlink.local", Totp.base32(Totp.newSecret()));

    @Test
    void theQrCodeIsAPngDataUri() {
        assertThat(QrCodes.pngDataUri(URL)).startsWith("data:image/png;base64,");
    }

    @Test
    void scanningTheQrCodeGivesBackTheUrl() throws Exception {
        BufferedImage image = image(QrCodes.pngDataUri(URL));

        assertThat(read(image)).isEqualTo(URL);
    }

    @Test
    void theImageIsLargeEnoughToScanFromAScreen() throws Exception {
        BufferedImage image = image(QrCodes.pngDataUri(URL));

        assertThat(image.getWidth()).isGreaterThanOrEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(image.getWidth());
    }

    static BufferedImage image(String dataUri) throws Exception {
        byte[] png = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    static String read(BufferedImage image) throws Exception {
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        RGBLuminanceSource source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
}
