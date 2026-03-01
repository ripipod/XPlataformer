using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Windows.Forms;

namespace XPlataformer
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new GameForm());
        }
    }

    public class GameForm : Form
    {
        private const int W = 800;
        private const int H = 600;

        private GamePanel gamePanel;
        private Timer gameTimer;

        public GameForm()
        {
            Text = "Codename XPlataformer - v1.0B (Beta)";
            ClientSize = new Size(W, H);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            DoubleBuffered = true;
            KeyPreview = true;

            gamePanel = new GamePanel(W, H);
            Controls.Add(gamePanel);
            gamePanel.Dock = DockStyle.Fill;

            gameTimer = new Timer();
            gameTimer.Interval = 16; // ~60 FPS
            gameTimer.Tick += (s, e) =>
            {
                float dt = 0.016f;
                gamePanel.Update(dt);
                gamePanel.Invalidate();
            };
            gameTimer.Start();

            Load += (s, e) => gamePanel.Focus();
            KeyDown += (s, e) => gamePanel.HandleKeyDown(e);
            KeyUp += (s, e) => gamePanel.HandleKeyUp(e);
        }

        protected override void OnClosed(EventArgs e)
        {
            gameTimer?.Stop();
            gameTimer?.Dispose();
            base.OnClosed(e);
        }
    }

    public class GamePanel : Panel
    {
        private int W, H;
        private Rectangle player;
        private float posX, posY;
        private float vx = 0, vy = 0;
        private bool left = false, right = false, jump = false;
        private bool onGround = false;

        private List<Rectangle> platforms = new List<Rectangle>();
        private List<Triangle> killers = new List<Triangle>();
        private Rectangle goal;

        private List<Level> levels = new List<Level>();
        private int currentLevel = 0;
        private int attemptCount = 1;

        private bool showingMessage = false;
        private int messageTimer = 0;
        private string messageText = "";
        private int pendingNextLevel = -1;

        public GamePanel(int w, int h)
        {
            W = w;
            H = h;
            BackColor = Color.FromArgb(30, 30, 40);
            DoubleBuffered = true;

            LoadLevels();
            LoadLevel(0);
        }

        public void HandleKeyDown(KeyEventArgs e)
        {
            switch (e.KeyCode)
            {
                case Keys.Left:
                case Keys.A:
                    left = true;
                    break;
                case Keys.Right:
                case Keys.D:
                    right = true;
                    break;
                case Keys.Up:
                case Keys.W:
                case Keys.Space:
                    jump = true;
                    break;
            }
            e.Handled = true;
        }

        public void HandleKeyUp(KeyEventArgs e)
        {
            switch (e.KeyCode)
            {
                case Keys.Left:
                case Keys.A:
                    left = false;
                    break;
                case Keys.Right:
                case Keys.D:
                    right = false;
                    break;
                case Keys.Up:
                case Keys.W:
                case Keys.Space:
                    jump = false;
                    break;
            }
            e.Handled = true;
        }

        private void LoadLevels()
        {
            string baseDir = "levels";
            if (!Directory.Exists(baseDir))
            {
                Directory.CreateDirectory(baseDir);
                CreateDefaultLevels(baseDir);
            }

            Level l1 = Level.LoadFromFile(Path.Combine(baseDir, "level1.txt"));
            if (l1 != null && !string.IsNullOrEmpty(l1.Name))
                levels.Add(l1);

            Level l2 = Level.LoadFromFile(Path.Combine(baseDir, "level2.txt"));
            if (l2 != null && !string.IsNullOrEmpty(l2.Name))
                levels.Add(l2);

            if (levels.Count == 0)
            {
                levels.Add(Level.DefaultLevel1());
                levels.Add(Level.DefaultLevel2());
            }
        }

        private void CreateDefaultLevels(string baseDir)
        {
            string level1Path = Path.Combine(baseDir, "level1.txt");
            File.WriteAllText(level1Path, @"name: Level 1-1
start: 50 400
platform: 0 550 800 50
platform: 150 470 120 20
platform: 320 380 100 20
platform: 480 340 140 20
platform: 660 260 100 20
killer: 400 550 450 550 425 520
killer: 560 400 610 400 585 370
goal: 720 210 40 40
");

            string level2Path = Path.Combine(baseDir, "level2.txt");
            File.WriteAllText(level2Path, @"name: Level 1-2
start: 50 400
platform: 0 550 800 50
platform: 120 470 100 20
platform: 260 420 80 20
platform: 420 360 120 20
platform: 600 300 120 20
killer: 300 550 350 550 325 520
goal: 680 260 40 40
");
        }

        private void LoadLevel(int idx)
        {
            if (idx < 0 || idx >= levels.Count) return;

            currentLevel = idx;
            attemptCount = 1;
            Level L = levels[idx];

            posX = L.StartX;
            posY = L.StartY;
            player = new Rectangle(L.StartX, L.StartY, 32, 48);

            platforms.Clear();
            platforms.AddRange(L.Platforms);
            killers.Clear();
            killers.AddRange(L.Killers);
            goal = L.Goal;

            vx = 0;
            vy = 0;
            onGround = false;
        }

        public void Update(float dt)
        {
            if (left) vx = -180f;
            else if (right) vx = 180f;
            else vx = 0f;

            if (jump && onGround)
            {
                vy = -520f;
                onGround = false;
            }

            vy += 1200f * dt;

            posX += vx * dt;
            posY += vy * dt;

            player.X = (int)Math.Max(0, posX);
            if (player.X + player.Width > W)
                player.X = W - player.Width;
            posX = player.X;
            player.Y = (int)posY;

            onGround = false;
            foreach (Rectangle p in platforms)
            {
                if (player.IntersectsWith(p))
                {
                    Rectangle inter = Rectangle.Intersect(player, p);

                    if (inter.Height < inter.Width)
                    {
                        if (player.Y < p.Y)
                        {
                            player.Y = p.Y - player.Height;
                            posY = player.Y;
                            vy = 0;
                            onGround = true;
                        }
                        else
                        {
                            player.Y = p.Y + p.Height;
                            posY = player.Y;
                            vy = 0;
                        }
                    }
                    else
                    {
                        if (player.X < p.X)
                        {
                            player.X = p.X - player.Width;
                            posX = player.X;
                        }
                        else
                        {
                            player.X = p.X + p.Width;
                            posX = player.X;
                        }
                    }
                }
            }

            foreach (Triangle killer in killers)
            {
                if (IsPlayerInTriangle(killer))
                {
                    attemptCount++;
                    Level L = levels[currentLevel];
                    posX = L.StartX;
                    posY = L.StartY;
                    vy = 0;
                    vx = 0;
                    player = new Rectangle(L.StartX, L.StartY, 32, 48);
                }
            }

            if (player.IntersectsWith(goal) && !showingMessage)
            {
                int next = currentLevel + 1;
                bool wrapped = false;
                if (next >= levels.Count)
                {
                    wrapped = true;
                    next = 0;
                }
                showingMessage = true;
                messageTimer = 84;
                messageText = "Level Complete!";
                if (wrapped) messageText += " (looping to first level)";
                pendingNextLevel = next;
            }

            if (showingMessage)
            {
                messageTimer--;
                if (messageTimer <= 0)
                {
                    showingMessage = false;
                    if (pendingNextLevel >= 0)
                    {
                        LoadLevel(pendingNextLevel);
                        pendingNextLevel = -1;
                    }
                }
            }
        }

        private bool IsPlayerInTriangle(Triangle tri)
        {
            Point[] corners = new Point[]
            {
                new Point(player.X, player.Y),
                new Point(player.X + player.Width, player.Y),
                new Point(player.X, player.Y + player.Height),
                new Point(player.X + player.Width, player.Y + player.Height)
            };

            foreach (Point p in corners)
            {
                if (PointInTriangle(p.X, p.Y, tri))
                    return true;
            }
            return false;
        }

        private bool PointInTriangle(float x, float y, Triangle tri)
        {
            float Sign(float px, float py, float ax, float ay, float bx, float by)
            {
                return (px - bx) * (ay - by) - (ax - bx) * (py - by);
            }

            float d1 = Sign(x, y, tri.X1, tri.Y1, tri.X2, tri.Y2);
            float d2 = Sign(x, y, tri.X2, tri.Y2, tri.X3, tri.Y3);
            float d3 = Sign(x, y, tri.X3, tri.Y3, tri.X1, tri.Y1);

            bool hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
            bool hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);

            return !(hasNeg && hasPos);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);

            // Draw platforms
            using (Brush brushGray = new SolidBrush(Color.LightGray))
            {
                foreach (Rectangle p in platforms)
                    e.Graphics.FillRectangle(brushGray, p);
            }

            // Draw killers
            using (Brush brushRed = new SolidBrush(Color.Red))
            {
                foreach (Triangle killer in killers)
                {
                    Point[] pts = new Point[] { 
                        new Point((int)killer.X1, (int)killer.Y1),
                        new Point((int)killer.X2, (int)killer.Y2),
                        new Point((int)killer.X3, (int)killer.Y3)
                    };
                    e.Graphics.FillPolygon(brushRed, pts);
                }
            }

            // Draw goal
            using (Brush brushGreen = new SolidBrush(Color.Green))
                e.Graphics.FillRectangle(brushGreen, goal);

            // Draw player
            using (Brush brushCyan = new SolidBrush(Color.Cyan))
                e.Graphics.FillRectangle(brushCyan, player);

            // Draw HUD
            using (Font font = new Font("Arial", 10))
            {
                e.Graphics.DrawString("Controls: Arrow/A/D to move, W/Up/Space to jump", font, Brushes.White, 10, 20);
                string lvlName = currentLevel < levels.Count ? levels[currentLevel].Name : "-";
                e.Graphics.DrawString($"Level: {lvlName}", font, Brushes.White, 10, 40);
                e.Graphics.DrawString($"Attempt Number: {attemptCount}", font, Brushes.White, 10, 60);

                if (showingMessage)
                {
                    using (Font boldFont = new Font("Arial", 14, FontStyle.Bold))
                    {
                        SizeF textSize = e.Graphics.MeasureString(messageText, boldFont);
                        float mx = (W - textSize.Width) / 2;
                        float my = 100;
                        e.Graphics.FillRectangle(new SolidBrush(Color.FromArgb(160, 0, 0, 0)), 
                            mx - 8, my - 18, textSize.Width + 16, 28);
                        e.Graphics.DrawString(messageText, boldFont, Brushes.White, mx, my);
                    }
                }
            }
        }
    }

    public struct Triangle
    {
        public float X1, Y1, X2, Y2, X3, Y3;

        public Triangle(float x1, float y1, float x2, float y2, float x3, float y3)
        {
            X1 = x1; Y1 = y1; X2 = x2; Y2 = y2; X3 = x3; Y3 = y3;
        }
    }

    public class Level
    {
        public string Name { get; set; }
        public int StartX { get; set; }
        public int StartY { get; set; }
        public List<Rectangle> Platforms { get; set; } = new List<Rectangle>();
        public List<Triangle> Killers { get; set; } = new List<Triangle>();
        public Rectangle Goal { get; set; }

        public static Level DefaultLevel1()
        {
            Level L = new Level();
            L.Name = "Level 1-1";
            L.StartX = 50;
            L.StartY = 400;
            L.Platforms.Add(new Rectangle(0, 550, 800, 50));
            L.Platforms.Add(new Rectangle(150, 470, 120, 20));
            L.Platforms.Add(new Rectangle(320, 380, 100, 20));
            L.Platforms.Add(new Rectangle(480, 340, 140, 20));
            L.Platforms.Add(new Rectangle(660, 260, 100, 20));
            L.Killers.Add(new Triangle(400, 550, 450, 550, 425, 520));
            L.Killers.Add(new Triangle(560, 400, 610, 400, 585, 370));
            L.Goal = new Rectangle(720, 210, 40, 40);
            return L;
        }

        public static Level DefaultLevel2()
        {
            Level L = new Level();
            L.Name = "Level 1-2";
            L.StartX = 50;
            L.StartY = 400;
            L.Platforms.Add(new Rectangle(0, 550, 800, 50));
            L.Platforms.Add(new Rectangle(120, 470, 100, 20));
            L.Platforms.Add(new Rectangle(260, 420, 80, 20));
            L.Platforms.Add(new Rectangle(420, 360, 120, 20));
            L.Platforms.Add(new Rectangle(600, 300, 120, 20));
            L.Killers.Add(new Triangle(300, 550, 350, 550, 325, 520));
            L.Goal = new Rectangle(680, 260, 40, 40);
            return L;
        }

        public static Level LoadFromFile(string filename)
        {
            Level L = new Level();
            if (!File.Exists(filename))
                return L;

            string[] lines = File.ReadAllLines(filename);
            foreach (string line in lines)
            {
                string trimmedLine = line.Trim();
                if (string.IsNullOrEmpty(trimmedLine) || trimmedLine.StartsWith("#"))
                    continue;

                int colonPos = trimmedLine.IndexOf(':');
                if (colonPos < 0) continue;

                string key = trimmedLine.Substring(0, colonPos).Trim().ToLower();
                string data = trimmedLine.Substring(colonPos + 1).Trim();
                string[] parts = data.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);

                switch (key)
                {
                    case "name":
                        L.Name = data;
                        break;
                    case "start":
                        if (parts.Length >= 2 && int.TryParse(parts[0], out int sx) && int.TryParse(parts[1], out int sy))
                        {
                            L.StartX = sx;
                            L.StartY = sy;
                        }
                        break;
                    case "platform":
                        if (parts.Length >= 4 && int.TryParse(parts[0], out int px) && int.TryParse(parts[1], out int py) &&
                            int.TryParse(parts[2], out int pw) && int.TryParse(parts[3], out int ph))
                        {
                            L.Platforms.Add(new Rectangle(px, py, pw, ph));
                        }
                        break;
                    case "killer":
                        if (parts.Length >= 6 && 
                            float.TryParse(parts[0], out float x1) && float.TryParse(parts[1], out float y1) &&
                            float.TryParse(parts[2], out float x2) && float.TryParse(parts[3], out float y2) &&
                            float.TryParse(parts[4], out float x3) && float.TryParse(parts[5], out float y3))
                        {
                            L.Killers.Add(new Triangle(x1, y1, x2, y2, x3, y3));
                        }
                        break;
                    case "goal":
                        if (parts.Length >= 4 && int.TryParse(parts[0], out int gx) && int.TryParse(parts[1], out int gy) &&
                            int.TryParse(parts[2], out int gw) && int.TryParse(parts[3], out int gh))
                        {
                            L.Goal = new Rectangle(gx, gy, gw, gh);
                        }
                        break;
                }
            }
            return L;
        }
    }
}
