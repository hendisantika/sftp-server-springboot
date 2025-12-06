package com.sftp.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class SftpConfig {

    private static final Logger logger = LoggerFactory.getLogger(SftpConfig.class);

    @Value("${sftp.port:2222}")
    private int sftpPort;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, String> userCredentials = new ConcurrentHashMap<>();
    private final Map<String, Path> userHomeDirs = new ConcurrentHashMap<>();
    @Value("${sftp.host-key-path:hostkey.ser}")
    private String hostKeyPath;
    @Value("${sftp.base-directory:${java.io.tmpdir}/sftp-files}")
    private String baseDirectory;
    @Value("${sftp.enabled:true}")
    private boolean sftpEnabled;
    @Value("${sftp.idle-timeout:300000}")
    private long idleTimeout;
    // User credentials loaded from properties (format: user1:hashedPassword,user2:hashedPassword)
    @Value("${sftp.users:}")
    private String usersConfig;
    private SshServer sshServer;

    @PostConstruct
    public void startSftpServer() {
        if (!sftpEnabled) {
            logger.info("SFTP server is disabled");
            return;
        }

        try {
            initializeUsers();
            initializeServer();
            sshServer.start();
            logger.info("SFTP Server started successfully on port {}", sftpPort);
        } catch (IOException e) {
            logger.error("Failed to start SFTP server on port {}: {}", sftpPort, e.getMessage(), e);
            throw new IllegalStateException("Failed to start SFTP server", e);
        }
    }

    @PreDestroy
    public void stopSftpServer() {
        if (sshServer != null && sshServer.isStarted()) {
            try {
                sshServer.stop();
                logger.info("SFTP Server stopped successfully");
            } catch (IOException e) {
                logger.error("Error stopping SFTP server: {}", e.getMessage(), e);
            }
        }
    }

    private void initializeUsers() throws IOException {
        Path basePath = Paths.get(baseDirectory);
        if (!Files.exists(basePath)) {
            Files.createDirectories(basePath);
            logger.info("Created base SFTP directory: {}", basePath);
        }

        if (usersConfig == null || usersConfig.isBlank()) {
            // Default demo user - should be overridden in production
            logger.warn("No SFTP users configured. Using default demo user. Configure sftp.users property for production.");
            String defaultPassword = passwordEncoder.encode("demo123");
            userCredentials.put("demo", defaultPassword);
            Path demoUserDir = basePath.resolve("demo");
            if (!Files.exists(demoUserDir)) {
                Files.createDirectories(demoUserDir);
            }
            userHomeDirs.put("demo", demoUserDir);
        } else {
            // Parse users from configuration: user1:password1,user2:password2
            for (String userEntry : usersConfig.split(",")) {
                String[] parts = userEntry.trim().split(":");
                if (parts.length == 2) {
                    String username = parts[0].trim();
                    String password = parts[1].trim();

                    // If password doesn't start with $2a$ (BCrypt prefix), encode it
                    String hashedPassword = password.startsWith("$2a$")
                            ? password
                            : passwordEncoder.encode(password);

                    userCredentials.put(username, hashedPassword);

                    Path userDir = basePath.resolve(username);
                    if (!Files.exists(userDir)) {
                        Files.createDirectories(userDir);
                    }
                    userHomeDirs.put(username, userDir);
                    logger.info("Configured SFTP user: {}", username);
                }
            }
        }
    }

    private void initializeServer() {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(sftpPort);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get(hostKeyPath)));
        sshServer.setPasswordAuthenticator(createPasswordAuthenticator());

        // Configure idle timeout (in milliseconds)
        sshServer.getProperties().put("idle-timeout", idleTimeout);

        // Configure file system with per-user home directories
        sshServer.setFileSystemFactory(new VirtualFileSystemFactory() {
            @Override
            public FileSystem createFileSystem(SessionContext session) throws IOException {
                String username = session.getUsername();
                Path userHome = userHomeDirs.getOrDefault(username, Paths.get(baseDirectory));
                this.setUserHomeDir(username, userHome);
                return super.createFileSystem(session);
            }
        });

        // Enable SFTP subsystem
        sshServer.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
    }

    private PasswordAuthenticator createPasswordAuthenticator() {
        return new PasswordAuthenticator() {
            @Override
            public boolean authenticate(String username, String password, ServerSession session) {
                if (username == null || password == null) {
                    logger.warn("SFTP authentication failed: null username or password");
                    return false;
                }

                String storedPassword = userCredentials.get(username);
                if (storedPassword == null) {
                    logger.warn("SFTP authentication failed: unknown user '{}'", username);
                    return false;
                }

                boolean authenticated = passwordEncoder.matches(password, storedPassword);
                if (authenticated) {
                    logger.info("SFTP authentication successful for user '{}'", username);
                } else {
                    logger.warn("SFTP authentication failed: invalid password for user '{}'", username);
                }
                return authenticated;
            }
        };
    }
}
