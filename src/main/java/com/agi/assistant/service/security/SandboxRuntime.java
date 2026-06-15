package com.agi.assistant.service.security;

import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱运行时
 * <p>
 * 在隔离的 Docker 容器中执行用户提交的代码，安全配置如下：
 * <ul>
 *   <li>网络隔离：network=none</li>
 *   <li>只读文件系统：read_only=true</li>
 *   <li>移除所有 Linux capabilities：cap_drop=ALL</li>
 *   <li>禁止提权：no_new_privileges</li>
 *   <li>内存限制：256MB</li>
 *   <li>CPU 限制：0.5 核</li>
 *   <li>执行超时：30 秒</li>
 * </ul>
 */
@Slf4j
@Service
public class SandboxRuntime {

    private static final String IMAGE_PYTHON = "python:3.11-slim";
    private static final String IMAGE_NODE = "node:20-slim";
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long MEMORY_LIMIT = 256 * 1024 * 1024L; // 256MB
    private static final double CPU_LIMIT = 0.5;
    private static final String SANDBOX_WORK_DIR = "/sandbox";

    private final DockerClient dockerClient;

    public SandboxRuntime() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 在沙箱中执行代码。
     *
     * @param language 编程语言（python / node）
     * @param code     要执行的代码
     * @param timeout  超时时间（秒），0 表示使用默认值
     * @return 执行结果
     */
    public SandboxExecuteResponse executeCode(String language, String code, int timeout) {
        long actualTimeout = timeout > 0 ? timeout : DEFAULT_TIMEOUT_SECONDS;
        String containerId = null;
        long startTime = System.currentTimeMillis();

        try {
            // 1. 根据语言选择镜像
            String image = resolveImage(language);

            // 2. 创建安全容器
            containerId = createSecureContainer(image, language);

            // 3. 将代码复制到容器中
            String fileName = resolveFileName(language);
            copyCodeToContainer(containerId, code, fileName);

            // 4. 启动容器执行代码
            dockerClient.startContainerCmd(containerId).exec();

            // 5. 等待容器完成（带超时）
            boolean finished = dockerClient.waitContainerCmd(containerId)
                    .start()
                    .awaitStarted(actualTimeout, TimeUnit.SECONDS);

            // 6. 收集输出
            String output = "";
            String error = "";

            if (finished) {
                output = collectStdout(containerId);
                error = collectStderr(containerId);
            } else {
                error = "Execution timed out after " + actualTimeout + " seconds";
            }

            long executionTime = System.currentTimeMillis() - startTime;
            return new SandboxExecuteResponse(output, error, executionTime);

        } catch (Exception e) {
            log.error("Sandbox execution failed: {}", e.getMessage(), e);
            long executionTime = System.currentTimeMillis() - startTime;
            return new SandboxExecuteResponse("", "Sandbox error: " + e.getMessage(), executionTime);

        } finally {
            // 7. 清理容器
            if (containerId != null) {
                removeContainer(containerId);
            }
        }
    }

    /**
     * 创建安全沙箱容器。
     */
    private String createSecureContainer(String image, String language) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode("none")                         // 无网络
                .withReadonlyRootfs(true)                        // 只读文件系统
                .withCapDrop(Capability.ALL)                     // 移除所有 capabilities
                .withSecurityOpts(java.util.List.of("no-new-privileges:true"))  // 禁止提权
                .withMemory(MEMORY_LIMIT)                        // 内存限制 256MB
                .withCpuQuota((long) (CPU_LIMIT * 100000))       // CPU 限制 0.5 核
                .withAutoRemove(false);

        // 创建 tmpfs 挂载以支持可写临时目录
        hostConfig.withTmpFs(java.util.Map.of(
                SANDBOX_WORK_DIR, "size=64m",
                "/tmp", "size=64m"
        ));

        String[] command = resolveCommand(language);

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withWorkingDir(SANDBOX_WORK_DIR)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withNetworkDisabled(true)
                .exec();

        String containerId = container.getId();
        log.info("Created sandbox container: id={}, image={}", containerId, image);
        return containerId;
    }

    /**
     * 将代码文件复制到容器中。
     */
    private void copyCodeToContainer(String containerId, String code, String fileName) {
        try (InputStream codeStream = new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8))) {
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(codeStream)
                    .withRemotePath(SANDBOX_WORK_DIR)
                    .exec();
            log.debug("Code copied to container {}, file: {}", containerId, fileName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy code to container", e);
        }
    }

    /**
     * 收集容器的标准输出。
     */
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

    /**
     * 收集容器的标准错误输出。
     */
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

    /**
     * 移除容器。
     */
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

    /**
     * 根据语言选择镜像。
     */
    private String resolveImage(String language) {
        if (language == null) {
            return IMAGE_PYTHON;
        }
        return switch (language.toLowerCase()) {
            case "python", "py" -> IMAGE_PYTHON;
            case "javascript", "js", "node" -> IMAGE_NODE;
            default -> {
                log.warn("Unsupported language '{}', defaulting to Python", language);
                yield IMAGE_PYTHON;
            }
        };
    }

    /**
     * 根据语言生成执行命令。
     */
    private String[] resolveCommand(String language) {
        if (language == null) {
            return new String[]{"python3", SANDBOX_WORK_DIR + "/main.py"};
        }
        return switch (language.toLowerCase()) {
            case "python", "py" -> new String[]{"python3", SANDBOX_WORK_DIR + "/main.py"};
            case "javascript", "js", "node" -> new String[]{"node", SANDBOX_WORK_DIR + "/main.js"};
            default -> new String[]{"python3", SANDBOX_WORK_DIR + "/main.py"};
        };
    }

    /**
     * 根据语言确定文件名。
     */
    private String resolveFileName(String language) {
        if (language == null) {
            return "main.py";
        }
        return switch (language.toLowerCase()) {
            case "python", "py" -> "main.py";
            case "javascript", "js", "node" -> "main.js";
            default -> "main.py";
        };
    }
}
