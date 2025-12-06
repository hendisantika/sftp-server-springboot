package com.sftp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "sftp.enabled=false"
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void securityHeaders_shouldBePresent() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Referrer-Policy"));
    }

    @Test
    void staticResources_shouldBeAccessibleWithoutAuth() throws Exception {
        // CSS files should be accessible
        mockMvc.perform(get("/css/nonexistent.css"))
                .andExpect(status().isNotFound()); // 404 is fine, just not 401/403

        // JS files should be accessible
        mockMvc.perform(get("/js/nonexistent.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void protectedEndpoint_withoutAuth_shouldRedirect() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void protectedEndpoint_withUserRole_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(view().name("filelist"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminEndpoint_withAdminRole_shouldBeAccessible() throws Exception {
        // Admin endpoints would return 404 if not implemented, but not 403
        mockMvc.perform(get("/admin/test"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void adminEndpoint_withUserRole_shouldBeForbidden() throws Exception {
        mockMvc.perform(get("/admin/test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicEndpoints_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/home"))
                .andExpect(status().isOk());
    }
}
