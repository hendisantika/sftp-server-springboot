package com.sftp.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class FileController {
    private static final Logger logger = LoggerFactory.getLogger(FileController.class);
    private final Path folderPath = Paths.get(System.getProperty("java.io.tmpdir"), "sftp-files");

    @GetMapping("/files")
    public String listFiles(Model model) {
        File folder = folderPath.toFile();

        if (!folder.exists()) {
            folder.mkdirs();
        }

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
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) throws MalformedURLException {
        Path filePath = folderPath.resolve(filename).normalize();
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }
    
    @DeleteMapping("/files/delete/{filename:.+}")
    @ResponseBody
    public ResponseEntity<String> deleteFile(@PathVariable String filename) {
        Path filePath = folderPath.resolve(filename).normalize();
        File file = filePath.toFile();

        if (!file.exists() || !file.isFile()) {
			logger.info("Ga ketemu filenya...");
            return ResponseEntity.notFound().build();
        }

        boolean deleted = file.delete();
        if (deleted) {
			System.out.println("Sudah dihapus...");
            return ResponseEntity.ok("File deleted successfully");
        } else {
			System.out.println("Ngapus gagal...");
            return ResponseEntity.status(500).body("Failed to delete file");
        }
    }
    
    @GetMapping("/files/preview/{filename:.+}")
    public String previewFile(@PathVariable String filename, Model model) throws IOException {
        Path filePath = folderPath.resolve(filename).normalize();
        File file = filePath.toFile();

        if (!file.exists() || !file.isFile()) {
            model.addAttribute("error", "File tidak ditemukan.");
            return "preview";
        }

        if (!file.getName().endsWith(".txt"))
        		if (!file.getName().endsWith(".sql")) 
        			if (!file.getName().endsWith(".csv")) {
        				logger.info("Format salah...");
//        				model.addAttribute("message","Format tidak didukung untuk preview");
        		        model.addAttribute("filename", filename);
        		        model.addAttribute("content", "Format tidak didukung untuk preview");
        				return "preview";
        			}

        // Batasi hanya file teks kecil yang bisa dipreview
        if (file.length() > 1024 * 1024) {
	        model.addAttribute("filename", filename);
            model.addAttribute("content", "File terlalu besar untuk ditampilkan.");
            return "preview";
        }
        
        String content = java.nio.file.Files.readString(filePath);
        model.addAttribute("filename", filename);
        model.addAttribute("content", content);
        return "preview";
    }
    
    @PostMapping("/files/upload")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model model) throws IOException {
        if (file.isEmpty()) {
            model.addAttribute("message", "File kosong, silakan pilih file lain.");
            return "redirect:/files";
        }

        if (!Files.exists(folderPath)) {
            Files.createDirectories(folderPath);
        }

        System.out.println("Uploading");
        Path destinationFile = folderPath.resolve(Paths.get(file.getOriginalFilename())).normalize();
        file.transferTo(destinationFile);

        return "redirect:/files";
    }
    
    public static class FileInfo {
        private final String name;
        private final String size;
        private final String lastModified;

        public FileInfo(String name, long sizeInBytes, long lastModifiedMillis) {
            this.name = name;
            this.size = new DecimalFormat("#,##0").format(sizeInBytes);
            this.lastModified = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastModifiedMillis));
        }

        public String getName() {
            return name;
        }

        public String getSize() {
            return size;
        }

        public String getLastModified() {
            return lastModified;
        }
    }
}
