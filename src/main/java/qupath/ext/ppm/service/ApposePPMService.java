package qupath.ext.ppm.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.apposed.appose.TaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;

/**
 * Singleton managing the Appose Environment and Python Service lifecycle
 * for PPM analysis (ppm_library).
 *
 * <p>Provides an embedded Python runtime for PPM fiber analysis via
 * Appose's shared-memory IPC. The Python worker is a single long-lived
 * subprocess -- globals set in {@code init()} persist across task calls,
 * enabling fast subsequent analysis without per-request overhead.</p>
 *
 * <p>The Appose environment is built from a {@code pixi.toml} bundled in the
 * JAR resources. First-time setup downloads Python and dependencies (~500 MB).
 * Subsequent launches reuse the cached environment.</p>
 *
 * @author Mike Nelson
 * @since 0.2.0
 */
public class ApposePPMService {

    private static final Logger logger = LoggerFactory.getLogger(ApposePPMService.class);

    private static final String RESOURCE_BASE = "qupath/ext/ppm/";
    private static final String PIXI_TOML_RESOURCE = RESOURCE_BASE + "pixi.toml";
    private static final String SCRIPTS_BASE = RESOURCE_BASE + "scripts/";
    private static final String ENV_NAME = "ppm-analysis";

    /**
     * pip install URL for ppm-library from GitHub.
     * Uses archive tarball so git is not required on the user's machine.
     */
    private static final String PPM_LIBRARY_PIP_URL =
            "ppm-library @ https://github.com/uw-loci/" + "ppm_library/archive/refs/heads/main.tar.gz";

    /**
     * Minimum required ppm-library version for this extension version.
     * <p>IMPORTANT: Update this constant whenever ppm_library changes
     * affect the extension (new parameters, API changes, bug fixes).
     * The extension will auto-upgrade ppm_library if the installed
     * version is older than this.</p>
     */
    private static final String REQUIRED_PPM_VERSION = "1.3.2";

    private static ApposePPMService instance;

    private Environment environment;
    private Service pythonService;
    private boolean initialized;
    private boolean versionCompatible;
    private String installedPpmVersion;
    private String initError;
    private Thread shutdownHook;

    private ApposePPMService() {}

    /**
     * Gets the singleton instance.
     */
    public static synchronized ApposePPMService getInstance() {
        if (instance == null) {
            instance = new ApposePPMService();
        }
        return instance;
    }

    /**
     * Checks if the Appose pixi environment appears to be built on disk.
     * Fast filesystem check -- does NOT trigger any downloads.
     */
    public static boolean isEnvironmentBuilt() {
        ApposePPMService svc = instance;
        if (svc != null && svc.environment != null) {
            Path envDir = Path.of(svc.environment.base());
            return Files.isDirectory(envDir.resolve(".pixi"));
        }
        Path envDir = getEnvironmentPath();
        return Files.isDirectory(envDir.resolve(".pixi"));
    }

    /**
     * Returns whether the installed ppm_library version is compatible with this extension.
     * Returns false if the environment hasn't been initialized yet, or if the version is too old.
     */
    public boolean isVersionCompatible() {
        return initialized && versionCompatible;
    }

    /**
     * Returns the installed ppm_library version, or null if not yet detected.
     */
    public String getInstalledPpmVersion() {
        return installedPpmVersion;
    }

    /**
     * Returns the minimum required ppm_library version for this extension.
     */
    public static String getRequiredPpmVersion() {
        return REQUIRED_PPM_VERSION;
    }

    /**
     * Returns the path where the Appose pixi environment is stored.
     */
    public static Path getEnvironmentPath() {
        ApposePPMService svc = instance;
        if (svc != null && svc.environment != null) {
            return Path.of(svc.environment.base());
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "appose", ENV_NAME);
    }

