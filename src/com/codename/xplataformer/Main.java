package src.com.codename.xplataformer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Main {
    public static void main(String[] args) {
        JFrame frame = new JFrame("XPlataformer RC1");
            // try to load an `icon.png` from the project root and use it as the window icon
            try{
                java.awt.Image icon = ImageIO.read(new File("icon.png"));
                if(icon != null) frame.setIconImage(icon);
            }catch(Exception ignored){}
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // use a 16:9 logical resolution (approx 1067x600)
        GamePanel panel = new GamePanel(1067, 600);
        frame.setContentPane(panel);
        frame.pack();
        // minimum window size also roughly 16:9
        frame.setMinimumSize(new Dimension(854, 480));
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);
        panel.start();
    }

    static class GamePanel extends JPanel implements Runnable, KeyListener, MouseListener, MouseMotionListener {
        final int W, H;
        Thread thread;
        Rectangle player;
        // precise positions and velocities
        float posX, posY;
        float vx=0, vy=0;
        boolean left, right, jump;
        boolean onGround = false;
        
        // camera/scroll
        float cameraX = 0;
        float levelWidth = 1067;

        ArrayList<Rectangle> platforms = new ArrayList<>();
        ArrayList<Polygon> killers = new ArrayList<>();
        ArrayList<MovingPlatform> movingPlatforms = new ArrayList<>();
        // extra mechanics
        ArrayList<Rectangle> invisiblePlatforms = new ArrayList<>();
        ArrayList<DraggablePlatform> dragPlatforms = new ArrayList<>();
        ArrayList<Boss> bosses = new ArrayList<>();
        Rectangle goal;
        
        // game state
        enum GameState { MENU, PLAYING, DEMO }
        GameState gameState = GameState.MENU;
        
        // demo (IA)
        AIPlayer aiPlayer;
        Level demoLevel;
        
        // menu - botones
        Rectangle btnPlay = new Rectangle(150, 200, 500, 60);
        Rectangle btnCustom = new Rectangle(100, 290, 600, 60);
        int selectedMenu = 0; // 0=Play, 1=Custom Pack
        
        // mouse (stored in logical coordinates, not raw pixels)
        int mouseX = 0, mouseY = 0;
        // current render scale and offset (for resizing)
        float renderScale = 1f;
        int renderOffsetX = 0, renderOffsetY = 0;
        // optional full-window background image
        Image backgroundImage = null;
        
        // debug mode
        boolean debugMode = false;
        int debugModeTimer = 0;
        boolean iKeyPressed = false;
        // level management
        List<Level> levels = new ArrayList<>();
        int currentLevel = 0;
        int attemptCount = 1;
        // debug: print a few frames after loading a level to inspect physics
        int debugFrames = 0;
        // in-game message when level completes
        boolean showingMessage = false;
        int messageTimer = 0;
        String messageText = "";
        int pendingNextLevel = -1;
        // custom package support
        int customLoadedCount = 0;
        // dragging state for cursor-movable platforms
        DraggablePlatform activeDrag = null;
        int dragOffsetX = 0, dragOffsetY = 0;
        // cached indices of levels that use mouse-based mechanics (drag platforms, etc.)
        ArrayList<Integer> mouseLevels = new ArrayList<>();

        public GamePanel(int w, int h){
            W = w; H = h;
            setPreferredSize(new Dimension(W,H));
            setFocusable(true);
            addKeyListener(this);
            addMouseListener(this);
            addMouseMotionListener(this);

            // try to load a background image from project root
            try{
                File bgFile = new File("background.png");
                if(bgFile.exists()){
                    backgroundImage = ImageIO.read(bgFile);
                }
            }catch(IOException ignored){}

            loadLevels();
            
            // Load demo level (level 1-5)
            if(levels.size() >= 5){
                demoLevel = levels.get(4); // index 4 = level 1-5
            } else if(!levels.isEmpty()){
                demoLevel = levels.get(0);
            }
            
            gameState = GameState.MENU;
        }

        // load a .xlevel (zip) package into RAM (temporary levels appended)
        void loadXLevel(File pkgFile){
            if(pkgFile==null || !pkgFile.exists()) return;
            int added = 0;
            try(ZipFile zf = new ZipFile(pkgFile)){
                Enumeration<? extends ZipEntry> en = zf.entries();
                while(en.hasMoreElements()){
                    ZipEntry e = en.nextElement();
                    String name = e.getName().toLowerCase();
                    if(name.endsWith(".txt") || name.endsWith(".lvl")){
                        try(InputStream in = zf.getInputStream(e)){
                            Level L = Level.loadFromStream(in);
                            if(L!=null){ levels.add(L); added++; }
                        }catch(Exception ex){ System.err.println("Failed to read entry " + name + ": " + ex); }
                    }
                }
            }catch(Exception ex){
                final String em = "Failed to open package: " + ex.getMessage();
                SwingUtilities.invokeLater(() -> { showingMessage=true; messageText=em; messageTimer=100; });
                return;
            }
            customLoadedCount += added;
            final int got = added;
            recomputeMouseLevels();
            SwingUtilities.invokeLater(() -> { showingMessage=true; messageText = "Loaded " + got + " custom level(s) from " + pkgFile.getName(); messageTimer=120; });
        }
        void loadLevels(){
            // try to load level files from JAR resources first (for packaged games)
            try{
                java.net.URL resourceURL = GamePanel.class.getResource("/levels/");
                if(resourceURL != null){
                    File resourceDir = new File(resourceURL.toURI());
                    File[] files = resourceDir.listFiles((d,name)->name.endsWith(".txt")||name.endsWith(".lvl"));
                    if(files != null && files.length > 0){
                        Arrays.sort(files, Comparator.comparing(File::getName));
                        for(File f : files){
                            try{ levels.add(Level.loadFromFile(f)); }catch(Exception e){ System.err.println("Failed to load " + f + ": " + e); }
                        }
                        if(!levels.isEmpty()) return; // success!
                    }
                }
            }catch(Exception e){ System.err.println("ClassLoader resource loading failed: " + e); }
            
            // fallback: try to load level files from levels/ folder (for development)
            String base = "levels" + File.separator;
            File dir = new File(base);
            if(!dir.exists() || !dir.isDirectory()){
                // create default levels folder and files
                dir.mkdirs();
                createDefaultLevels(base);
            }
            File[] files = dir.listFiles((d,name)->name.endsWith(".txt")||name.endsWith(".lvl"));
            if(files==null || files.length==0){
                createDefaultLevels(base);
                files = dir.listFiles((d,name)->name.endsWith(".txt")||name.endsWith(".lvl"));
            }
            if(files!=null){
                Arrays.sort(files, Comparator.comparing(File::getName));
                for(File f : files){
                    try{ levels.add(Level.loadFromFile(f)); }catch(Exception e){ System.err.println("Failed to load " + f + ": " + e); }
                }
            }
            if(levels.isEmpty()){
                levels.add(Level.defaultLevel1());
                levels.add(Level.defaultLevel2());
            }
            recomputeMouseLevels();
        }

        void createDefaultLevels(String base){
            try{
                // generate 39 levels across worlds, saved as text files
                for(int i = 1; i <= 39; i++){
                    File lf = new File(base + String.format("level%02d.txt", i));
                    try(PrintWriter w = new PrintWriter(lf)){
                        int world = ((i - 1) / 10) + 1; // 1-based
                        int indexInWorld = ((i - 1) % 10) + 1;
                        boolean hard = indexInWorld == 10;
                        String name = "World " + world + " - " + (hard ? "Hard " + (world) : ("Level " + indexInWorld));
                        w.println("name: " + name);

                        // basic start
                        w.println("start: 50 400");

                        // ground platform
                        w.println("platform: 0 550 1200 50");

                        int offsetX = 120 * (indexInWorld - 1);
                        // regular platforms pattern
                        int step = hard ? 70 : 90;
                        int count = hard ? 12 : 6;
                        for(int p = 0; p < count; p++){
                            int px = 120 + offsetX + p * step;
                            int py = 520 - (p % 4) * 60;
                            int pw = 80 + (p % 3) * 20;
                            int ph = 20;
                            w.println("platform: " + px + " " + py + " " + pw + " " + ph);
                        }

                        // add some killers
                        int traps = hard ? 6 : 3;
                        for(int t = 0; t < traps; t++){
                            int tx = 260 + offsetX + t * 140;
                            int ty = 550;
                            w.println("killer: " + tx + " " + ty + " " + (tx+40) + " " + ty + " " + (tx+20) + " " + (ty-30));
                        }

                        // introduce cursor-drag platforms in later worlds / levels
                        if(world >= 2){
                            int dx = 300 + offsetX;
                            int dy = 260;
                            w.println("dragplatform: " + dx + " " + dy + " 120 20");
                            if(hard){
                                w.println("dragplatform: " + (dx+220) + " " + (dy-80) + " 120 20");
                            }
                        }

                        // invisible platforms for some trick jumps
                        if(indexInWorld >= 5){
                            int ix = 500 + offsetX;
                            int iy = 320;
                            w.println("invisibleplatform: " + ix + " " + iy + " 100 15");
                        }

                        // simple boss in each hard level
                        if(hard){
                            int bx = 800 + offsetX;
                            int by = 350;
                            w.println("boss: " + bx + " " + by + " 80 80 5");
                        }

                        // goal near the far right
                        int gx = 950 + offsetX;
                        int gy = 260;
                        w.println("goal: " + gx + " " + gy + " 40 40");
                    }
                }
            }catch(IOException ignored){}
        }

        void loadLevel(int idx){
            if(idx < 0 || idx >= levels.size()) return;
            currentLevel = idx;
            attemptCount = 1;
            Level L = levels.get(idx);
            platforms.clear(); killers.clear(); movingPlatforms.clear();
            invisiblePlatforms.clear(); dragPlatforms.clear(); bosses.clear();
            platforms.addAll(L.platforms);
            killers.addAll(L.killers);
            movingPlatforms.addAll(L.movingPlatforms);
            invisiblePlatforms.addAll(L.invisiblePlatforms);
            dragPlatforms.addAll(L.dragPlatforms);
            bosses.addAll(L.bosses);
            goal = L.goal;
            player = new Rectangle(L.startX, L.startY, 32, 48);
            posX = player.x; posY = player.y;
            // reset motion state so player doesn't get moved away by leftover velocity
            vx = 0f; vy = 0f; onGround = false;
            cameraX = 0;
            
            // Calculate level width based on rightmost element (at least as wide as the viewport)
            levelWidth = W;
            for(Rectangle p : platforms) levelWidth = Math.max(levelWidth, p.x + p.width);
            for(Polygon k : killers){
                int[] xs = k.xpoints;
                for(int x : xs) levelWidth = Math.max(levelWidth, x);
            }
            for(MovingPlatform mp : movingPlatforms) levelWidth = Math.max(levelWidth, (int)mp.maxX + mp.width);
            if(goal != null) levelWidth = Math.max(levelWidth, goal.x + goal.width);
            levelWidth += 50; // padding
            
            requestFocusInWindow();
            debugFrames = 120;
            System.out.println("[DEBUG] loadLevel: " + L.name + " start=" + L.startX + "," + L.startY + " goal=" + L.goal + " levelWidth=" + levelWidth);
        }

        public void start(){
            thread = new Thread(this, "game-loop");
            thread.start();
            requestFocusInWindow();
        }

        @Override
        public void run() {
            long last = System.nanoTime();
            while (true){
                long now = System.nanoTime();
                float dt = (now - last) / 1_000_000_000f;
                last = now;
                update(dt);
                repaint();
                try { Thread.sleep(16); } catch (InterruptedException ignored){}
            }
        }

        void update(float dt){
            
            if(gameState == GameState.MENU){
                // Menu no necesita actualización fija, pero la demostraciónse actualiza en paintComponent
                return;
            }
            
            if(gameState == GameState.DEMO){
                // Demo: IA está jugando
                if(aiPlayer != null){
                    aiPlayer.update(dt, this);
                }
                updateDemo(dt);
                return;
            }

            // PLAYING state
            // update debug mode timer
            if(debugModeTimer > 0) debugModeTimer--;
            else debugMode = false;

            // update moving platforms
            for(MovingPlatform mp : movingPlatforms) mp.update(dt);

            // velocities in pixels/sec
            if(left) vx = -180f;
            else if(right) vx = 180f;
            else vx = 0f;

            if(jump && onGround){ vy = -520f; onGround = false; }

            // apply gravity (pixels/sec^2)
            vy += 1200f * dt;

            // integrate
            posX += vx * dt;
            posY += vy * dt;

            if(debugFrames > 0){
                System.out.println(String.format("[DEBUG] frame debug: posY=%.2f vy=%.2f onGround=%b player.y=%d", posY, vy, onGround, player.y));
                debugFrames--;
            }

            // update player rect from float positions
            player.x = Math.max(0, (int)posX);
            if(player.x + player.width > W) player.x = W - player.width;
            posX = player.x;
            player.y = (int)posY;
            
            // update camera to follow player
            cameraX = posX - W/4f;
            if(cameraX < 0) cameraX = 0;
            if(cameraX + W > levelWidth) cameraX = levelWidth - W;

            // collision with static platforms
            onGround = false;
            for(Rectangle p : platforms){
                if(player.intersects(p)){
                    Rectangle inter = player.intersection(p);
                    if(inter.height < inter.width){
                        if(player.y < p.y){
                            // landed on top
                            player.y = p.y - player.height;
                            posY = player.y;
                            vy = 0;
                            onGround = true;
                        } else {
                            // hit from below
                            player.y = p.y + p.height;
                            posY = player.y;
                            vy = 0;
                        }
                    } else {
                        // hit from side
                        if(player.x < p.x) {
                            player.x = p.x - player.width;
                            posX = player.x;
                        } else {
                            player.x = p.x + p.width;
                            posX = player.x;
                        }
                    }
                }
            }

            // collision with invisible platforms (same as static, just not drawn)
            for(Rectangle p : invisiblePlatforms){
                if(player.intersects(p)){
                    Rectangle inter = player.intersection(p);
                    if(inter.height < inter.width){
                        if(player.y < p.y){
                            player.y = p.y - player.height;
                            posY = player.y;
                            vy = 0;
                            onGround = true;
                        } else {
                            player.y = p.y + p.height;
                            posY = player.y;
                            vy = 0;
                        }
                    } else {
                        if(player.x < p.x) {
                            player.x = p.x - player.width;
                            posX = player.x;
                        } else {
                            player.x = p.x + p.width;
                            posX = player.x;
                        }
                    }
                }
            }

            // collision with moving platforms
            for(MovingPlatform mp : movingPlatforms){
                Rectangle p = mp.getRect();
                if(player.intersects(p)){
                    Rectangle inter = player.intersection(p);
                    if(inter.height < inter.width){
                        if(player.y < p.y){
                            // landed on top
                            player.y = p.y - player.height;
                            posY = player.y;
                            vy = 0;
                            onGround = true;
                        } else {
                            // hit from below
                            player.y = p.y + p.height;
                            posY = player.y;
                            vy = 0;
                        }
                    } else {
                        // hit from side
                        if(player.x < p.x) {
                            player.x = p.x - player.width;
                            posX = player.x;
                        } else {
                            player.x = p.x + p.width;
                            posX = player.x;
                        }
                    }
                }
            }

            // collision with draggable platforms
            for(DraggablePlatform dp : dragPlatforms){
                Rectangle p = dp.rect;
                if(player.intersects(p)){
                    Rectangle inter = player.intersection(p);
                    if(inter.height < inter.width){
                        if(player.y < p.y){
                            player.y = p.y - player.height;
                            posY = player.y;
                            vy = 0;
                            onGround = true;
                        } else {
                            player.y = p.y + p.height;
                            posY = player.y;
                            vy = 0;
                        }
                    } else {
                        if(player.x < p.x) {
                            player.x = p.x - player.width;
                            posX = player.x;
                        } else {
                            player.x = p.x + p.width;
                            posX = player.x;
                        }
                    }
                }
            }

            // boss interaction
            for(Boss b : bosses){
                if(!b.alive) continue;
                Rectangle br = b.rect;
                if(player.intersects(br)){
                    Rectangle inter = player.intersection(br);
                    boolean stompFromTop = (inter.height < inter.width) && (player.y < br.y) && vy > 0;
                    if(stompFromTop){
                        // damage boss and bounce
                        b.hp -= 1;
                        if(b.hp <= 0) b.alive = false;
                        player.y = br.y - player.height;
                        posY = player.y;
                        vy = -420f;
                        onGround = false;
                    } else {
                        // player loses: respawn
                        attemptCount++;
                        Level L = levels.get(currentLevel);
                        posX = L.startX; posY = L.startY; vy = 0; vx = 0;
                        player.x = (int)posX; player.y = (int)posY;
                    }
                }
            }

            // killer triangles
            for(Polygon t : killers){
                if( containsAnyCorner(t, player) ){
                    // respawn
                    attemptCount++;
                    // respawn at level start
                    Level L = levels.get(currentLevel);
                    posX = L.startX; posY = L.startY; vy = 0; vx = 0;
                    player.x = (int)posX; player.y = (int)posY;
                }
            }

            // goal (require all bosses defeated if any exist)
            if(player.intersects(goal) && !showingMessage && allBossesDefeated()){
                // compute next level index
                int next = currentLevel + 1;
                boolean wrapped = false;
                if(next >= levels.size()){ wrapped = true; next = 0; }
                // show an in-game message for 1.4s (approx) then load next level on game loop
                showingMessage = true;
                messageTimer = 84; // ~1.35s at 16ms per frame
                messageText = "Level Complete!" + (wrapped ? " (looping to first level)" : "");
                pendingNextLevel = next;
            }

            if(showingMessage){
                messageTimer--;
                if(messageTimer <= 0){
                    showingMessage = false;
                    if(pendingNextLevel >= 0){ loadLevel(pendingNextLevel); pendingNextLevel = -1; }
                }
            }
        }

        boolean containsAnyCorner(Polygon poly, Rectangle r){
            int[] xs = new int[]{r.x, r.x + r.width, r.x, r.x + r.width};
            int[] ys = new int[]{r.y, r.y, r.y + r.height, r.y + r.height};
            for(int i=0;i<4;i++) if(poly.contains(xs[i], ys[i])) return true;
            return false;
        }

        boolean allBossesDefeated(){
            if(bosses.isEmpty()) return true;
            for(Boss b : bosses){
                if(b.alive) return false;
            }
            return true;
        }

        void recomputeMouseLevels(){
            mouseLevels.clear();
            for(int i = 0; i < levels.size(); i++){
                Level L = levels.get(i);
                if(L != null && !L.dragPlatforms.isEmpty()){
                    mouseLevels.add(i);
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;

            // enable higher-quality rendering for a more skeuomorphic look
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // clear background and draw full-window background image (not limited to 4:3)
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if(backgroundImage != null){
                g2.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), null);
            }

            // compute scale and offset so game can be resized while keeping aspect ratio
            float sx = getWidth() / (float) W;
            float sy = getHeight() / (float) H;
            float scale = Math.min(sx, sy);
            if(scale <= 0f) scale = 1f;
            int offsetX = (int) ((getWidth() - W * scale) / 2f);
            int offsetY = (int) ((getHeight() - H * scale) / 2f);

            renderScale = scale;
            renderOffsetX = offsetX;
            renderOffsetY = offsetY;

            g2.translate(offsetX, offsetY);
            g2.scale(scale, scale);

            if(gameState == GameState.MENU){
                paintMenu(g2);
            } else if(gameState == GameState.DEMO){
                paintGameWithBlur(g2, 0.5f); // 50% blur
                paintDemoUI(g2);
            } else {
                paintGame(g2);
            }
        }
        
        void paintMenu(Graphics2D g2){
            // Background: subtle vignette and radial-style gradient for a more physical feel
            GradientPaint bg = new GradientPaint(
                    0, 0, new Color(35, 30, 45),
                    0, H, new Color(5, 5, 10));
            g2.setPaint(bg);
            g2.fillRect(0,0,W,H);

            g2.setColor(new Color(0,0,0,120));
            g2.setStroke(new BasicStroke(30));
            g2.drawRect(15,15,W-30,H-30);

            // Title with soft shadow and inner highlight
            String title = "XPlataformer";
            g2.setFont(new Font("Serif", Font.BOLD, 50));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (W - fm.stringWidth(title)) / 2;

            g2.setColor(new Color(0,0,0,180));
            g2.drawString(title, tx+3, 83);
            g2.setPaint(new GradientPaint(
                    tx, 40, new Color(255, 240, 200),
                    tx, 95, new Color(210, 170, 90)));
            g2.drawString(title, tx, 80);

            // Version below title
            String version = "RC1";
            g2.setFont(new Font("Serif", Font.PLAIN, 24));
            fm = g2.getFontMetrics();
            int vx = (W - fm.stringWidth(version)) / 2;
            g2.setColor(new Color(0,0,0,150));
            g2.drawString(version, vx+2, 118);
            g2.setColor(new Color(230, 220, 210));
            g2.drawString(version, vx, 115);

            // Button 1: Play
            boolean hoverPlay = btnPlay.contains(mouseX, mouseY);
            drawSkeuoButton(g2, btnPlay, "Play", hoverPlay, selectedMenu == 0);

            // Button 2: Custom Pack
            boolean hoverCustom = btnCustom.contains(mouseX, mouseY);
            drawSkeuoButton(g2, btnCustom, "Play Custom Level Pack", hoverCustom, selectedMenu == 1);

            // Instructions on a small metal plate
            String help = "Arrow Keys / Mouse to select, ENTER / Click to confirm";
            g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
            fm = g2.getFontMetrics();
            int hw = fm.stringWidth(help) + 40;
            int hh = fm.getHeight() + 16;
            int hx = (W - hw) / 2;
            int hy = H - 60;

            Shape plate = new RoundRectangle2D.Float(hx, hy, hw, hh, 14, 14);
            g2.setColor(new Color(0,0,0,160));
            g2.fill(new RoundRectangle2D.Float(hx+2, hy+4, hw, hh, 14, 14));

            g2.setPaint(new GradientPaint(
                    hx, hy, new Color(90, 95, 105),
                    hx, hy+hh, new Color(45, 50, 60)));
            g2.fill(plate);
            g2.setColor(new Color(20,20,25));
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(plate);

            g2.setColor(new Color(255,255,255,220));
            g2.drawString(help, hx + (hw - fm.stringWidth(help)) / 2, hy + ((hh - fm.getHeight()) / 2) + fm.getAscent());
        }
        
        void paintGameWithBlur(Graphics2D g2, float blurAmount){
            // Renderizar el juego normal
            paintGame(g2);
            
            // Aplicar layer de blur semi-transparent
            g2.setColor(new Color(0, 0, 0, (int)(blurAmount * 100)));
            g2.fillRect(0, 0, W, H);
        }
        
        void paintGame(Graphics2D g2){
            // background: overlay over the full-window background image
            GradientPaint sky = new GradientPaint(
                    0, 0, new Color(35, 60, 120, 170),
                    0, H, new Color(10, 10, 25, 230));
            g2.setPaint(sky);
            g2.fillRect(0,0,W,H);

            // subtle ground horizon
            g2.setPaint(new GradientPaint(
                    0, H-120, new Color(40, 30, 25, 190),
                    0, H, new Color(10, 5, 0, 255)));
            g2.fillRect(0, H-140, W, 160);

            // Apply camera transform
            g2.translate(-cameraX, 0);
            
            // platforms (fixed)
            for(Rectangle p : platforms) drawSkeuoPlatform(g2, p, false);

            // moving platforms
            for(MovingPlatform mp : movingPlatforms) drawSkeuoPlatform(g2, mp.getRect(), true);

            // cursor-movable platforms
            for(DraggablePlatform dp : dragPlatforms) drawSkeuoDragPlatform(g2, dp.rect);

            // killers
            for(Polygon t : killers) drawSkeuoKiller(g2, t);

            // bosses
            for(Boss b : bosses) if(b.alive) drawBoss(g2, b);

            // goal
            drawSkeuoGoal(g2, goal);

            // player
            drawSkeuoPlayer(g2, player);
            
            // Reset camera transform
            g2.translate(cameraX, 0);

            // HUD (no camera transform)
            int hudPad = 10;
            int hudWidth = 360;
            int hudHeight = 70;
            Shape hudPanel = new RoundRectangle2D.Float(hudPad, hudPad, hudWidth, hudHeight, 18, 18);

            g2.setColor(new Color(0,0,0,140));
            g2.fill(new RoundRectangle2D.Float(hudPad+2, hudPad+3, hudWidth, hudHeight, 18, 18));

            g2.setPaint(new GradientPaint(
                    hudPad, hudPad, new Color(70, 75, 90, 230),
                    hudPad, hudPad+hudHeight, new Color(35, 40, 50, 240)));
            g2.fill(hudPanel);
            g2.setColor(new Color(15,15,20,240));
            g2.setStroke(new BasicStroke(1.8f));
            g2.draw(hudPanel);

            g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g2.setColor(new Color(230, 235, 245));
            // controls
            g2.drawString("Controls: Arrow/A/D to move, W/Up/Space to jump", hudPad + 12, hudPad + 22);
            // level & attempt
            String lvlName = levels.size() > 0 ? levels.get(currentLevel).name : "-";
            g2.drawString("Level: " + lvlName, hudPad + 12, hudPad + 40);
            g2.drawString("Attempt: " + attemptCount, hudPad + 12, hudPad + 58);
            // show message if set
            if(showingMessage){
                g2.setFont(g2.getFont().deriveFont(Font.BOLD, 18f));
                FontMetrics fm = g2.getFontMetrics();
                int mw = fm.stringWidth(messageText);
                int mx = (W - mw) / 2;
                int my = 110;
                Shape bubble = new RoundRectangle2D.Float(mx-14, my-26, mw+28, 36, 12, 12);
                g2.setColor(new Color(0,0,0,180));
                g2.fill(new RoundRectangle2D.Float(mx-12, my-22, mw+28, 36, 12, 12));
                g2.setPaint(new GradientPaint(
                        mx, my-26, new Color(250, 245, 230),
                        mx, my+10, new Color(230, 215, 190)));
                g2.fill(bubble);
                g2.setColor(new Color(90, 70, 40));
                g2.setStroke(new BasicStroke(1.4f));
                g2.draw(bubble);
                g2.setColor(new Color(40, 30, 20));
                g2.drawString(messageText, mx, my-4);
            }
            
            // debug mode display
            if(debugMode){
                int baseY = H - 70;
                g2.setFont(g2.getFont().deriveFont(Font.BOLD, 18f));
                g2.setColor(Color.YELLOW);
                g2.drawString("DEBUG MODE: Press a number (1-9) to jump to level", 10, baseY);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
                g2.setColor(new Color(230, 230, 180));
                g2.drawString("Total levels: " + levels.size(), 10, baseY + 18);

                if(!mouseLevels.isEmpty()){
                    StringBuilder sb = new StringBuilder("Mouse levels (drag platforms): ");
                    for(int i = 0; i < mouseLevels.size(); i++){
                        int idx = mouseLevels.get(i);
                        String name = (idx >= 0 && idx < levels.size()) ? levels.get(idx).name : "?";
                        if(i > 0) sb.append(" | ");
                        sb.append(idx + 1).append(": ").append(name);
                    }
                    g2.drawString(sb.toString(), 10, baseY + 36);
                }
            }
        }

        private void drawSkeuoButton(Graphics2D g2, Rectangle bounds, String text, boolean hover, boolean selected){
            int x = bounds.x;
            int y = bounds.y;
            int w = bounds.width;
            int h = bounds.height;

            Shape shape = new RoundRectangle2D.Float(x, y, w, h, 22, 22);

            // drop shadow
            g2.setColor(new Color(0,0,0,170));
            g2.fill(new RoundRectangle2D.Float(x+3, y+5, w, h, 22, 22));

            // base material
            Color top = selected || hover ? new Color(255, 232, 150) : new Color(210, 210, 220);
            Color bottom = selected || hover ? new Color(210, 170, 70) : new Color(120, 130, 150);
            g2.setPaint(new GradientPaint(x, y, top, x, y+h, bottom));
            g2.fill(shape);

            // inner highlight
            Shape inner = new RoundRectangle2D.Float(x+3, y+3, w-6, h/2f, 18, 18);
            g2.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 180),
                    x, (float)(y+h*0.6), new Color(255, 255, 255, 0)));
            g2.fill(inner);

            // border
            g2.setColor(new Color(60, 60, 70));
            g2.setStroke(new BasicStroke(2.0f));
            g2.draw(shape);

            // text with subtle emboss
            g2.setFont(new Font("SansSerif", Font.BOLD, 28));
            FontMetrics fm = g2.getFontMetrics();
            int tx = x + (w - fm.stringWidth(text)) / 2;
            int ty = y + ((h - fm.getHeight()) / 2) + fm.getAscent();

            g2.setColor(new Color(0,0,0,150));
            g2.drawString(text, tx+2, ty+2);
            g2.setColor(new Color(40, 35, 25));
            g2.drawString(text, tx, ty);
        }

        private void drawSkeuoPlatform(Graphics2D g2, Rectangle p, boolean moving){
            int x = p.x;
            int y = p.y;
            int w = p.width;
            int h = p.height;

            Color top = moving ? new Color(180, 210, 240) : new Color(140, 130, 120);
            Color bottom = moving ? new Color(70, 100, 135) : new Color(60, 45, 35);

            g2.setPaint(new GradientPaint(x, y, top, x, y+h, bottom));
            g2.fillRect(x, y, w, h);

            // beveled edges
            g2.setColor(new Color(255, 255, 255, 140));
            g2.drawLine(x, y, x + w, y);
            g2.drawLine(x, y, x, y + h);

            g2.setColor(new Color(0, 0, 0, 160));
            g2.drawLine(x, y + h - 1, x + w, y + h - 1);
            g2.drawLine(x + w - 1, y, x + w - 1, y + h);

            // subtle bolts for flavor
            int boltSize = Math.max(2, h / 5);
            g2.setColor(new Color(30, 30, 35, 200));
            g2.fillOval(x + 4, y + 3, boltSize, boltSize);
            g2.fillOval(x + w - boltSize - 6, y + 3, boltSize, boltSize);
        }

        private void drawSkeuoDragPlatform(Graphics2D g2, Rectangle p){
            int x = p.x;
            int y = p.y;
            int w = p.width;
            int h = p.height;

            // brighter, slightly translucent material to hint interactivity
            g2.setPaint(new GradientPaint(
                    x, y, new Color(220, 235, 255, 220),
                    x, y+h, new Color(130, 165, 215, 230)));
            g2.fillRect(x, y, w, h);

            g2.setColor(new Color(255, 255, 255, 180));
            g2.drawLine(x, y, x + w, y);
            g2.drawLine(x, y, x, y + h);

            g2.setColor(new Color(0, 0, 40, 200));
            g2.drawLine(x, y + h - 1, x + w, y + h - 1);
            g2.drawLine(x + w - 1, y, x + w - 1, y + h);

            // grip lines
            g2.setColor(new Color(0, 0, 60, 120));
            for(int gx = x + 6; gx < x + w - 4; gx += 6){
                g2.drawLine(gx, y + 4, gx, y + h - 4);
            }
        }

        private void drawSkeuoKiller(Graphics2D g2, Polygon t){
            Rectangle b = t.getBounds();
            float y1 = b.y;
            float y2 = b.y + b.height;

            // metalic red spike
            LinearGradientPaint lg = new LinearGradientPaint(
                    new Point2D.Float(b.x, y1),
                    new Point2D.Float(b.x, y2),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(230, 120, 120),
                            new Color(160, 30, 30),
                            new Color(70, 0, 0)});
            g2.setPaint(lg);
            g2.fillPolygon(t);

            // edge highlight
            g2.setColor(new Color(255, 230, 220, 180));
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawPolygon(t);
        }

        private void drawSkeuoGoal(Graphics2D g2, Rectangle goal){
            int x = goal.x;
            int y = goal.y;
            int w = goal.width;
            int h = goal.height;

            Shape body = new RoundRectangle2D.Float(x, y, w, h, 14, 14);

            // glow behind goal
            RadialGradientPaint glow = new RadialGradientPaint(
                    new Point2D.Float(x + w/2f, y + h/2f),
                    Math.max(w, h),
                    new float[]{0f, 1f},
                    new Color[]{new Color(255, 255, 200, 190), new Color(255, 255, 200, 0)});
            g2.setPaint(glow);
            g2.fill(new Ellipse2D.Float(x - w, y - h, w*3, h*3));

            // chest / gem body
            g2.setPaint(new GradientPaint(
                    x, y, new Color(255, 235, 170),
                    x, y+h, new Color(185, 140, 60)));
            g2.fill(body);

            g2.setColor(new Color(90, 65, 25));
            g2.setStroke(new BasicStroke(2.2f));
            g2.draw(body);

            // top highlight
            Shape shine = new RoundRectangle2D.Float(x+4, y+3, w-8, h/3f, 10, 10);
            g2.setPaint(new GradientPaint(
                    x, y, new Color(255, 255, 255, 200),
                    x, y + h/2f, new Color(255, 255, 255, 0)));
            g2.fill(shine);
        }

        private void drawBoss(Graphics2D g2, Boss b){
            Rectangle r = b.rect;
            int x = r.x;
            int y = r.y;
            int w = r.width;
            int h = r.height;

            Shape body = new RoundRectangle2D.Float(x, y, w, h, 24, 24);
            g2.setPaint(new GradientPaint(
                    x, y, new Color(200, 90, 60),
                    x, y+h, new Color(80, 0, 0)));
            g2.fill(body);

            g2.setColor(new Color(20, 0, 0));
            g2.setStroke(new BasicStroke(3.0f));
            g2.draw(body);

            // eyes
            int eyeW = Math.max(6, w / 8);
            int eyeH = Math.max(6, h / 6);
            g2.setColor(new Color(255, 240, 220));
            g2.fillOval(x + w/4 - eyeW/2, y + h/3 - eyeH/2, eyeW, eyeH);
            g2.fillOval(x + 3*w/4 - eyeW/2, y + h/3 - eyeH/2, eyeW, eyeH);
            g2.setColor(new Color(80, 0, 0));
            g2.drawOval(x + w/4 - eyeW/2, y + h/3 - eyeH/2, eyeW, eyeH);
            g2.drawOval(x + 3*w/4 - eyeW/2, y + h/3 - eyeH/2, eyeW, eyeH);

            // health bar above
            int barW = w;
            int barH = 8;
            int bx = x;
            int by = y - barH - 4;
            float hpRatio = Math.max(0f, Math.min(1f, b.hp / 5f));

            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(bx-1, by-1, barW+2, barH+2, 6, 6);
            g2.setColor(new Color(80, 20, 20));
            g2.drawRoundRect(bx-1, by-1, barW+2, barH+2, 6, 6);

            int fillW = (int)(barW * hpRatio);
            g2.setPaint(new GradientPaint(
                    bx, by, new Color(255, 110, 90),
                    bx, by+barH, new Color(180, 20, 20)));
            g2.fillRoundRect(bx, by, fillW, barH, 4, 4);
        }

        private void drawSkeuoPlayer(Graphics2D g2, Rectangle r){
            int x = r.x;
            int y = r.y;
            int w = r.width;
            int h = r.height;

            Shape body = new RoundRectangle2D.Float(x, y, w, h, 10, 10);

            g2.setPaint(new GradientPaint(
                    x, y, new Color(190, 245, 255),
                    x, y+h, new Color(40, 120, 150)));
            g2.fill(body);

            g2.setColor(new Color(0, 40, 60));
            g2.setStroke(new BasicStroke(2.0f));
            g2.draw(body);

            // visor
            int visorH = h / 4;
            Shape visor = new RoundRectangle2D.Float(x + w*0.18f, y + h*0.18f, w*0.64f, visorH, visorH, visorH);
            g2.setPaint(new GradientPaint(
                    x, y, new Color(240, 255, 255, 230),
                    x, y+visorH, new Color(120, 190, 220, 230)));
            g2.fill(visor);

            // subtle shine
            g2.setColor(new Color(255,255,255,140));
            g2.drawArc(x+4, y+3, w-8, h/2, 180, 160);
        }
        
        void paintDemoUI(Graphics2D g2){
            // Demo info overlay
            g2.setFont(new Font("Arial", Font.BOLD, 20));
            g2.setColor(Color.WHITE);
            g2.drawString("Demo: Level 1-5 - Press ENTER to Play", 20, 40);
        }
        
        void updateDemo(float dt){
            // Similar a update normal pero sin input del usuario
            for(MovingPlatform mp : movingPlatforms) mp.update(dt);

            vx = 0f; // Demo controls via IA
            if(jump && onGround){ vy = -520f; onGround = false; }
            vy += 1200f * dt;
            posX += vx * dt;
            posY += vy * dt;

            if(debugFrames > 0){
                System.out.println(String.format("[DEMO] frame debug: posY=%.2f vy=%.2f onGround=%b", posY, vy, onGround));
                debugFrames--;
            }

            player.x = Math.max(0, (int)posX);
            if(player.x + player.width > W) player.x = W - player.width;
            posX = player.x;
            player.y = (int)posY;
            
            cameraX = posX - W/4f;
            if(cameraX < 0) cameraX = 0;
            if(cameraX + W > levelWidth) cameraX = levelWidth - W;

            onGround = false;
            for(Rectangle p : platforms){
                if(player.intersects(p)){
                    Rectangle inter = player.intersection(p);
                    if(inter.height < inter.width ){
                        if(player.y < p.y){
                            player.y = p.y - player.height;
                            posY = player.y;
                            vy = 0;
                            onGround = true;
                        } else {
                            player.y = p.y + p.height;
                            posY = player.y;
                            vy = 0;
                        }
                    } else {
                        if(player.x < p.x) {
                            player.x = p.x - player.width;
                            posX = player.x;
                        } else {
                            player.x = p.x + p.width;
                            posX = player.x;
                        }
                    }
                }
            }

            for(MovingPlatform mp : movingPlatforms){
                Rectangle p = mp.getRect();
                if(player.intersects(p)){
                    Rectangle inter = player.intersection(p);
                    if(inter.height < inter.width){
                        if(player.y < p.y){
                            player.y = p.y - player.height;
                            posY = player.y;
                            vy = 0;
                            onGround = true;
                        } else {
                            player.y = p.y + p.height;
                            posY = player.y;
                            vy = 0;
                        }
                    } else {
                        if(player.x < p.x) {
                            player.x = p.x - player.width;
                            posX = player.x;
                        } else {
                            player.x = p.x + p.width;
                            posX = player.x;
                        }
                    }
                }
            }

            for(Polygon t : killers){
                if( containsAnyCorner(t, player) ){
                    attemptCount++;
                    Level L = levels.get(currentLevel);
                    posX = L.startX; posY = L.startY; vy = 0; vx = 0;
                    player.x = (int)posX; player.y = (int)posY;
                }
            }

            if(player.intersects(goal) && allBossesDefeated()){
                int next = currentLevel + 1;
                if(next >= levels.size()) next = 0;
                showingMessage = true;
                messageTimer = 84;
                messageText = "Demo Complete!";
                pendingNextLevel = -1; // don't auto-advance in demo
            }

            if(showingMessage){
                messageTimer--;
                if(messageTimer <= 0){
                    showingMessage = false;
                }
            }
        }

        // KeyListener
        @Override public void keyTyped(KeyEvent e) {}
        @Override public void keyPressed(KeyEvent e) {
            
            // Menu navigation
            if(gameState == GameState.MENU){
                switch(e.getKeyCode()){
                    case KeyEvent.VK_LEFT:
                    case KeyEvent.VK_A:
                        selectedMenu = (selectedMenu - 1 + 2) % 2; break;
                    case KeyEvent.VK_RIGHT:
                    case KeyEvent.VK_D:
                        selectedMenu = (selectedMenu + 1) % 2; break;
                    case KeyEvent.VK_ENTER:
                        if(selectedMenu == 0){
                            // Play
                            loadLevel(0);
                            gameState = GameState.PLAYING;
                        } else {
                            // Custom Pack
                            SwingUtilities.invokeLater(() -> {
                                JFileChooser fc = new JFileChooser();
                                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                                int res = fc.showOpenDialog(GamePanel.this);
                                if(res == JFileChooser.APPROVE_OPTION){
                                    File dir = fc.getSelectedFile();
                                    loadCustomPack(dir);
                                    if(!levels.isEmpty()){
                                        loadLevel(0);
                                        gameState = GameState.PLAYING;
                                    }
                                }
                            });
                        }
                        break;
                }
                return;
            }
            
            // gameplay controls
            switch(e.getKeyCode()){
                case KeyEvent.VK_LEFT:
                case KeyEvent.VK_A:
                    left = true; break;
                case KeyEvent.VK_RIGHT:
                case KeyEvent.VK_D:
                    right = true; break;
                case KeyEvent.VK_UP:
                case KeyEvent.VK_W:
                case KeyEvent.VK_SPACE:
                    jump = true; break;
                case KeyEvent.VK_ESCAPE:
                    gameState = GameState.MENU;
                    selectedMenu = 0;
                    break;
                case KeyEvent.VK_C:
                    // open custom .xlevel package
                    SwingUtilities.invokeLater(() -> {
                        JFileChooser fc = new JFileChooser();
                        fc.setFileFilter(new FileNameExtensionFilter("XLevel package (zip/.xlevel)", "xlevel", "zip"));
                        int res = fc.showOpenDialog(GamePanel.this);
                        if(res == JFileChooser.APPROVE_OPTION){
                            File f = fc.getSelectedFile();
                            new Thread(() -> loadXLevel(f)).start();
                        }
                    });
                    break;
                case KeyEvent.VK_I:
                    iKeyPressed = true; break;
                case KeyEvent.VK_E:
                    if(iKeyPressed){ debugMode = true; debugModeTimer = 300; } // ~5 seconds
                    break;
                case KeyEvent.VK_0:
                case KeyEvent.VK_1:
                case KeyEvent.VK_2:
                case KeyEvent.VK_3:
                case KeyEvent.VK_4:
                case KeyEvent.VK_5:
                case KeyEvent.VK_6:
                case KeyEvent.VK_7:
                case KeyEvent.VK_8:
                case KeyEvent.VK_9:
                    if(debugMode){
                        int num = e.getKeyCode() - KeyEvent.VK_0;
                        if(num > 0 && num <= levels.size()){
                            loadLevel(num - 1);
                            debugMode = false;
                            debugModeTimer = 0;
                        }
                    }
                    break;
            }
        }
        @Override public void keyReleased(KeyEvent e) {
            switch(e.getKeyCode()){
                case KeyEvent.VK_LEFT:
                case KeyEvent.VK_A:
                    left = false; break;
                case KeyEvent.VK_RIGHT:
                case KeyEvent.VK_D:
                    right = false; break;
                case KeyEvent.VK_UP:
                case KeyEvent.VK_W:
                case KeyEvent.VK_SPACE:
                    jump = false; break;
                case KeyEvent.VK_I:
                    iKeyPressed = false; break;
            }
        }
        
        void loadCustomPack(File directory){
            levels.clear();
            customLoadedCount = 0;
            if(directory == null || !directory.isDirectory()) return;
            
            File[] files = directory.listFiles((d,name)->name.endsWith(".txt")||name.endsWith(".lvl"));
            if(files != null){
                Arrays.sort(files, Comparator.comparing(File::getName));
                for(File f : files){
                    try{ 
                        levels.add(Level.loadFromFile(f));
                        customLoadedCount++;
                    }catch(Exception e){ 
                        System.err.println("Failed to load " + f + ": " + e); 
                    }
                }
            }
            System.out.println("[INFO] Loaded " + customLoadedCount + " custom levels from " + directory.getAbsolutePath());
            recomputeMouseLevels();
        }
        
        // Level data structure and loader
        static class MovingPlatform {
            float x, y;
            int width, height;
            float minX, maxX, minY, maxY;
            float speed;
            float dirX, dirY;
            
            public MovingPlatform(int x, int y, int width, int height, float minX, float maxX, float minY, float maxY, float speed) {
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
                this.minX = minX;
                this.maxX = maxX;
                this.minY = minY;
                this.maxY = maxY;
                this.speed = speed;
                
                // determine direction
                if(maxX > minX) {
                    dirX = 1f;
                    dirY = 0f;
                } else {
                    dirX = 0f;
                    dirY = 1f;
                }
            }
            
            public void update(float dt) {
                x += dirX * speed * dt;
                y += dirY * speed * dt;
                
                if(dirX != 0) {
                    if(x <= minX) { x = minX; dirX = 1f; }
                    if(x >= maxX) { x = maxX; dirX = -1f; }
                } else {
                    if(y <= minY) { y = minY; dirY = 1f; }
                    if(y >= maxY) { y = maxY; dirY = -1f; }
                }
            }
            
            public Rectangle getRect() {
                return new Rectangle((int)x, (int)y, width, height);
            }
        }

        static class DraggablePlatform {
            Rectangle rect;
            DraggablePlatform(int x, int y, int w, int h){
                this.rect = new Rectangle(x, y, w, h);
            }
        }

        static class Boss {
            Rectangle rect;
            int hp;
            boolean alive = true;
            Boss(int x, int y, int w, int h, int hp){
                this.rect = new Rectangle(x, y, w, h);
                this.hp = Math.max(1, hp);
            }
        }

        static class Level{
            String name = "";
            int startX = 50, startY = 400;
            ArrayList<Rectangle> platforms = new ArrayList<>();
            ArrayList<Polygon> killers = new ArrayList<>();
            ArrayList<MovingPlatform> movingPlatforms = new ArrayList<>();
            ArrayList<Rectangle> invisiblePlatforms = new ArrayList<>();
            ArrayList<DraggablePlatform> dragPlatforms = new ArrayList<>();
            ArrayList<Boss> bosses = new ArrayList<>();
            Rectangle goal = new Rectangle(720,210,40,40);

            static Level defaultLevel1(){
                Level L = new Level(); L.name = "Level 1-1";
                L.startX = 50; L.startY = 400;
                L.platforms.add(new Rectangle(0,550,800,50));
                L.platforms.add(new Rectangle(150,470,120,20));
                L.platforms.add(new Rectangle(320,380,100,20));
                L.platforms.add(new Rectangle(480,340,140,20));
                L.platforms.add(new Rectangle(660,260,100,20));
                L.killers.add(new Polygon(new int[]{400,450,425}, new int[]{550,550,520},3));
                L.killers.add(new Polygon(new int[]{560,610,585}, new int[]{400,400,370},3));
                L.goal = new Rectangle(720,210,40,40);
                return L;
            }
            static Level defaultLevel2(){
                Level L = new Level(); L.name = "Level 1-2";
                L.startX = 50; L.startY = 400;
                L.platforms.add(new Rectangle(0,550,800,50));
                L.platforms.add(new Rectangle(120,470,100,20));
                L.platforms.add(new Rectangle(260,420,80,20));
                L.platforms.add(new Rectangle(420,360,120,20));
                L.platforms.add(new Rectangle(600,300,120,20));
                L.killers.add(new Polygon(new int[]{300,350,325}, new int[]{550,550,520},3));
                L.goal = new Rectangle(680,260,40,40);
                return L;
            }

            static Level loadFromFile(File f) throws IOException{
                Level L = new Level();
                try(BufferedReader r = new BufferedReader(new FileReader(f))){
                    String line;
                    while((line = r.readLine())!=null){
                        line = line.trim();
                        if(line.isEmpty()||line.startsWith("#")) continue;
                        String[] parts = line.split(":",2);
                        if(parts.length<2) continue;
                        String key = parts[0].trim().toLowerCase();
                        String data = parts[1].trim();
                        switch(key){
                            case "name": L.name = data; break;
                            case "start": {
                                String[] s = data.split("\\s+");
                                L.startX = Integer.parseInt(s[0]); L.startY = Integer.parseInt(s[1]);
                            } break;
                            case "platform": {
                                String[] s = data.split("\\s+");
                                L.platforms.add(new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3])));
                            } break;
                            case "movingplatform": {
                                String[] s = data.split("\\s+");
                                int x = Integer.parseInt(s[0]);
                                int y = Integer.parseInt(s[1]);
                                int w = Integer.parseInt(s[2]);
                                int h = Integer.parseInt(s[3]);
                                float minX = Float.parseFloat(s[4]);
                                float maxX = Float.parseFloat(s[5]);
                                float minY = Float.parseFloat(s[6]);
                                float maxY = Float.parseFloat(s[7]);
                                float speed = Float.parseFloat(s[8]);
                                L.movingPlatforms.add(new MovingPlatform(x, y, w, h, minX, maxX, minY, maxY, speed));
                            } break;
                            case "dragplatform": {
                                String[] s = data.split("\\s+");
                                int x = Integer.parseInt(s[0]);
                                int y = Integer.parseInt(s[1]);
                                int w = Integer.parseInt(s[2]);
                                int h = Integer.parseInt(s[3]);
                                L.dragPlatforms.add(new DraggablePlatform(x, y, w, h));
                            } break;
                            case "invisibleplatform": {
                                String[] s = data.split("\\s+");
                                L.invisiblePlatforms.add(new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3])));
                            } break;
                            case "boss": {
                                String[] s = data.split("\\s+");
                                int x = Integer.parseInt(s[0]);
                                int y = Integer.parseInt(s[1]);
                                int w = Integer.parseInt(s[2]);
                                int h = Integer.parseInt(s[3]);
                                int hp = (s.length > 4) ? Integer.parseInt(s[4]) : 3;
                                L.bosses.add(new Boss(x, y, w, h, hp));
                            } break;
                            case "killer": {
                                String[] s = data.split("\\s+");
                                int[] xs = new int[]{Integer.parseInt(s[0]),Integer.parseInt(s[2]),Integer.parseInt(s[4])};
                                int[] ys = new int[]{Integer.parseInt(s[1]),Integer.parseInt(s[3]),Integer.parseInt(s[5])};
                                L.killers.add(new Polygon(xs, ys, 3));
                            } break;
                            case "goal": {
                                String[] s = data.split("\\s+");
                                L.goal = new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3]));
                            } break;
                        }
                    }
                }
                return L;
            }
            static Level loadFromStream(InputStream in) throws IOException{
                Level L = new Level();
                try(BufferedReader r = new BufferedReader(new InputStreamReader(in))){
                    String line;
                    while((line = r.readLine())!=null){
                        line = line.trim();
                        if(line.isEmpty()||line.startsWith("#")) continue;
                        String[] parts = line.split(":",2);
                        if(parts.length<2) continue;
                        String key = parts[0].trim().toLowerCase();
                        String data = parts[1].trim();
                        switch(key){
                            case "name": L.name = data; break;
                            case "start": {
                                String[] s = data.split("\\s+");
                                L.startX = Integer.parseInt(s[0]); L.startY = Integer.parseInt(s[1]);
                            } break;
                            case "platform": {
                                String[] s = data.split("\\s+");
                                L.platforms.add(new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3])));
                            } break;
                            case "movingplatform": {
                                String[] s = data.split("\\s+");
                                int x = Integer.parseInt(s[0]);
                                int y = Integer.parseInt(s[1]);
                                int w = Integer.parseInt(s[2]);
                                int h = Integer.parseInt(s[3]);
                                float minX = Float.parseFloat(s[4]);
                                float maxX = Float.parseFloat(s[5]);
                                float minY = Float.parseFloat(s[6]);
                                float maxY = Float.parseFloat(s[7]);
                                float speed = Float.parseFloat(s[8]);
                                L.movingPlatforms.add(new MovingPlatform(x, y, w, h, minX, maxX, minY, maxY, speed));
                            } break;
                            case "dragplatform": {
                                String[] s = data.split("\\s+");
                                int x = Integer.parseInt(s[0]);
                                int y = Integer.parseInt(s[1]);
                                int w = Integer.parseInt(s[2]);
                                int h = Integer.parseInt(s[3]);
                                L.dragPlatforms.add(new DraggablePlatform(x, y, w, h));
                            } break;
                            case "invisibleplatform": {
                                String[] s = data.split("\\s+");
                                L.invisiblePlatforms.add(new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3])));
                            } break;
                            case "boss": {
                                String[] s = data.split("\\s+");
                                int x = Integer.parseInt(s[0]);
                                int y = Integer.parseInt(s[1]);
                                int w = Integer.parseInt(s[2]);
                                int h = Integer.parseInt(s[3]);
                                int hp = (s.length > 4) ? Integer.parseInt(s[4]) : 3;
                                L.bosses.add(new Boss(x, y, w, h, hp));
                            } break;
                            case "killer": {
                                String[] s = data.split("\\s+");
                                int[] xs = new int[]{Integer.parseInt(s[0]),Integer.parseInt(s[2]),Integer.parseInt(s[4])};
                                int[] ys = new int[]{Integer.parseInt(s[1]),Integer.parseInt(s[3]),Integer.parseInt(s[5])};
                                L.killers.add(new Polygon(xs, ys, 3));
                            } break;
                            case "goal": {
                                String[] s = data.split("\\s+");
                                L.goal = new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3]));
                            } break;
                        }
                    }
                }
                return L;
            }
        }
        
        // Mouse listeners
        private int toLogicalX(int screenX){
            return (int) ((screenX - renderOffsetX) / Math.max(renderScale, 0.0001f));
        }
        private int toLogicalY(int screenY){
            return (int) ((screenY - renderOffsetY) / Math.max(renderScale, 0.0001f));
        }

        @Override public void mousePressed(MouseEvent e){
            int lx = toLogicalX(e.getX());
            int ly = toLogicalY(e.getY());
            if(gameState == GameState.MENU){
                if(btnPlay.contains(lx, ly)){
                    loadLevel(0);
                    gameState = GameState.PLAYING;
                } else if(btnCustom.contains(lx, ly)){
                    SwingUtilities.invokeLater(() -> {
                        JFileChooser fc = new JFileChooser();
                        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                        int res = fc.showOpenDialog(GamePanel.this);
                        if(res == JFileChooser.APPROVE_OPTION){
                            File dir = fc.getSelectedFile();
                            loadCustomPack(dir);
                            if(!levels.isEmpty()){
                                loadLevel(0);
                                gameState = GameState.PLAYING;
                            }
                        }
                    });
                }
            } else if(gameState == GameState.PLAYING){
                // start dragging any cursor-movable platform under the cursor
                for(DraggablePlatform dp : dragPlatforms){
                    if(dp.rect.contains(lx, ly)){
                        activeDrag = dp;
                        dragOffsetX = lx - dp.rect.x;
                        dragOffsetY = ly - dp.rect.y;
                        break;
                    }
                }
            }
        }
        
        @Override public void mouseMoved(MouseEvent e){
            mouseX = toLogicalX(e.getX());
            mouseY = toLogicalY(e.getY());
            
            if(gameState == GameState.MENU){
                if(btnPlay.contains(mouseX, mouseY)){
                    selectedMenu = 0;
                } else if(btnCustom.contains(mouseX, mouseY)){
                    selectedMenu = 1;
                }
            }
        }
        
        @Override public void mouseClicked(MouseEvent e){}
        @Override public void mouseReleased(MouseEvent e){
            activeDrag = null;
        }
        @Override public void mouseEntered(MouseEvent e){}
        @Override public void mouseExited(MouseEvent e){}
        @Override public void mouseDragged(MouseEvent e){
            if(activeDrag != null){
                int lx = toLogicalX(e.getX());
                int ly = toLogicalY(e.getY());
                Rectangle r = activeDrag.rect;
                r.x = lx - dragOffsetX;
                r.y = ly - dragOffsetY;
                // clamp within simple bounds
                if(r.x < 0) r.x = 0;
                if(r.x + r.width > levelWidth) r.x = (int)levelWidth - r.width;
                if(r.y < 0) r.y = 0;
                if(r.y + r.height > H) r.y = H - r.height;
            }
        }
        
        // Simple IA Player for demo
        static class AIPlayer {
            int decisionTimer = 0;
            boolean aiLeft = false, aiRight = false, aiJump = false;
            
            void update(float dt, GamePanel panel){
                decisionTimer--;
                if(decisionTimer <= 0){
                    decisionTimer = 60; // Decide every 60 frames (~1 second)
                    decideMoveTowardGoal(panel);
                }
                
                panel.left = aiLeft;
                panel.right = aiRight;
                panel.jump = aiJump;
            }
            
            void decideMoveTowardGoal(GamePanel panel){
                float goalAvgX = (panel.goal.x + panel.goal.width/2);
                float playerX = panel.posX + 16; // center of player
                
                int tolerance = 50;
                if(playerX < goalAvgX - tolerance){
                    aiRight = true;
                    aiLeft = false;
                } else if(playerX > goalAvgX + tolerance){
                    aiLeft = true;
                    aiRight = false;
                } else {
                    aiLeft = false;
                    aiRight = false;
                }
                
                // Jump sometimes when on ground
                if(panel.onGround && Math.random() < 0.4){
                    aiJump = true;
                } else {
                    aiJump = false;
                }
            }
        }
    }
}