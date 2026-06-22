package com.agi.assistant.service.security;

import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SandboxRuntime {

    private static final String SANDBOX_WORK_DIR = "/sandbox";
    private static final int TIMEOUT_EXIT_CODE = 124;

    private final String imagePython;
    private final String imageNode;
    private final String imageJava;
    private final long defaultTimeoutSeconds;
    private final long memoryLimit;
    private final double cpuLimit;
    private final String dockerHost;

    private final DockerClient dockerClient;

    public SandboxRuntime(
            @Value("${sandbox.docker.host:unix:///var/run/docker.sock}") String dockerHost,
            @Value("${sandbox.docker.image-python:python:3.11-slim}") String imagePython,
            @Value("${sandbox.docker.image-node:node:20-slim}") String imageNode,
            @Value("${sandbox.docker.image-java:eclipse-temurin:17-jdk}") String imageJava,
            @Value("${sandbox.docker.timeout-seconds:60}") long defaultTimeoutSeconds,
            @Value("${sandbox.docker.memory-limit:512m}") String memoryLimitStr,
            @Value("${sandbox.docker.cpu-limit:1.0}") double cpuLimit) {
        this.dockerHost = resolveDockerHost(dockerHost);
        this.imagePython = imagePython;
        this.imageNode = imageNode;
        this.imageJava = imageJava;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.cpuLimit = cpuLimit;
        this.memoryLimit = parseMemoryLimit(memoryLimitStr);

        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(this.dockerHost)
                .build();

        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
        log.info("Sandbox Docker client initialized with host {}", this.dockerHost);
    }

    public SandboxExecuteResponse executeCode(String language, String code, int timeout) {
        long actualTimeout = timeout > 0 ? timeout : defaultTimeoutSeconds;
        String containerId = null;
        long startTime = System.currentTimeMillis();

        try {
            String image = resolveImage(language);
            String encodedCode = Base64.getEncoder()
                    .encodeToString((code != null ? code : "").getBytes(StandardCharsets.UTF_8));
            containerId = createSecureContainer(image, language, encodedCode);

            dockerClient.startContainerCmd(containerId).exec();

            WaitContainerResultCallback waitCallback = dockerClient.waitContainerCmd(containerId).start();
            Integer statusCode = null;
            boolean finished;
            try {
                statusCode = waitCallback.awaitStatusCode(actualTimeout, TimeUnit.SECONDS);
                finished = statusCode != null;
            } catch (DockerClientException e) {
                if (!isAwaitTimeout(e)) {
                    throw e;
                }
                finished = false;
            }

            String output = "";
            String error = "";
            int exitCode = finished ? statusCode : TIMEOUT_EXIT_CODE;

            if (finished) {
                output = collectStdout(containerId);
                error = collectStderr(containerId);
            } else {
                stopContainer(containerId);
                error = "Execution timed out after " + actualTimeout + " seconds";
            }

            long executionTime = System.currentTimeMillis() - startTime;
            return new SandboxExecuteResponse(output, error, executionTime, exitCode, 0);
        } catch (Exception e) {
            log.error("Sandbox execution failed: {}", e.getMessage(), e);
            long executionTime = System.currentTimeMillis() - startTime;
            return new SandboxExecuteResponse("", "Sandbox error: " + e.getMessage(), executionTime, 1, 0);
        } finally {
            if (containerId != null) {
                removeContainer(containerId);
            }
        }
    }

    private String resolveDockerHost(String configuredDockerHost) {
        String envDockerHost = System.getenv("DOCKER_HOST");
        if (envDockerHost != null && !envDockerHost.isBlank()) {
            return envDockerHost;
        }

        if (isWindows() && (configuredDockerHost == null
                || configuredDockerHost.isBlank()
                || configuredDockerHost.startsWith("unix://"))) {
            return "npipe:////./pipe/dockerDesktopLinuxEngine";
        }

        return configuredDockerHost != null && !configuredDockerHost.isBlank()
                ? configuredDockerHost
                : "unix:///var/run/docker.sock";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private String createSecureContainer(String image, String language, String encodedCode) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(java.util.List.of("no-new-privileges:true"))
                .withMemory(memoryLimit)
                .withCpuQuota((long) (cpuLimit * 100000))
                .withTmpFs(Map.of(
                        SANDBOX_WORK_DIR, "size=256m",
                        "/tmp", "size=128m"
                ))
                .withAutoRemove(false);

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withWorkingDir(SANDBOX_WORK_DIR)
                .withCmd(resolveCommand(language))
                .withEnv("SANDBOX_CODE_B64=" + encodedCode)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withNetworkDisabled(true)
                .exec();

        String containerId = container.getId();
        log.info("Created sandbox container: id={}, image={}", containerId, image);
        return containerId;
    }

    private String collectStdout(String containerId) {
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(false)
                    .withFollowStream(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame.getStreamType() == StreamType.STDOUT) {
                                try {
                                    stdout.write(frame.getPayload());
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }).awaitCompletion(5, TimeUnit.SECONDS);
            return stdout.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to collect stdout from container {}: {}", containerId, e.getMessage());
            return "";
        }
    }

    private String collectStderr(String containerId) {
        try {
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(false)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame.getStreamType() == StreamType.STDERR) {
                                try {
                                    stderr.write(frame.getPayload());
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }).awaitCompletion(5, TimeUnit.SECONDS);
            return stderr.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to collect stderr from container {}: {}", containerId, e.getMessage());
            return "";
        }
    }

    private void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(1)
                    .exec();
        } catch (DockerException e) {
            log.warn("Failed to stop timed out container {}: {}", containerId, e.getMessage());
        }
    }

    private boolean isAwaitTimeout(DockerClientException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("timeout");
    }

    private void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            log.debug("Removed sandbox container: {}", containerId);
        } catch (DockerException e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    private String resolveImage(String language) {
        if (language == null) {
            return imagePython;
        }
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "python", "py" -> imagePython;
            case "javascript", "js", "node" -> imageNode;
            case "java" -> imageJava;
            default -> {
                log.warn("Unsupported language '{}', defaulting to Python", language);
                yield imagePython;
            }
        };
    }

    private String[] resolveCommand(String language) {
        if (language == null) {
            return new String[]{"sh", "-c", writeCodeCommand("main.py") + " && python3 " + SANDBOX_WORK_DIR + "/main.py"};
        }
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "python", "py" ->
                    new String[]{"sh", "-c", writeCodeCommand("main.py") + " && python3 " + SANDBOX_WORK_DIR + "/main.py"};
            case "javascript", "js", "node" ->
                    new String[]{"sh", "-c", writeCodeCommand("main.js") + " && node " + SANDBOX_WORK_DIR + "/main.js"};
            case "java" -> new String[]{"sh", "-c",
                    writeCodeCommand("Main.java") + " && "
                            + "cp " + SANDBOX_WORK_DIR + "/Main.java /tmp/Main.java && "
                            + "javac /tmp/Main.java -d /tmp && "
                            + "java -cp /tmp Main"};
            default ->
                    new String[]{"sh", "-c", writeCodeCommand("main.py") + " && python3 " + SANDBOX_WORK_DIR + "/main.py"};
        };
    }

    private String writeCodeCommand(String fileName) {
        String filePath = SANDBOX_WORK_DIR + "/" + fileName;
        return "printf '%s' \"$SANDBOX_CODE_B64\" | base64 -d > " + filePath;
    }

    private long parseMemoryLimit(String memoryStr) {
        if (memoryStr == null || memoryStr.isBlank()) {
            return 512 * 1024 * 1024L;
        }

        String str = memoryStr.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;

        if (str.endsWith("g") || str.endsWith("gb")) {
            str = str.replaceAll("[^0-9]", "");
            multiplier = 1024L * 1024 * 1024;
        } else if (str.endsWith("m") || str.endsWith("mb")) {
            str = str.replaceAll("[^0-9]", "");
            multiplier = 1024L * 1024;
        } else if (str.endsWith("k") || str.endsWith("kb")) {
            str = str.replaceAll("[^0-9]", "");
            multiplier = 1024L;
        } else {
            str = str.replaceAll("[^0-9]", "");
        }

        try {
            return Long.parseLong(str) * multiplier;
        } catch (NumberFormatException e) {
            log.warn("Invalid memory limit '{}', using default 512MB", memoryStr);
            return 512 * 1024 * 1024L;
        }
    }
}
