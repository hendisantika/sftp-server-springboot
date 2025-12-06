package com.sftp.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "sftp.enabled=false"
})
class FileControllerTest {

    @TempDir
    Path tempDir;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test file in the temp directory
        Files.writeString(tempDir.resolve("test.txt"), "Hello, World!");
    }

    @Test
    void listFiles_unauthenticated_shouldRedirectToLogin() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void listFiles_authenticated_shouldReturnFilelist() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(view().name("filelist"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void downloadFile_invalidFilename_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/files/download/../etc/passwd"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void downloadFile_pathTraversalAttempt_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/files/download/..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void downloadFile_nonExistentFile_shouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/files/download/nonexistent.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void deleteFile_invalidFilename_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(delete("/files/delete/../test.txt").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void deleteFile_nonExistentFile_shouldReturnNotFound() throws Exception {
        mockMvc.perform(delete("/files/delete/nonexistent.txt").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void previewFile_nonExistentFile_shouldShowError() throws Exception {
        mockMvc.perform(get("/files/preview/nonexistent.txt"))
                .andExpect(status().isOk())
                .andExpect(view().name("preview"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void upload_emptyFile_shouldRedirectWithError() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.txt",
                MediaType.TEXT_PLAIN_VALUE,
                new byte[0]
        );

        mockMvc.perform(multipart("/files/upload")
                        .file(emptyFile)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?error=empty"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void upload_disallowedExtension_shouldRedirectWithError() throws Exception {
        MockMultipartFile exeFile = new MockMultipartFile(
                "file",
                "malware.exe",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "malicious content".getBytes()
        );

        mockMvc.perform(multipart("/files/upload")
                        .file(exeFile)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?error=type"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = "USER")
    void upload_pathTraversalFilename_shouldRedirectWithError() throws Exception {
        MockMultipartFile maliciousFile = new MockMultipartFile(
                "file",
                "../../../etc/passwd",
                MediaType.TEXT_PLAIN_VALUE,
                "content".getBytes()
        );

        mockMvc.perform(multipart("/files/upload")
                        .file(maliciousFile)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?error=invalid"));
    }

    @Test
    void upload_withoutCsrf_shouldBeRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "content".getBytes()
        );

        // Without CSRF, request should be rejected (either 403 or redirect to login)
        mockMvc.perform(multipart("/files/upload").file(file))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status == 403 || status == 302 : "Expected 403 or 302, got " + status;
                });
    }
}
