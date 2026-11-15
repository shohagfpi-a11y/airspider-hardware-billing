package com.hardwarebilling.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Renders a Thymeleaf document template to HTML (shared with the browser print view) and then to a PDF
 * with OpenHTMLtoPDF. A system TrueType font is embedded when one is found (Arial on Windows,
 * DejaVu/Liberation on Linux); otherwise the built-in Helvetica is used.
 */
@Service
public class PdfService {

    private static final List<String[]> FONTS = List.of(
            new String[]{"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"},
            new String[]{"/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"},
            new String[]{"C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"},
            new String[]{"/System/Library/Fonts/Supplemental/Arial.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf"});

    private final SpringTemplateEngine templates;

    public PdfService(SpringTemplateEngine templates) {
        this.templates = templates;
    }

    /** Read on each render (small file): CSS edits show up without a restart, and print view + PDF always share it. */
    private String docCss() {
        try (InputStream in = getClass().getResourceAsStream("/static/css/doc.css")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("doc.css missing", e);
        }
    }

    /** Renders HTML (used by both the print page and the PDF). */
    public String html(String template, Map<String, Object> model, boolean screen) {
        Context ctx = new Context();
        ctx.setVariables(model);
        ctx.setVariable("docCss", docCss());
        ctx.setVariable("screen", screen);
        return templates.process(template, ctx);
    }

    public byte[] pdf(String template, Map<String, Object> model) {
        try {
            String html = html(template, model, false);
            var doc = new W3CDom().fromJsoup(Jsoup.parse(html));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            for (String[] pair : FONTS) {
                File regular = new File(pair[0]), bold = new File(pair[1]);
                if (regular.isFile() && bold.isFile()) {
                    b.useFont(regular, "AppSans", 400, com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle.NORMAL, true);
                    b.useFont(bold, "AppSans", 700, com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle.NORMAL, true);
                    break;
                }
            }
            b.withW3cDocument(doc, "/");
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF generation failed", e);
        }
    }
}
