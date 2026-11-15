package com.hardwarebilling.service;

import com.hardwarebilling.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores company images (logo, stamp, signature). Only real PNG/JPEG files up to 2 MB are accepted
 * (checked by magic bytes AND by decoding). Files get random names and are served only through an
 * authenticated controller. Old files are kept so issued invoices keep their original images.
 */
@Service
public class FileStorageService {
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("[a-f0-9\\-]{36}\\.(png|jpg)");
    private final Path dir;

    public FileStorageService(AppProperties props) throws IOException {
        this.dir = Path.of(props.uploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        if (file.getSize() > MAX_BYTES) throw new BusinessException("Image must be 2 MB or smaller");
        try {
            return storeBytes(file.getBytes());
        } catch (IOException e) {
            throw new BusinessException("Could not read the uploaded image");
        }
    }

    /** Stores PNG/JPEG bytes (uploads and bundled/generated demo images) after the same checks. */
    public String storeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length > MAX_BYTES) throw new BusinessException("Image must be 2 MB or smaller");
        try {
            String ext;
            if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') ext = "png";
            else if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) ext = "jpg";
            else throw new BusinessException("Only PNG or JPEG images are allowed");
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw new BusinessException("The file is not a valid image");
            String name = UUID.randomUUID() + "." + ext;
            Files.write(dir.resolve(name), bytes);
            return name;
        } catch (IOException e) {
            throw new BusinessException("Could not read the uploaded image");
        }
    }

    public byte[] read(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) return null;
        try {
            Path p = dir.resolve(name).normalize();
            if (!p.startsWith(dir) || !Files.isRegularFile(p)) return null;
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean exists(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) return false;
        Path p = dir.resolve(name).normalize();
        return p.startsWith(dir) && Files.isRegularFile(p);
    }

    public String contentType(String name) {
        return name != null && name.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    /** data: URI used to embed the same image in the HTML print view and the PDF. */
    public String dataUri(String name) {
        byte[] b = read(name);
        return b == null ? null : "data:" + contentType(name) + ";base64," + Base64.getEncoder().encodeToString(b);
    }
}
