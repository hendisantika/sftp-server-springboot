package com.sftp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "sftp.enabled=false"  // Disable SFTP server during tests
})
class SftpApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring context loads successfully
    }
}
