package com.ripipod.xplataformer.launcher;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.RoundRectangle2D;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("XPlataformer Launcher");
            try {
                Image icon = ImageIO.read(Path.of("icon.png").toFile());
                if (icon != null) frame.setIconImage(icon);
            } catch (Exception ignored) {}

            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            LauncherPanel panel = new LauncherPanel(1067, 600);
            frame.setContentPane(panel);
            frame.pack();
            frame.setMinimumSize(new Dimension(900, 520));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.boot();
        });
    }

    static final class LauncherPanel extends JPanel implements MouseListener, MouseMotionListener {
        private static final String REPO_RELEASES_API = "https://api.github.com/repos/ripipod/xplataformer/releases";
        private static final String USER_AGENT = "XPlataformerLauncher/1.1";
        private static final String SOURCE_LEVELS_ZIP_URL = "https://raw.githubusercontent.com/ripipod/XPlataformer/main/levels.zip";
        private static final Pattern SHA256_HEX = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b");

        final int W;
        final int H;
        final Rectangle btnRefresh = new Rectangle(70, 120, 280, 56);
        final Rectangle btnDownload = new Rectangle(370, 120, 300, 56);
        final Rectangle btnLaunch = new Rectangle(690, 120, 300, 56);
        final Rectangle remoteBox = new Rectangle(70, 205, 920, 145);
        final Rectangle localBox = new Rectangle(70, 375, 920, 170);

        int mouseX;
        int mouseY;
        float renderScale = 1f;
        int renderOffsetX;
        int renderOffsetY;
        int selectedRemote = -1;
        int selectedLocal = -1;

        String status = "Ready.";
        boolean busy;
        Image backgroundImage;

        final Path launcherBaseDir = resolveLauncherBaseDir();
        final Path installRoot = launcherBaseDir.resolve("XPLATAFORMJARS");
        final List<RemoteJar> remoteJars = new ArrayList<>();
        final List<InstalledJar> localJars = new ArrayList<>();

        LauncherPanel(int w, int h) {
            W = w;
            H = h;
            setPreferredSize(new Dimension(W, H));
            addMouseListener(this);
            addMouseMotionListener(this);
            setFocusable(true);
            loadBackground();
        }

        void boot() {
            try {
                Files.createDirectories(installRoot);
            } catch (IOException e) {
                setStatus("Could not create install folder: " + e.getMessage());
            }
            refreshLocalJars();
            fetchReleasesAsync();
        }

        private Path resolveLauncherBaseDir() {
            try {
                URI location = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI();
                Path path = Path.of(location).toAbsolutePath();
                return Files.isRegularFile(path) ? path.getParent() : path;
            } catch (Exception ignored) {
                return Path.of(".").toAbsolutePath().normalize();
            }
        }

        private void loadBackground() {
            try {
                Path bg = Path.of("background.png");
                if (Files.exists(bg)) backgroundImage = ImageIO.read(bg.toFile());
            } catch (Exception ignored) {}
        }

        private void setStatus(String text) { status = text; repaint(); }
        private void setBusy(boolean value) { busy = value; repaint(); }
        private void setStatusFromWorker(String text) { SwingUtilities.invokeLater(() -> setStatus(text)); }

        private void fetchReleasesAsync() {
            setBusy(true);
            setStatus("Loading releases from GitHub...");
            new SwingWorker<List<RemoteJar>, Void>() {
                @Override protected List<RemoteJar> doInBackground() throws Exception { return fetchRemoteJars(); }
                @Override protected void done() {
                    try {
                        remoteJars.clear();
                        remoteJars.addAll(get());
                        if (!remoteJars.isEmpty()) {
                            selectedRemote = 0;
                            setStatus("Releases loaded: " + remoteJars.size() + " jar asset(s).");
                        } else {
                            selectedRemote = -1;
                            setStatus("No JAR assets found in repository releases.");
                        }
                    } catch (Exception e) {
                        selectedRemote = -1;
                        setStatus("Failed to load releases: " + e.getMessage());
                    } finally {
                        setBusy(false);
                    }
                }
            }.execute();
        }

        private List<RemoteJar> fetchRemoteJars() throws IOException {
            String body = httpGetText(REPO_RELEASES_API);
            return parseJarAssets(body);
        }

        private List<RemoteJar> parseJarAssets(String json) {
            List<RemoteJar> list = new ArrayList<>();
            Pattern releasePattern = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"(.*?)(?=\\\"tag_name\\\"\\s*:|\\z)", Pattern.DOTALL);
            Pattern assetPattern = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher releaseMatcher = releasePattern.matcher(json);
            while (releaseMatcher.find()) {
                String tag = unescapeJson(releaseMatcher.group(1));
                String chunk = releaseMatcher.group(2);
                List<ReleaseAsset> assets = new ArrayList<>();
                Matcher assetMatcher = assetPattern.matcher(chunk);
                while (assetMatcher.find()) {
                    String name = unescapeJson(assetMatcher.group(1));
                    String url = unescapeJson(assetMatcher.group(2));
                    int next = chunk.indexOf("\"name\"", assetMatcher.end());
                    String slice = chunk.substring(assetMatcher.start(), next == -1 ? chunk.length() : next);
                    String digest = extractDigest(slice);
                    assets.add(new ReleaseAsset(name, url, digest));
                }
                String levelZipUrl = findLevelZipAsset(assets);
                String checksumsUrl = findChecksumsAsset(assets);
                for (ReleaseAsset a : assets) {
                    if (!a.name.toLowerCase().endsWith(".jar")) continue;
                    String sidecar = findExactAsset(assets, a.name + ".sha256");
                    list.add(new RemoteJar(tag, a.name, a.url, normalizeSha256(a.digest), sidecar, checksumsUrl, levelZipUrl));
                }
            }
            return list;
        }

        private String extractDigest(String s) {
            Matcher m = Pattern.compile("\\\"digest\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE).matcher(s);
            return m.find() ? unescapeJson(m.group(1)) : null;
        }

        private String findExactAsset(List<ReleaseAsset> assets, String name) {
            for (ReleaseAsset a : assets) if (a.name.equalsIgnoreCase(name)) return a.url;
            return null;
        }

        private String findChecksumsAsset(List<ReleaseAsset> assets) {
            for (ReleaseAsset a : assets) {
                String n = a.name.toLowerCase();
                if ((n.contains("sha256") || n.contains("checksum")) && (n.endsWith(".txt") || n.endsWith(".sha256"))) return a.url;
            }
            return null;
        }

        private String findLevelZipAsset(List<ReleaseAsset> assets) {
            for (ReleaseAsset a : assets) {
                String n = a.name.toLowerCase();
                if ((n.equals("level.zip") || n.equals("levels.zip")) && n.endsWith(".zip")) return a.url;
            }
            for (ReleaseAsset a : assets) {
                String n = a.name.toLowerCase();
                if (n.contains("level") && n.endsWith(".zip")) return a.url;
            }
            return null;
        }

        private String normalizeSha256(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String v = raw.toLowerCase().trim();
            if (v.startsWith("sha256:")) v = v.substring(7);
            Matcher m = SHA256_HEX.matcher(v);
            return m.find() ? m.group(1).toLowerCase() : null;
        }

        private String unescapeJson(String v) {
            return v.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        }
        private void refreshLocalJars() {
            localJars.clear();
            try {
                if (!Files.exists(installRoot)) Files.createDirectories(installRoot);
                try (var stream = Files.walk(installRoot, 4)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                            .sorted(Comparator.comparing((Path p) -> p.toString().toLowerCase()))
                            .forEach(p -> {
                                String version = p.getParent() == null ? "-" : p.getParent().getFileName().toString();
                                localJars.add(new InstalledJar(version, p));
                            });
                }
            } catch (IOException e) {
                setStatus("Failed to read local installs: " + e.getMessage());
            }
            if (localJars.isEmpty()) selectedLocal = -1;
            else if (selectedLocal < 0 || selectedLocal >= localJars.size()) selectedLocal = 0;
            repaint();
        }

        private void downloadSelectedRemoteAsync() {
            if (selectedRemote < 0 || selectedRemote >= remoteJars.size()) {
                setStatus("Select a remote release first.");
                return;
            }
            RemoteJar remote = remoteJars.get(selectedRemote);
            setBusy(true);
            setStatus("Installing " + remote.fileName + "...");
            new SwingWorker<InstalledJar, Void>() {
                @Override protected InstalledJar doInBackground() throws Exception { return installRelease(remote); }
                @Override protected void done() {
                    try {
                        InstalledJar installed = get();
                        refreshLocalJars();
                        selectedLocal = findInstalledIndex(installed.jarPath);
                        setStatus("Install complete: " + installed.jarPath.getFileName());
                    } catch (Exception e) {
                        setStatus("Install failed: " + e.getMessage());
                    } finally {
                        setBusy(false);
                    }
                }
            }.execute();
        }

        private int findInstalledIndex(Path jarPath) {
            for (int i = 0; i < localJars.size(); i++) if (localJars.get(i).jarPath.equals(jarPath)) return i;
            return -1;
        }

        private InstalledJar installRelease(RemoteJar remote) throws Exception {
            Path versionDir = installRoot.resolve(sanitizeForDir(remote.tag));
            Files.createDirectories(versionDir);
            Path jarTarget = versionDir.resolve(remote.fileName);

            setStatusFromWorker("Step 1/4: Downloading JAR...");
            String expectedSha = resolveExpectedSha256(remote);
            if (expectedSha == null) throw new IOException("Could not resolve SHA256 from GitHub for " + remote.fileName);

            boolean verified = false;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Path tmpJar = versionDir.resolve(remote.fileName + ".part");
                downloadToFile(remote.url, tmpJar);
                String actualSha = computeFileSha256(tmpJar);
                if (expectedSha.equalsIgnoreCase(actualSha)) {
                    Files.move(tmpJar, jarTarget, StandardCopyOption.REPLACE_EXISTING);
                    verified = true;
                    break;
                }
                Files.deleteIfExists(tmpJar);
                Files.deleteIfExists(jarTarget);
                if (attempt < 3) setStatusFromWorker("SHA256 mismatch, retrying download (" + attempt + "/3)...");
            }
            if (!verified) throw new IOException("SHA256 mismatch after 3 attempts. Install aborted.");

            setStatusFromWorker("Step 2/4: Downloading level.zip...");
            Path levelZip = versionDir.resolve("level.zip");
            downloadLevelZip(remote, levelZip);

            setStatusFromWorker("Step 3/4: Extracting levels...");
            replaceLevelsDirectoryFromZip(levelZip, versionDir);
            Files.deleteIfExists(levelZip);

            setStatusFromWorker("Step 4/4: Downloading background.png...");
            downloadToFile(buildBackgroundUrl(remote.tag), versionDir.resolve("background.png"));

            return new InstalledJar(remote.tag, jarTarget);
        }

        private String resolveExpectedSha256(RemoteJar remote) {
            String direct = normalizeSha256(remote.digestSha256);
            if (direct != null) return direct;
            if (remote.sidecarShaUrl != null) {
                try {
                    String sha = extractShaFromText(httpGetText(remote.sidecarShaUrl), remote.fileName);
                    if (sha != null) return sha;
                } catch (Exception ignored) {}
            }
            if (remote.checksumsUrl != null) {
                try {
                    String sha = extractShaFromText(httpGetText(remote.checksumsUrl), remote.fileName);
                    if (sha != null) return sha;
                } catch (Exception ignored) {}
            }
            try {
                String sha = extractShaFromText(httpGetText(remote.url + ".sha256"), remote.fileName);
                if (sha != null) return sha;
            } catch (Exception ignored) {}
            return null;
        }

        private String extractShaFromText(String text, String jarFileName) {
            if (text == null || text.isBlank()) return null;
            try (BufferedReader r = new BufferedReader(new StringReader(text))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (jarFileName != null && !jarFileName.isBlank() && !line.toLowerCase().contains(jarFileName.toLowerCase())) continue;
                    Matcher m = SHA256_HEX.matcher(line);
                    if (m.find()) return m.group(1).toLowerCase();
                }
            } catch (IOException ignored) {}
            Matcher first = SHA256_HEX.matcher(text);
            return first.find() ? first.group(1).toLowerCase() : null;
        }

        private String computeFileSha256(Path path) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private String buildBackgroundUrl(String tag) {
            return "https://raw.githubusercontent.com/ripipod/xplataformer/" + tag.replace(" ", "%20") + "/background.png";
        }

        private void downloadLevelZip(RemoteJar remote, Path target) throws IOException {
            List<String> candidates = new ArrayList<>();
            candidates.add(SOURCE_LEVELS_ZIP_URL);
            candidates.add("https://github.com/ripipod/XPlataformer/raw/main/levels.zip");
            candidates.add("https://github.com/ripipod/XPlataformer/blob/main/levels.zip?raw=1");

            IOException last = null;
            for (String url : candidates) {
                try {
                    downloadToFile(url, target);
                    return;
                } catch (IOException e) {
                    last = e;
                }
            }
            throw new IOException("Could not download level zip for release " + remote.tag + (last == null ? "" : " (" + last.getMessage() + ")"));
        }

        private String sanitizeForDir(String value) {
            String s = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            return s.isEmpty() ? "unknown-version" : s;
        }

        private void downloadToFile(String url, Path target) throws IOException {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
        }

        private String httpGetText(String url) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json, text/plain, */*");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) throw new IOException("No response body from " + url + " (HTTP " + code + ")");
            String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for " + url);
            return body;
        }

        private void extractZip(Path zipFile, Path targetDir) throws IOException {
            Files.createDirectories(targetDir);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    while (name.startsWith("./")) name = name.substring(2);
                    while (name.startsWith("/")) name = name.substring(1);
                    if (name.isBlank()) {
                        zis.closeEntry();
                        continue;
                    }
                    Path out = targetDir.resolve(name).normalize();
                    if (!out.startsWith(targetDir.normalize())) throw new IOException("Blocked zip-slip entry: " + entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                            zis.transferTo(os);
                        }
                    }
                    zis.closeEntry();
                }
            }
        }

        private void replaceLevelsDirectoryFromZip(Path zipFile, Path versionDir) throws IOException {
            Path tmpExtract = versionDir.resolve("__levels_extract_tmp__");
            deleteRecursively(tmpExtract);
            extractZip(zipFile, tmpExtract);

            Path extractedRoot = tmpExtract;
            Path levelsCandidate = tmpExtract.resolve("levels");
            Path levelCandidate = tmpExtract.resolve("level");
            if (Files.isDirectory(levelsCandidate)) {
                extractedRoot = levelsCandidate;
            } else if (Files.isDirectory(levelCandidate)) {
                extractedRoot = levelCandidate;
            } else {
                List<Path> dirs = new ArrayList<>();
                try (var s = Files.list(tmpExtract)) {
                    s.filter(Files::isDirectory).forEach(dirs::add);
                }
                if (dirs.size() == 1) extractedRoot = dirs.get(0);
            }

            Path targetLevels = versionDir.resolve("levels");
            deleteRecursively(targetLevels);
            Files.move(extractedRoot, targetLevels, StandardCopyOption.REPLACE_EXISTING);

            if (!extractedRoot.equals(tmpExtract)) {
                deleteRecursively(tmpExtract);
            }
        }

        private void deleteRecursively(Path dir) throws IOException {
            if (!Files.exists(dir)) return;
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }

        private void launchSelectedLocal() {
            if (selectedLocal < 0 || selectedLocal >= localJars.size()) {
                setStatus("Select a downloaded jar first.");
                return;
            }
            InstalledJar selected = localJars.get(selectedLocal);
            Path jar = selected.jarPath;
            try {
                String cmdLine = "start \"\" cmd /k java -jar \"" + jar.getFileName() + "\"";
                new ProcessBuilder("cmd", "/c", cmdLine).directory(jar.getParent().toFile()).start();
                setStatus("Opened CMD and launched: " + jar.getFileName());
            } catch (IOException e) {
                setStatus("Failed to launch jar: " + e.getMessage());
            }
        }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (backgroundImage != null) g2.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), null);

            float sx = getWidth() / (float) W;
            float sy = getHeight() / (float) H;
            float scale = Math.min(sx, sy);
            if (scale <= 0f) scale = 1f;
            renderScale = scale;
            renderOffsetX = (int) ((getWidth() - W * scale) / 2f);
            renderOffsetY = (int) ((getHeight() - H * scale) / 2f);

            g2.translate(renderOffsetX, renderOffsetY);
            g2.scale(scale, scale);
            paintLauncherUI(g2);
        }

        private void paintLauncherUI(Graphics2D g2) {
            GradientPaint bg = new GradientPaint(0, 0, new Color(35, 30, 45), 0, H, new Color(5, 5, 10));
            g2.setPaint(bg);
            g2.fillRect(0, 0, W, H);

            g2.setColor(new Color(0, 0, 0, 120));
            g2.setStroke(new BasicStroke(24));
            g2.drawRect(12, 12, W - 24, H - 24);

            String title = "XPlataformer Launcher";
            g2.setFont(new Font("Serif", Font.BOLD, 48));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (W - fm.stringWidth(title)) / 2;
            g2.setColor(new Color(0, 0, 0, 180));
            g2.drawString(title, tx + 3, 68);
            g2.setPaint(new GradientPaint(tx, 28, new Color(255, 240, 200), tx, 84, new Color(210, 170, 90)));
            g2.drawString(title, tx, 65);

            drawSkeuoButton(g2, btnRefresh, "Refresh Releases", btnRefresh.contains(mouseX, mouseY), busy);
            drawSkeuoButton(g2, btnDownload, "Install Selected", btnDownload.contains(mouseX, mouseY), busy || selectedRemote < 0);
            drawSkeuoButton(g2, btnLaunch, "Launch Selected", btnLaunch.contains(mouseX, mouseY), busy || selectedLocal < 0);

            drawListPanel(g2, remoteBox, "Remote release JARs", remoteJars, selectedRemote, true);
            drawListPanel(g2, localBox, "Installed local JARs", localJars, selectedLocal, false);
            drawStatus(g2);
        }

        private void drawListPanel(Graphics2D g2, Rectangle r, String title, List<?> entries, int selected, boolean remote) {
            Shape panel = new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 18, 18);
            g2.setColor(new Color(0, 0, 0, 140));
            g2.fill(new RoundRectangle2D.Float(r.x + 3, r.y + 4, r.width, r.height, 18, 18));
            g2.setPaint(new GradientPaint(r.x, r.y, new Color(70, 75, 90, 230), r.x, r.y + r.height, new Color(35, 40, 50, 240)));
            g2.fill(panel);
            g2.setColor(new Color(15, 15, 20, 240));
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(panel);

            g2.setFont(new Font("SansSerif", Font.BOLD, 18));
            g2.setColor(new Color(230, 235, 245));
            g2.drawString(title, r.x + 14, r.y + 28);

            int top = r.y + 40;
            int rowH = 24;
            int max = Math.min(entries.size(), (r.height - 52) / rowH);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
            for (int i = 0; i < max; i++) {
                int y = top + i * rowH;
                if (selected == i) {
                    g2.setPaint(new GradientPaint(r.x + 10, y - 14, new Color(255, 232, 150, 210), r.x + 10, y + 6, new Color(210, 170, 70, 210)));
                    g2.fill(new RoundRectangle2D.Float(r.x + 10, y - 14, r.width - 20, 20, 10, 10));
                }
                String text;
                if (remote) {
                    RemoteJar item = (RemoteJar) entries.get(i);
                    text = item.tag + " -> " + item.fileName;
                } else {
                    InstalledJar item = (InstalledJar) entries.get(i);
                    text = item.version + " -> " + item.jarPath.getFileName();
                }
                g2.setColor(new Color(20, 20, 24, 210));
                g2.drawString(trimText(g2, text, r.width - 40), r.x + 18, y);
            }

            if (entries.isEmpty()) {
                g2.setColor(new Color(210, 215, 230, 180));
                g2.drawString(remote ? "No remote JARs loaded." : "No installed JARs yet.", r.x + 18, r.y + 64);
            }
        }

        private String trimText(Graphics2D g2, String text, int maxWidth) {
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(text) <= maxWidth) return text;
            String ellipsis = "...";
            int limit = maxWidth - fm.stringWidth(ellipsis);
            if (limit <= 0) return ellipsis;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (fm.stringWidth(sb.toString() + c) > limit) break;
                sb.append(c);
            }
            return sb.append(ellipsis).toString();
        }

        private void drawStatus(Graphics2D g2) {
            String text = busy ? "[Busy] " + status : status;
            g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
            FontMetrics fm = g2.getFontMetrics();
            int hw = Math.min(W - 80, Math.max(300, fm.stringWidth(text) + 40));
            int hh = fm.getHeight() + 16;
            int hx = (W - hw) / 2;
            int hy = H - 48;

            Shape plate = new RoundRectangle2D.Float(hx, hy, hw, hh, 14, 14);
            g2.setColor(new Color(0, 0, 0, 160));
            g2.fill(new RoundRectangle2D.Float(hx + 2, hy + 3, hw, hh, 14, 14));
            g2.setPaint(new GradientPaint(hx, hy, new Color(90, 95, 105), hx, hy + hh, new Color(45, 50, 60)));
            g2.fill(plate);
            g2.setColor(new Color(20, 20, 25));
            g2.draw(plate);
            g2.setColor(new Color(255, 255, 255, 220));
            g2.drawString(trimText(g2, text, hw - 24), hx + 12, hy + ((hh - fm.getHeight()) / 2) + fm.getAscent());
        }

        private void drawSkeuoButton(Graphics2D g2, Rectangle bounds, String text, boolean hover, boolean disabled) {
            int x = bounds.x, y = bounds.y, w = bounds.width, h = bounds.height;
            Shape shape = new RoundRectangle2D.Float(x, y, w, h, 22, 22);
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fill(new RoundRectangle2D.Float(x + 3, y + 5, w, h, 22, 22));

            Color top = hover ? new Color(255, 232, 150) : new Color(210, 210, 220);
            Color bottom = hover ? new Color(210, 170, 70) : new Color(120, 130, 150);
            if (disabled) {
                top = new Color(150, 150, 155);
                bottom = new Color(95, 100, 110);
            }
            g2.setPaint(new GradientPaint(x, y, top, x, y + h, bottom));
            g2.fill(shape);
            Shape inner = new RoundRectangle2D.Float(x + 3, y + 3, w - 6, h / 2f, 18, 18);
            g2.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 180), x, (float) (y + h * 0.6), new Color(255, 255, 255, 0)));
            g2.fill(inner);
            g2.setColor(new Color(60, 60, 70));
            g2.setStroke(new BasicStroke(2.0f));
            g2.draw(shape);

            g2.setFont(new Font("SansSerif", Font.BOLD, 24));
            FontMetrics fm = g2.getFontMetrics();
            int tx = x + (w - fm.stringWidth(text)) / 2;
            int ty = y + ((h - fm.getHeight()) / 2) + fm.getAscent();
            g2.setColor(new Color(0, 0, 0, 150));
            g2.drawString(text, tx + 2, ty + 2);
            g2.setColor(disabled ? new Color(45, 45, 45) : new Color(40, 35, 25));
            g2.drawString(text, tx, ty);
        }

        private int toLogicalX(int px) { return (int) ((px - renderOffsetX) / (renderScale <= 0f ? 1f : renderScale)); }
        private int toLogicalY(int py) { return (int) ((py - renderOffsetY) / (renderScale <= 0f ? 1f : renderScale)); }

        @Override public void mouseClicked(MouseEvent e) {}

        @Override
        public void mousePressed(MouseEvent e) {
            int lx = toLogicalX(e.getX());
            int ly = toLogicalY(e.getY());
            if (busy) return;

            if (btnRefresh.contains(lx, ly)) { fetchReleasesAsync(); return; }
            if (btnDownload.contains(lx, ly)) { downloadSelectedRemoteAsync(); return; }
            if (btnLaunch.contains(lx, ly)) { launchSelectedLocal(); return; }

            int remoteIndex = resolveListIndex(remoteBox, lx, ly, remoteJars.size());
            if (remoteIndex >= 0) { selectedRemote = remoteIndex; repaint(); return; }

            int localIndex = resolveListIndex(localBox, lx, ly, localJars.size());
            if (localIndex >= 0) { selectedLocal = localIndex; repaint(); }
        }

        private int resolveListIndex(Rectangle box, int lx, int ly, int count) {
            if (!box.contains(lx, ly)) return -1;
            int top = box.y + 40;
            int rowH = 24;
            if (ly < top - 14) return -1;
            int idx = (ly - (top - 14)) / rowH;
            int max = Math.min(count, (box.height - 52) / rowH);
            return idx >= 0 && idx < max ? idx : -1;
        }

        @Override public void mouseReleased(MouseEvent e) {}
        @Override public void mouseEntered(MouseEvent e) {}
        @Override public void mouseExited(MouseEvent e) {}
        @Override public void mouseDragged(MouseEvent e) {}

        @Override
        public void mouseMoved(MouseEvent e) {
            mouseX = toLogicalX(e.getX());
            mouseY = toLogicalY(e.getY());
            repaint();
        }
    }
    static final class ReleaseAsset {
        final String name;
        final String url;
        final String digest;

        ReleaseAsset(String name, String url, String digest) {
            this.name = name;
            this.url = url;
            this.digest = digest;
        }
    }

    static final class RemoteJar {
        final String tag;
        final String fileName;
        final String url;
        final String digestSha256;
        final String sidecarShaUrl;
        final String checksumsUrl;
        final String levelZipUrl;

        RemoteJar(String tag, String fileName, String url, String digestSha256, String sidecarShaUrl, String checksumsUrl, String levelZipUrl) {
            this.tag = tag;
            this.fileName = fileName;
            this.url = url;
            this.digestSha256 = digestSha256;
            this.sidecarShaUrl = sidecarShaUrl;
            this.checksumsUrl = checksumsUrl;
            this.levelZipUrl = levelZipUrl;
        }
    }

    static final class InstalledJar {
        final String version;
        final Path jarPath;

        InstalledJar(String version, Path jarPath) {
            this.version = version;
            this.jarPath = jarPath;
        }
    }
}
