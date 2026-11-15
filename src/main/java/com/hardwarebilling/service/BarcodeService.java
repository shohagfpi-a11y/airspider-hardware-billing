package com.hardwarebilling.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

/** Code 128 barcode of the invoice number as an embeddable PNG data URI (works in print and PDF). */
@Service
public class BarcodeService {

    public String code128DataUri(String text) {
        try {
            // quiet zone is added around the bars by the document CSS; width scales with content length
            BitMatrix m = new Code128Writer().encode(text, BarcodeFormat.CODE_128, 0, 60, Map.of(EncodeHintType.MARGIN, 0));
            // enlarge x3 for crisp printing
            int module = 3;
            int w = m.getWidth() * module;
            BitMatrix big = new Code128Writer().encode(text, BarcodeFormat.CODE_128, w, 90, Map.of(EncodeHintType.MARGIN, 0));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(big, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new BusinessException("Could not generate barcode");
        }
    }
}
