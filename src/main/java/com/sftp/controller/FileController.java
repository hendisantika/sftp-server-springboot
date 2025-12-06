package com.sftp.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Controller
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    // Allowed file extensions for upload (whitelist approach)
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "csv", "sql", "xml", "json", "log",
            "pdf", "doc", "docx", "xls", "xlsx",
            "jpg", "jpeg", "png", "gif", "bmp",
            "zip", "tar", "gz"
    );

    // Extensions that can be previewed as text
    private static final Set<String> PREVIEWABLE_EXTENSIONS = Set.of(
            "txt", "csv", "sql", "xml", "json", "log", "md", "properties", "yml", "yaml"
    );

    // Maximum file size for upload (10 MB)
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // Maximum file size for preview (1 MB)
    private static final long MAX_PREVIEW_SIZE = 1024 * 1024;

    // Pattern to detect path traversal attempts
    private static final Pattern DANGEROUS_PATH_PATTERN = Pattern.compile(".*[/\\\\]?\\.\\.[/\\\\]?.*");

    private final Path folderPath;

    public FileController(@Value("${app.upload.directory:${java.io.tmpdir}/sftp-files}") String uploadDirectory) {
        this.folderPath = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        initializeDirectory();
    }

    private void initializeDirectory() {
        try {
            if (!Files.exists(folderPath)) {
                Files.createDirectories(folderPath);
                logger.info("Created upload directory: {}", folderPath);
            }
        } catch (IOException e) {
            logger.error("Failed to create upload directory: {}", folderPath, e);
            throw new IllegalStateException("Cannot create upload directory", e);
        }
    }

    @GetMapping("/files")
    public String listFiles(Model model, Principal principal) {
        logger.debug("User '{}' listing files", principal != null ? principal.getName() : "anonymous");

        File folder = folderPath.toFile();
        File[] fileArray = folder.listFiles();

        List<FileInfo> files = fileArray == null
                ? List.of()
                : Arrays.stream(fileArray)
                .filter(File::isFile)
                .map(file -> new FileInfo(file.getName(), file.length(), file.lastModified()))
                .collect(Collectors.toList());

        model.addAttribute("path", folder.getPath());
        model.addAttribute("files", files);
        return "filelist";
    }

    @GetMapping("/files/download/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename, Principal principal) {
        logger.info("User '{}' downloading file: {}", principal != null ? principal.getName() : "anonymous", filename);

        if (!isValidFilename(filename)) {
            logger.warn("Invalid filename requested for download: {}", filename);
            return ResponseEntity.badRequest().build();
        }

        try {
            Path filePath = resolveAndValidatePath(filename);
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                logger.warn("File not found or not readable: {}", filename);
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + sanitizeFilename(filename) + "\"")
                    .body(resource);

        } catch (SecurityException e) {
            logger.warn("Path traversal attempt detected for download: {}", filename);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (MalformedURLException e) {
            logger.error("Malformed URL for file: {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (IOException e) {
            logger.error("Error reading file: {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/files/delete/{filename:.+}")
    @ResponseBody
    public ResponseEntity<String> deleteFile(@PathVariable String filename, Principal principal) {
        logger.info("User '{}' deleting file: {}", principal != null ? principal.getName() : "anonymous", filename);

        if (!isValidFilename(filename)) {
            logger.warn("Invalid filename requested for deletion: {}", filename);
            return ResponseEntity.badRequest().body("Invalid filename");
        }

        try {
            Path filePath = resolveAndValidatePath(filename);
            File file = filePath.toFile();

            if (!file.exists() || !file.isFile()) {
                logger.warn("File not found for deletion: {}", filename);
                return ResponseEntity.notFound().build();
            }

            boolean deleted = file.delete();
            if (deleted) {
                logger.info("File deleted successfully: {}", filename);
                return ResponseEntity.ok("File deleted successfully");
            } else {
                logger.error("Failed to delete file: {}", filename);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to delete file");
            }

        } catch (SecurityException e) {
            logger.warn("Path traversal attempt detected for deletion: {}", filename);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
    }

    @GetMapping("/files/preview/{filename:.+}")
    public String previewFile(@PathVariable String filename, Model model, Principal principal) {
        logger.debug("User '{}' previewing file: {}", principal != null ? principal.getName() : "anonymous", filename);

        model.addAttribute("filename", filename);

        if (!isValidFilename(filename)) {
            logger.warn("Invalid filename requested for preview: {}", filename);
            model.addAttribute("content", "Invalid filename.");
            return "preview";
        }

        try {
            Path filePath = resolveAndValidatePath(filename);
            File file = filePath.toFile();

            if (!file.exists() || !file.isFile()) {
                model.addAttribute("content", "File not found.");
                return "preview";
            }

            String extension = getFileExtension(filename).toLowerCase();
            if (!PREVIEWABLE_EXTENSIONS.contains(extension)) {
                model.addAttribute("content", "File format not supported for preview. Supported formats: " +
                        String.join(", ", PREVIEWABLE_EXTENSIONS));
                return "preview";
            }

            if (file.length() > MAX_PREVIEW_SIZE) {
                model.addAttribute("content", "File too large for preview (max " +
                        (MAX_PREVIEW_SIZE / 1024) + " KB).");
                return "preview";
            }

            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            model.addAttribute("content", content);

        } catch (SecurityException e) {
            logger.warn("Path traversal attempt detected for preview: {}", filename);
            model.addAttribute("content", "Access denied.");
        } catch (IOException e) {
            logger.error("Error reading file for preview: {}", filename, e);
            model.addAttribute("content", "Error reading file.");
        }

        return "preview";
    }

    @PostMapping("/files/upload")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model model, Principal principal) {
        String username = principal != null ? principal.getName() : "anonymous";

        if (file.isEmpty()) {
            logger.warn("User '{}' attempted to upload empty file", username);
            model.addAttribute("error", "Please select a file to upload.");
            return "redirect:/files?error=empty";
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            logger.warn("User '{}' attempted to upload file with no name", username);
            return "redirect:/files?error=invalid";
        }

        // Validate filename
        if (!isValidFilename(originalFilename)) {
            logger.warn("User '{}' attempted to upload file with invalid name: {}", username, originalFilename);
            return "redirect:/files?error=invalid";
        }

        // Validate file extension
        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            logger.warn("User '{}' attempted to upload file with disallowed extension: {}", username, extension);
            return "redirect:/files?error=type";
        }

        // Validate file size
        if (file.getSize() > MAX_FILE_SIZE) {
            logger.warn("User '{}' attempted to upload file exceeding size limit: {} bytes", username, file.getSize());
            return "redirect:/files?error=size";
        }

        try {
            // Sanitize and resolve destination path
            String sanitizedFilename = sanitizeFilename(originalFilename);
            Path destinationFile = folderPath.resolve(sanitizedFilename).normalize();

            // Verify the destination is within the upload directory
            if (!destinationFile.startsWith(folderPath)) {
                logger.warn("Path traversal attempt in upload by user '{}': {}", username, originalFilename);
                return "redirect:/files?error=invalid";
            }

            file.transferTo(destinationFile);
            logger.info("User '{}' uploaded file successfully: {}", username, sanitizedFilename);

            return "redirect:/files?success=upload";

        } catch (IOException e) {
            logger.error("Error uploading file by user '{}': {}", username, originalFilename, e);
            return "redirect:/files?error=upload";
        }
    }

    /**
     * Validates that a filename doesn't contain path traversal characters
     */
    private boolean isValidFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }

        // Check for path traversal patterns
        if (DANGEROUS_PATH_PATTERN.matcher(filename).matches()) {
            return false;
        }

        // Check for null bytes
        if (filename.contains("\0")) {
            return false;
        }

        // Check for other dangerous characters
        return !filename.contains("/") && !filename.contains("\\");
    }

    /**
     * Resolves a filename to a path and validates it's within the allowed directory
     */
    private Path resolveAndValidatePath(String filename) throws SecurityException {
        Path resolvedPath = folderPath.resolve(filename).normalize();

        // Ensure the resolved path is still within the upload directory
        if (!resolvedPath.startsWith(folderPath)) {
            throw new SecurityException("Path traversal attempt detected");
        }

        return resolvedPath;
    }

    /**
     * Sanitizes a filename by removing potentially dangerous characters
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "unnamed";
        }

        // Remove path separators and null bytes
        String sanitized = filename
                .replaceAll("[/\\\\]", "_")
                .replaceAll("\0", "")
                .replaceAll("[<>:\"|?*]", "_");

        // Limit length
        if (sanitized.length() > 255) {
            String extension = getFileExtension(sanitized);
            String baseName = sanitized.substring(0, 255 - extension.length() - 1);
            sanitized = baseName + "." + extension;
        }

        return sanitized;
    }

    /**
     * Extracts file extension from filename
     */
    private String getFileExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    public static class FileInfo {
        private static final DateTimeFormatter DATE_FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        private final String name;
        private final String size;
        private final String lastModified;

        public FileInfo(String name, long sizeInBytes, long lastModifiedMillis) {
            this.name = name;
            this.size = formatFileSize(sizeInBytes);
            this.lastModified = DATE_FORMATTER.format(Instant.ofEpochMilli(lastModifiedMillis));
        }

        private String formatFileSize(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            } else if (bytes < 1024 * 1024) {
                return new DecimalFormat("#,##0.#").format(bytes / 1024.0) + " KB";
            } else if (bytes < 1024 * 1024 * 1024) {
                return new DecimalFormat("#,##0.#").format(bytes / (1024.0 * 1024)) + " MB";
            } else {
                return new DecimalFormat("#,##0.#").format(bytes / (1024.0 * 1024 * 1024)) + " GB";
            }
        }

        public String name() {
            return name;
        }

        public String size() {
            return size;
        }

        public String getLastModified() {
            return lastModified;
        }
    }
}