    /**
     * Builds the pixi environment and starts the Python service.
     * Slow the first time (downloads dependencies), instant on subsequent runs.
     *
     * @param statusCallback optional callback for progress messages (may be null)
     * @throws IOException if resource loading or environment build fails
     */
    public synchronized void initialize(Consumer<String> statusCallback) throws IOException {
        if (initialized) {
            report(statusCallback, "Already initialized");
            return;
        }

        try {
            report(statusCallback, "Loading environment configuration...");
            logger.info("Initializing PPM Appose environment...");

            String pixiToml = loadResource(PIXI_TOML_RESOURCE);

            // ALL Appose operations require the extension classloader as TCCL.
            // Appose and its dependencies use ServiceLoader internally.
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposePPMService.class.getClassLoader());

            try {
                // Sync pixi.toml: detect version changes
                syncPixiToml(pixiToml);

                report(statusCallback, "Building pixi environment (this may take several minutes)...");

                // Build the pixi environment (downloads deps on first run)
                environment = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(ENV_NAME)
                        .logDebug()
                        .build();

                logger.info("Appose environment configured at: {}", environment.base());

                // Install dependencies via pixi, then ppm-library via pip.
                // Appose's build() with .content() only writes pixi.toml -- it does
                // NOT run pixi install. We must do that explicitly.
                installPPMLibrary(statusCallback);

                report(statusCallback, "Starting Python service...");

                // Create Python service (lazy - subprocess starts on first task)
                pythonService = environment.python();

                // Log Python stderr at INFO level and route to console window
                pythonService.debug(msg -> {
                    logger.info("[PPM Python] {}", msg);
                    qupath.ext.ppm.ui.PythonConsoleWindow.appendMessage(msg);
                });

                // Set the init script. Must prepend "import numpy" before the main
                // init script to avoid Windows deadlock (numpy/numpy#24290).
                String initScript = "import numpy\n" + loadScript("init_ppm.py");
                pythonService.init(initScript);

                // Force Python subprocess to start and verify packages
                report(statusCallback, "Verifying installed packages...");
                logger.info("Running PPM environment verification task...");

                String verifyScript = "import ppm_library\n"
                        + "import scipy\n"
                        + "import skimage\n"
                        + "task.outputs['ppm_version'] = getattr(ppm_library, '__version__', 'unknown')\n"
                        + "task.outputs['init_error'] = str(init_error) if init_error else ''\n";

                Task verifyTask = pythonService.task(verifyScript);
                verifyTask.listen(event -> {
                    if (event.responseType == ResponseType.FAILURE || event.responseType == ResponseType.CRASH) {
                        logger.error("Verification task failed: {}", verifyTask.error);
                    }
                });
                verifyTask.waitFor();

                String ppmVersion = String.valueOf(verifyTask.outputs.get("ppm_version"));
                String pythonInitError = String.valueOf(verifyTask.outputs.get("init_error"));

                if (pythonInitError != null && !pythonInitError.isEmpty()) {
                    throw new IOException("Python init failed: " + pythonInitError);
                }

                installedPpmVersion = ppmVersion;
                logger.info("PPM environment verified: ppm_library {}", ppmVersion);

                // Check if the installed version meets the minimum requirement.
                // If not, auto-upgrade and restart the service.
                if (!isVersionSufficient(ppmVersion, REQUIRED_PPM_VERSION)) {
                    logger.warn(
                            "ppm_library {} is older than required {}. Auto-upgrading...",
                            ppmVersion,
                            REQUIRED_PPM_VERSION);
                    report(statusCallback, "Upgrading ppm_library (" + ppmVersion + " -> " + REQUIRED_PPM_VERSION + "+)...");

                    // Shut down old service before upgrading
                    if (pythonService != null) {
                        pythonService.close();
                        pythonService = null;
                    }

                    // Re-run pip install to get latest
                    installPPMLibrary(statusCallback);

                    // Restart service and re-verify
                    pythonService = environment.python();
                    pythonService.debug(msg -> {
                        logger.info("[PPM Python] {}", msg);
                        qupath.ext.ppm.ui.PythonConsoleWindow.appendMessage(msg);
                    });
                    pythonService.init("import numpy\n" + loadScript("init_ppm.py"));

                    Task reverifyTask = pythonService.task(verifyScript);
                    reverifyTask.waitFor();
                    ppmVersion = String.valueOf(reverifyTask.outputs.get("ppm_version"));
                    installedPpmVersion = ppmVersion;

                    if (!isVersionSufficient(ppmVersion, REQUIRED_PPM_VERSION)) {
                        logger.error(
                                "ppm_library {} still does not meet required {} after upgrade",
                                ppmVersion,
                                REQUIRED_PPM_VERSION);
                        versionCompatible = false;
                        initError = "ppm_library " + ppmVersion + " is outdated. "
                                + "Required: " + REQUIRED_PPM_VERSION + "+. "
                                + "The GitHub main branch may not have been updated yet.";
                    } else {
                        logger.info("ppm_library upgraded to {}", ppmVersion);
                        versionCompatible = true;
                    }
                } else {
                    versionCompatible = true;
                }

                String extVersion = GeneralTools.getPackageVersion(ApposePPMService.class);
                logger.info("=== PPM Analysis Environment ===");
                logger.info("  Extension version: {}", extVersion != null ? extVersion : "dev");
                logger.info("  ppm_library version: {}", ppmVersion);
                logger.info("  Required ppm_library: >= {}", REQUIRED_PPM_VERSION);
                logger.info("  Version compatible: {}", versionCompatible);
                logger.info("  Environment path: {}", getEnvironmentPath());
                logger.info("=================================");

                initialized = true;
                initError = versionCompatible ? null : initError;
                registerShutdownHook();
                report(statusCallback, versionCompatible
                        ? "Setup complete! (ppm_library " + ppmVersion + ")"
                        : "WARNING: ppm_library " + ppmVersion + " is outdated (need " + REQUIRED_PPM_VERSION + "+)");
                logger.info("PPM Appose Python service initialized");

            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }

        } catch (Exception e) {
            initError = e.getMessage();
            initialized = false;
            logger.error("Failed to initialize PPM Appose: {}", e.getMessage(), e);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Runs a named task script with the given inputs.
     *
     * @param scriptName script name without .py extension
     * @param inputs     map of input values passed to the script
     * @return the completed Task with outputs
     * @throws IOException if the service is not available or the task fails
     */
    public Task runTask(String scriptName, Map<String, Object> inputs) throws IOException {
        ensureInitialized();

        String script;
        try {
            script = loadScript(scriptName + ".py");
        } catch (IOException e) {
            throw new IOException("Failed to load task script: " + scriptName, e);
        }

        // TCCL must be set for Groovy JSON serialization and SharedMemory/NDArray ops
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposePPMService.class.getClassLoader());
        try {
            Task task = pythonService.task(script, inputs);
            task.listen(event -> {
                if (event.responseType == ResponseType.CRASH) {
                    logger.error("PPM task '{}' CRASH: {}", scriptName, task.error);
                } else if (event.responseType == ResponseType.FAILURE) {
                    logger.error("PPM task '{}' FAILURE: {}", scriptName, task.error);
                }
            });
            task.waitFor();
            return task;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("PPM task '" + scriptName + "' interrupted", e);
        } catch (TaskException e) {
            throw new IOException("PPM task '" + scriptName + "' failed: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Gracefully shuts down the Python service.
     * Closes stdin first, then force-kills if needed after 5 seconds.
     */
    public synchronized void shutdown() {
        if (pythonService != null) {
            try {
                logger.info("Shutting down PPM Python service...");
                pythonService.close();

                if (pythonService.isAlive()) {
                    long deadline = System.currentTimeMillis() + 5000;
                    while (pythonService.isAlive() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (pythonService.isAlive()) {
                    logger.warn("PPM Python service did not exit gracefully, force-killing");
                    pythonService.kill();
                }
            } catch (Exception e) {
                try {
                    pythonService.kill();
                } catch (Exception ignored) {
                    // nothing more we can do
                }
                logger.warn("Error during PPM Python shutdown: {}", e.getMessage());
            }
            pythonService = null;
        }
        initialized = false;
        removeShutdownHook();
        logger.info("PPM Appose service shut down");
    }

    /**
     * Deletes the Appose pixi environment from disk.
     * Service must be shut down first via {@link #shutdown()}.
     *
     * @throws IOException if the environment directory cannot be deleted
     */
    public synchronized void deleteEnvironment() throws IOException {
        if (pythonService != null) {
            throw new IOException(
                    "Cannot delete environment while Python service is running. " + "Call shutdown() first.");
        }
        if (environment != null) {
            try {
                logger.info("Deleting PPM environment via API: {}", environment.base());
                environment.delete();
                environment = null;
                logger.info("PPM environment deleted");
                return;
            } catch (Exception e) {
                logger.warn("environment.delete() failed, falling back to manual deletion: {}", e.getMessage());
                environment = null;
            }
        }
        Path envPath = getEnvironmentPath();
        if (Files.exists(envPath)) {
            logger.info("Deleting environment directory: {}", envPath);
            deleteDirectoryRecursively(envPath);
            logger.info("Environment directory deleted");
        }
    }

    /**
     * Checks whether the service is initialized and available.
     */
    public boolean isAvailable() {
        return initialized && initError == null && pythonService != null;
    }

    /**
     * Gets the initialization error message, if any.
     */
    public String getInitError() {
        return initError;
    }

    /**
     * Executes a callable with the TCCL set to the extension's classloader.
     */
    public static <T> T withExtensionClassLoader(java.util.concurrent.Callable<T> callable) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposePPMService.class.getClassLoader());
        try {
            return callable.call();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    // ==================== Internal Helpers ====================

    /**
     * Installs pixi dependencies and ppm-library via pip.
     */
    private void installPPMLibrary(Consumer<String> statusCallback) throws IOException {
        Path envBase = Path.of(environment.base());
        Path manifestPath = envBase.resolve("pixi.toml");

        Path pixi = findPixiBinary();
        if (pixi == null) {
            throw new IOException("Cannot find pixi binary. "
                    + "The Appose environment may not have been set up correctly. "
                    + "Try PPM > Rebuild PPM Analysis Environment.");
        }

        // Run "pixi install" to resolve all conda dependencies
        logger.info("Running pixi install to resolve dependencies...");
        report(statusCallback, "Installing Python dependencies (this may take several minutes on first run)...");
        runPixiCommand(pixi, envBase, manifestPath, "install");

        // Install ppm-library via "pixi run pip install ..."
        logger.info("Installing ppm-library via pixi run pip...");
        report(statusCallback, "Installing ppm-library package...");

        java.util.List<String> command = java.util.List.of(
                pixi.toString(),
                "run",
                "--manifest-path",
                manifestPath.toString(),
                "pip",
                "install",
                "--upgrade",
                "--no-deps",
                PPM_LIBRARY_PIP_URL);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(envBase.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.info("[pip] {}", line);
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pip install interrupted", e);
        }

        if (exitCode != 0) {
            throw new IOException("pip install ppm-library failed (exit code " + exitCode + "):\n" + output);
        }
        logger.info("ppm-library installed successfully");
    }

    /**
     * Runs a pixi command and waits for completion.
     */
    private void runPixiCommand(Path pixi, Path workDir, Path manifestPath, String... args) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(pixi.toString());
        for (String arg : args) {
            command.add(arg);
        }
        command.add("--manifest-path");
        command.add(manifestPath.toString());

        logger.info("Running: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.info("[pixi] {}", line);
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pixi command interrupted", e);
        }

        if (exitCode != 0) {
            throw new IOException("pixi " + args[0] + " failed (exit code " + exitCode + "):\n" + output);
        }
    }

    /**
     * Finds the pixi binary. Checks Appose's default install location first,
     * then falls back to system PATH.
     */
    private Path findPixiBinary() {
        Path apposeDir = Path.of(System.getProperty("user.home"), ".local", "share", "appose");
        String pixiName = GeneralTools.isWindows() ? "pixi.exe" : "pixi";
        Path pixi = apposeDir.resolve(".pixi").resolve("bin").resolve(pixiName);
        if (Files.isRegularFile(pixi)) return pixi;

        // Check system PATH
        try {
            Process p = new ProcessBuilder(pixiName, "--version")
                    .redirectErrorStream(true)
                    .start();
            int exit = p.waitFor();
            if (exit == 0) {
                return Path.of(pixiName);
            }
        } catch (IOException | InterruptedException ignored) {
            // Not on PATH
        }

        return null;
    }

    /**
     * Ensures the pixi.toml on disk matches the bundled content.
     * If different, overwrites and deletes pixi.lock + .pixi/ to force rebuild.
     */
    private void syncPixiToml(String expectedContent) {
        try {
            Path envDir = getEnvironmentPath();
            Path pixiTomlFile = envDir.resolve("pixi.toml");
            if (!Files.exists(pixiTomlFile)) {
                return; // First-time install
            }
            String existingContent = Files.readString(pixiTomlFile, StandardCharsets.UTF_8);
            String normalizedExisting = existingContent.replace("\r\n", "\n").strip();
            String normalizedExpected = expectedContent.replace("\r\n", "\n").strip();
            if (normalizedExisting.equals(normalizedExpected)) {
                return;
            }
            logger.info("pixi.toml content changed - updating and forcing environment rebuild");
            Files.writeString(pixiTomlFile, expectedContent, StandardCharsets.UTF_8);
            Files.deleteIfExists(envDir.resolve("pixi.lock"));
            // Delete .pixi/ so Appose doesn't skip the build.
            // On Windows, files may be locked -- use rename-then-delete fallback.
            Path pixiDir = envDir.resolve(".pixi");
            if (Files.isDirectory(pixiDir)) {
                try {
                    deleteDirectoryRecursively(pixiDir);
                } catch (IOException e) {
                    Path renamed = envDir.resolve(".pixi_old_" + System.currentTimeMillis());
                    try {
                        Files.move(pixiDir, renamed);
                        logger.info("Could not delete .pixi/ (locked files), renamed to {}", renamed.getFileName());
                    } catch (IOException e2) {
                        logger.warn(
                                "Could not delete or rename .pixi/ -- "
                                        + "environment may not rebuild automatically. "
                                        + "Use PPM > Rebuild PPM Analysis Environment to force rebuild. "
                                        + "Error: {}",
                                e2.getMessage());
                    }
                }
            }
            logger.info("Environment sync complete - next build will re-resolve dependencies");
        } catch (IOException e) {
            logger.warn("Failed to sync pixi.toml (will attempt build anyway): {}", e.getMessage());
        }
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        java.nio.file.FileVisitor<Path> visitor = new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(directory, visitor);
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = new Thread(
                () -> {
                    logger.info("JVM shutdown hook: cleaning up PPM Python subprocess");
                    Service svc = pythonService;
                    if (svc != null) {
                        try {
                            svc.close();
                            if (svc.isAlive()) {
                                Thread.sleep(2000);
                            }
                            if (svc.isAlive()) {
                                svc.kill();
                            }
                        } catch (Exception e) {
                            try {
                                svc.kill();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                },
                "PPMAnalysis-ShutdownHook");
        shutdownHook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM already shutting down
            }
            shutdownHook = null;
        }
    }

    private void ensureInitialized() throws IOException {
        if (!isAvailable()) {
            throw new IOException("PPM Appose service is not available" + (initError != null ? ": " + initError : ""));
        }
    }

    String loadScript(String scriptFileName) throws IOException {
        return loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private static String loadResource(String resourcePath) throws IOException {
        try (InputStream is = ApposePPMService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    /**
     * Compares two semantic version strings (e.g., "1.3.0" vs "1.3.1").
     * Returns true if {@code installed} is >= {@code required}.
     */
    static boolean isVersionSufficient(String installed, String required) {
        if (installed == null || installed.isEmpty()
                || "unknown".equals(installed) || "null".equals(installed)) {
            return false;
        }
        try {
            int[] inst = parseVersion(installed);
            int[] req = parseVersion(required);
            for (int i = 0; i < Math.max(inst.length, req.length); i++) {
                int a = i < inst.length ? inst[i] : 0;
                int b = i < req.length ? req[i] : 0;
                if (a > b) return true;
                if (a < b) return false;
            }
            return true; // equal
        } catch (NumberFormatException e) {
            logger.warn("Could not parse version string: installed='{}', required='{}'", installed, required);
            return false;
        }
    }

    private static int[] parseVersion(String version) {
        // Strip any suffix like ".dev0", "-rc1", etc.
        String clean = version.replaceAll("[^0-9.].*", "");
        String[] parts = clean.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i]);
        }
        return result;
    }
}
