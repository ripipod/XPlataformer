package src.com.codename.xplataformer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
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
        JFrame frame = new JFrame("Codename XPlataformer - v1.0B (Beta)");
            // try to load an `icon.png` from the project root and use it as the window icon
            try{
                java.awt.Image icon = ImageIO.read(new File("icon.png"));
                if(icon != null) frame.setIconImage(icon);
            }catch(Exception ignored){}
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        GamePanel panel = new GamePanel(800, 600);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        panel.start();
    }

    static class GamePanel extends JPanel implements Runnable, KeyListener {
        final int W, H;
        Thread thread;
        Rectangle player;
        // precise positions and velocities
        float posX, posY;
        float vx=0, vy=0;
        boolean left, right, jump;
        boolean onGround = false;

        ArrayList<Rectangle> platforms = new ArrayList<>();
        ArrayList<Polygon> killers = new ArrayList<>();
        Rectangle goal;
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

        public GamePanel(int w, int h){
            W = w; H = h;
            setPreferredSize(new Dimension(W,H));
            setFocusable(true);
            addKeyListener(this);
            loadLevels();
            loadLevel(0);
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
            SwingUtilities.invokeLater(() -> { showingMessage=true; messageText = "Loaded " + got + " custom level(s) from " + pkgFile.getName(); messageTimer=120; });
        }
        void loadLevels(){
            // try to load level files from levels/ folder
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
        }

        void createDefaultLevels(String base){
            try{
                File l1 = new File(base+"level1.txt");
                File l2 = new File(base+"level2.txt");
                try(PrintWriter w = new PrintWriter(l1)){
                    w.println("name: Level 1-1");
                    w.println("start: 50 400");
                    w.println("platform: 0 550 800 50");
                    w.println("platform: 150 470 120 20");
                    w.println("platform: 320 380 100 20");
                    w.println("platform: 480 340 140 20");
                    w.println("platform: 660 260 100 20");
                    w.println("killer: 400 550 450 550 425 520");
                    w.println("killer: 560 400 610 400 585 370");
                    w.println("goal: 720 210 40 40");
                }
                try(PrintWriter w = new PrintWriter(l2)){
                    w.println("name: Level 1-2");
                    w.println("start: 50 400");
                    w.println("platform: 0 550 800 50");
                    w.println("platform: 120 470 100 20");
                    w.println("platform: 260 420 80 20");
                    w.println("platform: 420 360 120 20");
                    w.println("platform: 600 300 120 20");
                    w.println("killer: 300 550 350 550 325 520");
                    w.println("goal: 680 260 40 40");
                }
            }catch(IOException ignored){}
        }

        void loadLevel(int idx){
            if(idx < 0 || idx >= levels.size()) return;
            currentLevel = idx;
            attemptCount = 1;
            Level L = levels.get(idx);
            platforms.clear(); killers.clear();
            platforms.addAll(L.platforms);
            killers.addAll(L.killers);
            goal = L.goal;
            player = new Rectangle(L.startX, L.startY, 32, 48);
            posX = player.x; posY = player.y;
            // reset motion state so player doesn't get moved away by leftover velocity
            vx = 0f; vy = 0f; onGround = false;
            requestFocusInWindow();
            debugFrames = 120;
            System.out.println("[DEBUG] loadLevel: " + L.name + " start=" + L.startX + "," + L.startY + " goal=" + L.goal);
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

            // collision with platforms
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

            // goal
            if(player.intersects(goal) && !showingMessage){
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

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            // background
            g2.setColor(new Color(30,30,40));
            g2.fillRect(0,0,W,H);

            // platforms
            g2.setColor(Color.LIGHT_GRAY);
            for(Rectangle p : platforms) g2.fill(p);

            // killers
            g2.setColor(Color.RED);
            for(Polygon t : killers) g2.fill(t);

            // goal
            g2.setColor(Color.GREEN);
            g2.fill(goal);

            // player
            g2.setColor(Color.CYAN);
            g2.fill(player);

            // HUD
            g2.setColor(Color.WHITE);
            // controls
            g2.drawString("Controls: Arrow/A/D to move, W/Up/Space to jump", 10, 20);
            // level & attempt
            String lvlName = levels.size() > 0 ? levels.get(currentLevel).name : "-";
            g2.drawString("Level: " + lvlName, 10, 40);
            g2.drawString("Attempt Number: " + attemptCount, 10, 60);
            // show message if set
            if(showingMessage){
                g2.setFont(g2.getFont().deriveFont(Font.BOLD, 18f));
                FontMetrics fm = g2.getFontMetrics();
                int mw = fm.stringWidth(messageText);
                int mx = (W - mw) / 2;
                int my = 100;
                g2.setColor(new Color(0,0,0,160));
                g2.fillRoundRect(mx-8, my-18, mw+16, 28, 8, 8);
                g2.setColor(Color.WHITE);
                g2.drawString(messageText, mx, my);
            }
        }

        // KeyListener
        @Override public void keyTyped(KeyEvent e) {}
        @Override public void keyPressed(KeyEvent e) {
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
            }
        }
        
        // Level data structure and loader
        static class Level{
            String name = "";
            int startX = 50, startY = 400;
            ArrayList<Rectangle> platforms = new ArrayList<>();
            ArrayList<Polygon> killers = new ArrayList<>();
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
    }
}
