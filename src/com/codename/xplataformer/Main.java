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
        JFrame frame = new JFrame("XPlataformer");
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
        int lavaKillY;

        ArrayList<Rectangle> platforms = new ArrayList<>();
        ArrayList<Polygon> killers = new ArrayList<>();
        ArrayList<MovingPlatform> movingPlatforms = new ArrayList<>();
        // extra mechanics
        ArrayList<Rectangle> invisiblePlatforms = new ArrayList<>();
        ArrayList<DraggablePlatform> dragPlatforms = new ArrayList<>();
        ArrayList<Boss> bosses = new ArrayList<>();
        ArrayList<Fireball> fireballs = new ArrayList<>();
        Rectangle goal;
        Rectangle checkpoint = null;
        boolean checkpointActive = false;
        int checkpointRespawnX = 0;
        int checkpointRespawnY = 0;
        int checkpointDeaths = 0;
        
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
        int debugSelectedLevel = 0;
        Rectangle debugPanelBounds = new Rectangle(760, 80, 290, 420);
        // level management
        List<Level> levels = new ArrayList<>();
        int currentLevel = 0;
        int attemptCount = 1;
        int playerMaxHp = 3;
        int playerHp = 3;
        int damageInvulnTimer = 0;
        boolean respawnPending = false;
        int explosionTimer = 0;
        float explosionX = 0f, explosionY = 0f;
        String pendingDeathReason = "";
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
            lavaKillY = H + 80;
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
                        Arrays.sort(files, this::compareLevelFiles);
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
                // create default levels folder and files - note, THESE LEVELS ARE BROKEN!
                dir.mkdirs();
                createDefaultLevels(base);
            }
            File[] files = dir.listFiles((d,name)->name.endsWith(".txt")||name.endsWith(".lvl"));
            if(files==null || files.length==0){
                createDefaultLevels(base);
                files = dir.listFiles((d,name)->name.endsWith(".txt")||name.endsWith(".lvl"));
            }
            if(files!=null){
                Arrays.sort(files, this::compareLevelFiles);
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
            fireballs.clear();
            platforms.addAll(L.platforms);
            killers.addAll(L.killers);
            movingPlatforms.addAll(L.movingPlatforms);
            invisiblePlatforms.addAll(L.invisiblePlatforms);
            dragPlatforms.addAll(L.dragPlatforms);
            bosses.addAll(L.bosses);
            goal = L.goal;
            checkpoint = L.checkpoint;
            checkpointActive = false;
            checkpointDeaths = 0;
            player = new Rectangle(L.startX, L.startY, 32, 48);
            posX = player.x; posY = player.y;
            // reset motion state so player doesn't get moved away by leftover velocity
            vx = 0f; vy = 0f; onGround = false;
            playerHp = playerMaxHp;
            damageInvulnTimer = 0;
            respawnPending = false;
            explosionTimer = 0;
            cameraX = 0;
            
            // Calculate level width based on rightmost element (at least as wide as the viewport)
            levelWidth = W;
            for(Rectangle p : platforms) levelWidth = Math.max(levelWidth, p.x + p.width);
            for(Polygon k : killers){
                int[] xs = k.xpoints;
                for(int x : xs) levelWidth = Math.max(levelWidth, x);
            }
            for(MovingPlatform mp : movingPlatforms) levelWidth = Math.max(levelWidth, (int)mp.maxX + mp.width);
            for(Boss b : bosses) if(b != null && b.rect != null) levelWidth = Math.max(levelWidth, b.rect.x + b.rect.width);
            if(checkpoint != null) levelWidth = Math.max(levelWidth, checkpoint.x + checkpoint.width);
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
            if(damageInvulnTimer > 0) damageInvulnTimer--;
            if(respawnPending){
                explosionTimer--;
                if(explosionTimer <= 0){
                    respawnPlayer(pendingDeathReason.isEmpty() ? "You died!" : pendingDeathReason);
                    pendingDeathReason = "";
                }
                return;
            }

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
            int maxPlayerX = Math.max(0, (int)levelWidth - player.width);
            if(player.x > maxPlayerX) player.x = maxPlayerX;
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

            // boss AI + fire attacks
            updateBossesAndFire(dt);

            // boss interaction
            for(Boss b : bosses){
                if(!b.alive) continue;
                Rectangle br = b.rect;
                if(player.intersects(br)){
                    Rectangle inter = player.intersection(br);
                    boolean stompFromTop = b.tired
                            && vy > 60f
                            && (player.y + player.height) <= (br.y + Math.max(14, br.height / 2));
                    if(stompFromTop){
                        // damage boss and bounce
                        b.hp -= 1;
                        if(b.hp <= 0) b.alive = false;
                        player.y = br.y - player.height;
                        posY = player.y;
                        vy = -420f;
                        onGround = false;
                        b.tired = false;
                        b.tiredTimer = 0f;
                        b.attackTimer = 1.8f;
                        b.rect.y = b.homeY;
                    } else {
                        // player loses: respawn
                        damagePlayer("Boss hit!");
                    }
                }
            }

            // killer triangles
            for(Polygon t : killers){
                if( containsAnyCorner(t, player) ){
                    // respawn
                    damagePlayer("You got spiked!");
                }
            }

            // fireballs
            for(int i = fireballs.size()-1; i >= 0; i--){
                Fireball f = fireballs.get(i);
                f.update(dt);
                if(!f.alive){
                    fireballs.remove(i);
                    continue;
                }
                if(player.intersects(f.rect)){
                    fireballs.remove(i);
                    damagePlayer("BOOM! Fireball hit.");
                    break;
                }
            }

            // checkpoint activation
            if(checkpoint != null && player.intersects(checkpoint) && !checkpointActive){
                checkpointActive = true;
                checkpointDeaths = 0;
                checkpointRespawnX = checkpoint.x + Math.max(0, (checkpoint.width - player.width) / 2);
                checkpointRespawnY = checkpoint.y - player.height;
                showingMessage = true;
                messageText = "Checkpoint reached!";
                messageTimer = 60;
            }

            // void -> lava death
            if(player.y > lavaKillY){
                damagePlayer("You fell into lava!");
                return;
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

        void respawnPlayer(String reason){
            attemptCount++;
            Level L = levels.get(currentLevel);
            int rx = L.startX;
            int ry = L.startY;
            if(checkpointActive){
                checkpointDeaths++;
                if(checkpointDeaths >= 10){
                    checkpointActive = false;
                    checkpointDeaths = 0;
                    messageText = "Checkpoint lost (10 deaths). Return to start.";
                    reason = messageText;
                } else {
                    rx = checkpointRespawnX;
                    ry = checkpointRespawnY;
                }
            }
            posX = rx; posY = ry; vy = 0; vx = 0;
            player.x = (int)posX; player.y = (int)posY;
            playerHp = playerMaxHp;
            damageInvulnTimer = 20;
            respawnPending = false;
            showingMessage = true;
            messageText = reason;
            messageTimer = 45;
            fireballs.clear();
        }

        void damagePlayer(String reason){
            if(respawnPending) return;
            if(damageInvulnTimer > 0) return;

            playerHp = Math.max(0, playerHp - 1);
            damageInvulnTimer = 26;

            if(playerHp <= 0){
                respawnPending = true;
                explosionTimer = 26;
                explosionX = player.x + player.width / 2f;
                explosionY = player.y + player.height / 2f;
                pendingDeathReason = reason;
                fireballs.clear();
                showingMessage = true;
                messageText = "You exploded!";
                messageTimer = 35;
                return;
            }

            // small knock-up feedback on hit without instant death
            vy = Math.min(vy, -220f);
            showingMessage = true;
            messageText = "HP: " + playerHp + "/" + playerMaxHp;
            messageTimer = 25;
        }

        void updateBossesAndFire(float dt){
            for(Boss b : bosses){
                if(!b.alive) continue;

                if(b.tired){
                    b.rect.y = Math.min(b.tiredY, b.rect.y + Math.max(1, (int)(260f * dt)));
                    b.tiredTimer -= dt;
                    if(b.tiredTimer <= 0f){
                        b.tired = false;
                        b.attackTimer = 3.6f;
                    }
                    continue;
                }

                if(b.rect.y > b.homeY){
                    b.rect.y = Math.max(b.homeY, b.rect.y - Math.max(1, (int)(220f * dt)));
                }

                b.attackTimer -= dt;
                b.fireTimer -= dt;

                // boss periodically goes tired and can be stomped
                if(b.attackTimer <= 0f){
                    b.tired = true;
                    b.tiredTimer = 2.2f;
                    b.fireTimer = 0f;
                    continue;
                }

                if(b.fireTimer <= 0f){
                    float px = player.x + player.width / 2f;
                    float py = player.y + player.height / 2f;
                    float bx = b.rect.x + b.rect.width / 2f;
                    float by = b.rect.y + b.rect.height / 2f;
                    float dx = px - bx;
                    float dy = py - by;
                    float len = (float)Math.sqrt(dx*dx + dy*dy);
                    if(len < 0.0001f) len = 1f;
                    float speed = 280f;
                    float vxF = (dx / len) * speed;
                    float vyF = (dy / len) * speed;
                    fireballs.add(new Fireball((int)bx - 6, (int)by - 6, 12, 12, vxF, vyF));
                    b.fireTimer = 1.15f;
                }
            }
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

        int compareLevelFiles(File a, File b){
            int na = extractLevelNumber(a.getName());
            int nb = extractLevelNumber(b.getName());
            if(na != nb) return Integer.compare(na, nb);
            return a.getName().compareToIgnoreCase(b.getName());
        }

        int extractLevelNumber(String name){
            StringBuilder sb = new StringBuilder();
            for(int i = 0; i < name.length(); i++){
                char c = name.charAt(i);
                if(Character.isDigit(c)) sb.append(c);
            }
            if(sb.length() == 0) return Integer.MAX_VALUE;
            try{ return Integer.parseInt(sb.toString()); }
            catch(NumberFormatException ex){ return Integer.MAX_VALUE; }
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

            // boss-fight backdrop (castle pillars) behind gameplay geometry
            if(!bosses.isEmpty()){
                drawBossCastleBackdrop(g2);
            }
            
            // platforms (fixed)
            for(Rectangle p : platforms) drawSkeuoPlatform(g2, p, false);

            // moving platforms
            for(MovingPlatform mp : movingPlatforms) drawSkeuoPlatform(g2, mp.getRect(), true);

            // cursor-movable platforms
            for(DraggablePlatform dp : dragPlatforms) drawSkeuoDragPlatform(g2, dp.rect);

            // killers
            for(Polygon t : killers) drawSkeuoKiller(g2, t);

            // lava band (void kill zone hint)
            drawLava(g2);

            // bosses
            for(Boss b : bosses) if(b.alive) drawBoss(g2, b);
            for(Fireball f : fireballs) drawFireball(g2, f);
            if(checkpoint != null) drawCheckpoint(g2, checkpoint, checkpointActive);

            // goal
            drawSkeuoGoal(g2, goal);

            // player
            if(!respawnPending){
                drawSkeuoPlayer(g2, player);
            }
            if(explosionTimer > 0){
                drawExplosion(g2, explosionX, explosionY, explosionTimer);
            }
            
            // Reset camera transform
            g2.translate(cameraX, 0);

            // HUD (no camera transform)
            int hudPad = 10;
            int hudWidth = 360;
            int hudHeight = 88;
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
            g2.drawString("HP: " + playerHp + "/" + playerMaxHp, hudPad + 12, hudPad + 76);
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

            // boss HUD when near
            Boss nearBoss = getNearestAliveBoss(520f);
            if(nearBoss != null){
                drawBossHud(g2, nearBoss);
            }
            
            // debug mode display
            if(debugMode){
                drawDebugSelector(g2);
            }
        }

        private void drawDebugSelector(Graphics2D g2){
            int x = debugPanelBounds.x;
            int y = debugPanelBounds.y;
            int w = debugPanelBounds.width;
            int h = debugPanelBounds.height;
            Shape panel = new RoundRectangle2D.Float(x, y, w, h, 14, 14);
            g2.setColor(new Color(0,0,0,170));
            g2.fill(new RoundRectangle2D.Float(x+2, y+4, w, h, 14, 14));
            g2.setPaint(new GradientPaint(x, y, new Color(68, 74, 88, 240), x, y+h, new Color(35, 40, 50, 240)));
            g2.fill(panel);
            g2.setColor(new Color(20, 20, 26, 230));
            g2.draw(panel);

            g2.setFont(new Font("SansSerif", Font.BOLD, 14));
            g2.setColor(new Color(245, 245, 210));
            g2.drawString("Debug Level Selector", x + 12, y + 22);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g2.setColor(new Color(220, 226, 235));
            g2.drawString("UP/DOWN: select | ENTER: load | I+E: close", x + 12, y + 38);

            int startY = y + 56;
            int rowH = 18;
            int visible = Math.min(18, levels.size());
            int startIndex = 0;
            if(debugSelectedLevel >= visible) startIndex = debugSelectedLevel - visible + 1;
            for(int i = 0; i < visible; i++){
                int idx = startIndex + i;
                if(idx >= levels.size()) break;
                int ry = startY + i*rowH;
                if(idx == debugSelectedLevel){
                    g2.setPaint(new GradientPaint(x+10, ry-12, new Color(255, 235, 160, 230), x+10, ry+4, new Color(210, 165, 65, 230)));
                    g2.fill(new RoundRectangle2D.Float(x + 8, ry - 12, w - 16, 16, 8, 8));
                }
                String nm = levels.get(idx).name == null ? "" : levels.get(idx).name;
                String text = (idx + 1) + ". " + nm;
                g2.setColor(new Color(30, 24, 20, 220));
                g2.drawString(trimDebugText(g2, text, w - 28), x + 14, ry);
            }
        }

        private String trimDebugText(Graphics2D g2, String text, int maxWidth){
            FontMetrics fm = g2.getFontMetrics();
            if(fm.stringWidth(text) <= maxWidth) return text;
            String dots = "...";
            int lim = maxWidth - fm.stringWidth(dots);
            if(lim <= 0) return dots;
            StringBuilder sb = new StringBuilder();
            for(int i=0;i<text.length();i++){
                char c = text.charAt(i);
                if(fm.stringWidth(sb.toString() + c) > lim) break;
                sb.append(c);
            }
            return sb.append(dots).toString();
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

        private void drawLava(Graphics2D g2){
            int lavaTop = H - 8;
            int width = (int)Math.max(levelWidth + 200, W + 200);
            int x = -100;
            g2.setPaint(new GradientPaint(
                    x, lavaTop, new Color(255, 170, 40, 220),
                    x, lavaTop + 28, new Color(185, 20, 0, 235)));
            g2.fillRect(x, lavaTop, width, 28);
            g2.setColor(new Color(255, 240, 140, 180));
            for(int i = x; i < x + width; i += 26){
                g2.drawArc(i, lavaTop - 7, 20, 10, 0, 180);
            }
        }

        private void drawBossCastleBackdrop(Graphics2D g2){
            if(bosses.isEmpty()) return;
            Boss ref = null;
            for(Boss b : bosses){
                if(b != null){ ref = b; break; }
            }
            if(ref == null) return;

            int arenaCenterX = ref.rect.x + ref.rect.width / 2;
            int wallX = arenaCenterX - 420;
            int wallY = 130;
            int wallW = 840;
            int wallH = 430;

            // distant wall plate
            g2.setPaint(new GradientPaint(
                    wallX, wallY, new Color(85, 84, 92, 140),
                    wallX, wallY + wallH, new Color(30, 30, 40, 180)));
            g2.fillRoundRect(wallX, wallY, wallW, wallH, 20, 20);
            g2.setColor(new Color(15, 15, 20, 160));
            g2.drawRoundRect(wallX, wallY, wallW, wallH, 20, 20);

            // simple stone brick lines (cheap effect)
            g2.setColor(new Color(120, 120, 130, 45));
            for(int y = wallY + 26; y < wallY + wallH - 10; y += 24){
                g2.drawLine(wallX + 12, y, wallX + wallW - 12, y);
            }
            for(int x = wallX + 24; x < wallX + wallW - 10; x += 48){
                g2.drawLine(x, wallY + 14, x, wallY + wallH - 14);
            }

            // pillars around the boss arena
            int baseY = 560;
            int[] px = new int[]{
                    arenaCenterX - 340, arenaCenterX - 220, arenaCenterX - 90,
                    arenaCenterX + 90, arenaCenterX + 220, arenaCenterX + 340
            };
            for(int cx : px){
                int pw = 34;
                int py = 180;
                int ph = baseY - py;
                g2.setPaint(new GradientPaint(
                        cx - pw/2, py, new Color(165, 160, 150, 170),
                        cx + pw/2, py + ph, new Color(80, 75, 70, 190)));
                g2.fillRoundRect(cx - pw/2, py, pw, ph, 8, 8);
                g2.setColor(new Color(45, 40, 38, 200));
                g2.drawRoundRect(cx - pw/2, py, pw, ph, 8, 8);

                // cap + base
                g2.setPaint(new GradientPaint(
                        cx - 28, py - 12, new Color(190, 185, 170, 170),
                        cx - 28, py + 2, new Color(90, 86, 78, 190)));
                g2.fillRoundRect(cx - 28, py - 12, 56, 14, 6, 6);
                g2.fillRoundRect(cx - 30, baseY - 8, 60, 12, 6, 6);
            }
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

            // red variant of the player look
            Shape body = new RoundRectangle2D.Float(x, y, w, h, 12, 12);
            g2.setPaint(new GradientPaint(
                    x, y, new Color(255, 130, 130),
                    x, y+h, new Color(150, 20, 30)));
            g2.fill(body);

            g2.setColor(new Color(70, 0, 10));
            g2.setStroke(new BasicStroke(2.2f));
            g2.draw(body);

            int visorH = Math.max(8, h / 4);
            Shape visor = new RoundRectangle2D.Float(x + w*0.18f, y + h*0.18f, w*0.64f, visorH, visorH, visorH);
            g2.setPaint(new GradientPaint(
                    x, y, new Color(255, 235, 235, 220),
                    x, y+visorH, new Color(220, 140, 140, 220)));
            g2.fill(visor);

            // tired state: flatten to floor and show hint
            if(b.tired){
                g2.setColor(new Color(255, 240, 130));
                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                g2.drawString("TIRED", x + 6, y - 8);
            }

        }

        private void drawFireball(Graphics2D g2, Fireball f){
            int x = f.rect.x, y = f.rect.y, s = f.rect.width;
            RadialGradientPaint glow = new RadialGradientPaint(
                    new Point2D.Float(x + s/2f, y + s/2f),
                    s * 1.8f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(255, 230, 110, 220), new Color(255, 80, 10, 0)});
            g2.setPaint(glow);
            g2.fill(new Ellipse2D.Float(x - s, y - s, s*3, s*3));
            g2.setPaint(new GradientPaint(x, y, new Color(255, 245, 160), x, y+s, new Color(255, 60, 10)));
            g2.fillOval(x, y, s, s);
            g2.setColor(new Color(120, 20, 0, 220));
            g2.drawOval(x, y, s, s);
        }

        private void drawExplosion(Graphics2D g2, float cx, float cy, int timer){
            float t = Math.max(0f, Math.min(1f, timer / 26f));
            float rOuter = 54f * (1f - t) + 14f;
            float rInner = rOuter * 0.58f;

            RadialGradientPaint outer = new RadialGradientPaint(
                    new Point2D.Float(cx, cy),
                    rOuter,
                    new float[]{0f, 1f},
                    new Color[]{new Color(255, 230, 120, 230), new Color(255, 70, 0, 0)});
            g2.setPaint(outer);
            g2.fill(new Ellipse2D.Float(cx - rOuter, cy - rOuter, rOuter * 2f, rOuter * 2f));

            g2.setPaint(new GradientPaint(
                    cx, cy - rInner, new Color(255, 250, 180, 240),
                    cx, cy + rInner, new Color(255, 110, 20, 235)));
            g2.fill(new Ellipse2D.Float(cx - rInner, cy - rInner, rInner * 2f, rInner * 2f));
        }

        private void drawCheckpoint(Graphics2D g2, Rectangle cp, boolean active){
            int x = cp.x, y = cp.y, w = cp.width, h = cp.height;
            g2.setPaint(new GradientPaint(
                    x, y, active ? new Color(120, 240, 170) : new Color(180, 180, 180),
                    x, y+h, active ? new Color(40, 130, 90) : new Color(90, 90, 90)));
            g2.fillRoundRect(x, y, w, h, 8, 8);
            g2.setColor(new Color(20, 25, 20, 220));
            g2.drawRoundRect(x, y, w, h, 8, 8);

            // small flag
            int poleX = x + w/2;
            g2.setColor(new Color(230, 230, 235));
            g2.fillRect(poleX-1, y-22, 3, 24);
            Polygon flag = new Polygon(
                    new int[]{poleX+1, poleX+15, poleX+1},
                    new int[]{y-20, y-15, y-10},
                    3);
            g2.setColor(active ? new Color(80, 245, 160) : new Color(220, 220, 220));
            g2.fillPolygon(flag);
        }

        private Boss getNearestAliveBoss(float maxDistance){
            if(player == null) return null;
            float px = player.x + player.width / 2f;
            float py = player.y + player.height / 2f;
            Boss nearest = null;
            float bestD2 = maxDistance * maxDistance;
            for(Boss b : bosses){
                if(b == null || !b.alive || b.rect == null) continue;
                float bx = b.rect.x + b.rect.width / 2f;
                float by = b.rect.y + b.rect.height / 2f;
                float dx = bx - px;
                float dy = by - py;
                float d2 = dx*dx + dy*dy;
                if(d2 <= bestD2){
                    bestD2 = d2;
                    nearest = b;
                }
            }
            return nearest;
        }

        private void drawBossHud(Graphics2D g2, Boss b){
            int bw = 260;
            int bh = 18;
            int bx = (W - bw) / 2;
            int by = 14;
            float hpRatio = Math.max(0f, Math.min(1f, b.hp / (float)b.maxHp));

            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(bx - 2, by - 2, bw + 4, bh + 4, 10, 10);
            g2.setColor(new Color(85, 30, 25, 230));
            g2.drawRoundRect(bx - 2, by - 2, bw + 4, bh + 4, 10, 10);
            g2.setPaint(new GradientPaint(bx, by, new Color(255, 120, 100), bx, by + bh, new Color(170, 22, 22)));
            g2.fillRoundRect(bx, by, (int)(bw * hpRatio), bh, 8, 8);
            g2.setColor(new Color(245, 230, 220));
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            g2.drawString("BOSS", bx + 6, by + 13);
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
            int maxPlayerX = Math.max(0, (int)levelWidth - player.width);
            if(player.x > maxPlayerX) player.x = maxPlayerX;
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

            if(player.y > lavaKillY){
                attemptCount++;
                Level L = levels.get(currentLevel);
                posX = L.startX; posY = L.startY; vy = 0; vx = 0;
                player.x = (int)posX; player.y = (int)posY;
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
            
            if(debugMode){
                switch(e.getKeyCode()){
                    case KeyEvent.VK_UP:
                        debugSelectedLevel = Math.max(0, debugSelectedLevel - 1);
                        return;
                    case KeyEvent.VK_DOWN:
                        debugSelectedLevel = Math.min(Math.max(0, levels.size()-1), debugSelectedLevel + 1);
                        return;
                    case KeyEvent.VK_ENTER:
                        if(!levels.isEmpty()){
                            loadLevel(debugSelectedLevel);
                            debugMode = false;
                            debugModeTimer = 0;
                        }
                        return;
                }
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
                    if(iKeyPressed){
                        debugMode = !debugMode;
                        if(debugMode){
                            debugModeTimer = Integer.MAX_VALUE / 4;
                            debugSelectedLevel = Math.max(0, Math.min(debugSelectedLevel, levels.size()-1));
                        } else {
                            debugModeTimer = 0;
                        }
                    }
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
                Arrays.sort(files, this::compareLevelFiles);
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
            int maxHp;
            boolean alive = true;
            boolean tired = false;
            float tiredTimer = 0f;
            float attackTimer = 3.6f;
            float fireTimer = 1.0f;
            int homeY;
            int tiredY;
            Boss(int x, int y, int w, int h, int hp){
                this.rect = new Rectangle(x, y, w, h);
                this.hp = Math.max(1, hp);
                this.maxHp = this.hp;
                this.homeY = y;
                this.tiredY = y + 70;
            }
        }

        static class Fireball {
            Rectangle rect;
            float x, y, vx, vy;
            float spawnX, spawnY;
            float maxTravel = 520f;
            boolean alive = true;
            Fireball(int x, int y, int w, int h, float vx, float vy){
                this.rect = new Rectangle(x, y, w, h);
                this.x = x; this.y = y;
                this.spawnX = x; this.spawnY = y;
                this.vx = vx; this.vy = vy;
            }
            void update(float dt){
                x += vx * dt;
                y += vy * dt;
                rect.x = (int)x; rect.y = (int)y;
                float dx = x - spawnX;
                float dy = y - spawnY;
                if((dx*dx + dy*dy) > (maxTravel * maxTravel)){
                    alive = false;
                    return;
                }
                if(rect.x < -80 || rect.y < -80 || rect.x > 5000 || rect.y > 2000){
                    alive = false;
                }
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
            Rectangle checkpoint = null;

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
                            case "checkpoint": {
                                String[] s = data.split("\\s+");
                                L.checkpoint = new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3]));
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
                            case "checkpoint": {
                                String[] s = data.split("\\s+");
                                L.checkpoint = new Rectangle(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3]));
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
                if(debugMode && debugPanelBounds.contains(lx, ly)){
                    int rowH = 18;
                    int listY = debugPanelBounds.y + 56;
                    if(ly >= listY){
                        int clicked = (ly - listY) / rowH;
                        int visible = Math.min(18, levels.size());
                        int startIndex = 0;
                        if(debugSelectedLevel >= visible) startIndex = debugSelectedLevel - visible + 1;
                        int idx = startIndex + clicked;
                        if(idx >= 0 && idx < levels.size()){
                            if(idx == debugSelectedLevel){
                                loadLevel(debugSelectedLevel);
                                debugMode = false;
                                debugModeTimer = 0;
                            } else {
                                debugSelectedLevel = idx;
                            }
                            return;
                        }
                    }
                }
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
        // 86 lines before this... we hit 2000 whole lines of code! awesome!
        
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
// this is some big code over here!
