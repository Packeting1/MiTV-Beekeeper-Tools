using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace MiTvMic
{
    // 主界面。Ui.xaml 运行时加载（csc 不能编译 XAML），控件用 FindName 取、事件在代码里挂。
    sealed class Ui
    {
        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        sealed class Row : INotifyPropertyChanged
        {
            string state = "";

            public string Name { get; set; }
            public string Ip { get; set; }

            public string State
            {
                get { return state; }
                set { if (state != value) { state = value; Raise("State"); } }
            }

            public event PropertyChangedEventHandler PropertyChanged;

            void Raise(string name)
            {
                PropertyChangedEventHandler h = PropertyChanged;
                if (h != null) h(this, new PropertyChangedEventArgs(name));
            }
        }

        sealed class Snapshot
        {
            public bool Running;
            public Card Card;
            public string Device;
            public string Link;
            public MicState Mic;
            public bool Autostart;

            public bool Connected
            {
                get { return Running && Link.StartsWith("已连接"); }
            }
        }

        static readonly Brush DotOk = new SolidColorBrush(Color.FromRgb(0x3F, 0xD1, 0x8B));
        static readonly Brush DotWarn = new SolidColorBrush(Color.FromRgb(0xF0, 0xB4, 0x29));

        public readonly Window Window;

        readonly ListBox foundList, pairedList;
        readonly TextBox manualBox;
        readonly Button scanBtn, pairBtn, procBtn, cableBtn, wideBtn, micBtn;
        readonly CheckBox autoBox;
        readonly ContextMenu pairedMenu = new ContextMenu();
        Row rightRow;
        readonly Ellipse dot;
        readonly TextBlock svcText, linkText, noticeText, busyText;
        readonly Border notice, busyMask;
        readonly FrameworkElement root, pageAdd, pagePick, pageAbout;
        readonly RadioButton navAdd, navPick, navAbout;
        readonly TextBlock aboutText;
        readonly System.Windows.Documents.Run updateText;
        readonly Button updateBtn;
        Update.Info found;
        bool checking;
        readonly TranslateTransform noticeShift = new TranslateTransform();
        int noticeToken;

        readonly List<Row> foundRows = new List<Row>();
        readonly List<Row> pairedRows = new List<Row>();
        readonly DispatcherTimer timer, noticeTimer;

        Snapshot snap = new Snapshot();
        bool shown, switching, masked;
        DateTime switchStart;
        volatile bool probing, scanning, pairing, busy, closing;
        bool? dotRunning;

        public Ui()
        {
            using (Stream s = typeof(Ui).Assembly.GetManifestResourceStream("Ui.xaml"))
            {
                if (s == null) throw new InvalidOperationException("界面资源 Ui.xaml 未打包进 exe");
                Window = (Window)XamlReader.Load(s);
            }

            foundList = Get<ListBox>("FoundList");
            manualBox = Get<TextBox>("ManualBox");
            scanBtn = Get<Button>("ScanBtn");
            pairBtn = Get<Button>("PairBtn");
            pairedList = Get<ListBox>("PairedList");
            dot = Get<Ellipse>("Dot");
            svcText = Get<TextBlock>("SvcText");
            cableBtn = Get<Button>("CableBtn");
            wideBtn = Get<Button>("WideBtn");
            micBtn = Get<Button>("MicBtn");
            procBtn = Get<Button>("ProcBtn");
            autoBox = Get<CheckBox>("AutoBox");
            notice = Get<Border>("Notice");
            noticeText = Get<TextBlock>("NoticeText");
            linkText = Get<TextBlock>("LinkText");
            root = Get<FrameworkElement>("Root");
            pageAdd = Get<FrameworkElement>("PageAdd");
            pagePick = Get<FrameworkElement>("PagePick");
            pageAbout = Get<FrameworkElement>("PageAbout");
            navAdd = Get<RadioButton>("NavAdd");
            navPick = Get<RadioButton>("NavPick");
            navAbout = Get<RadioButton>("NavAbout");
            aboutText = Get<TextBlock>("AboutText");
            updateText = Get<System.Windows.Documents.Run>("UpdateText");
            updateBtn = Get<Button>("UpdateBtn");
            busyMask = Get<Border>("Busy");
            notice.RenderTransform = noticeShift;
            busyText = Get<TextBlock>("BusyText");

            foundList.SelectionChanged += delegate { UpdateButtons(); };
            scanBtn.Click += OnScan;
            pairBtn.Click += OnPair;
            procBtn.Click += OnProcClick;
            autoBox.Click += OnAutoClick;
            cableBtn.Click += delegate { RunOp(Op.Driver); };
            wideBtn.Click += delegate { RunOp(Op.DisableWide); };
            micBtn.Click += OnSetMic;
            navAdd.Checked += delegate { ShowPage(pageAdd); };
            navPick.Checked += delegate { ShowPage(pagePick); };
            navAbout.Checked += delegate { ShowPage(pageAbout); };
            Get<System.Windows.Documents.Hyperlink>("RepoLink").RequestNavigate += OnOpenLink;
            Get<System.Windows.Documents.Hyperlink>("CableLink").RequestNavigate += OnOpenLink;
            Get<System.Windows.Documents.Hyperlink>("DirLink").Click += OnOpenDataDir;
            updateBtn.Click += OnUpdateClick;
            Get<System.Windows.Documents.Run>("DirText").Text = Env.DataDir;
            Get<Button>("MinBtn").Click += delegate { Window.WindowState = WindowState.Minimized; };
            Get<Button>("CloseBtn").Click += delegate { Window.Close(); };
            pairedList.ContextMenu = pairedMenu;
            pairedList.PreviewMouseRightButtonDown += OnPairedRightDown;
            pairedList.ContextMenuOpening += OnPairedMenuOpening;
            Window.Loaded += OnLoaded;
            Window.Closing += delegate { closing = true; };
            // 遮罩只挡鼠标，键盘也要挡
            Window.PreviewKeyDown += delegate (object s, System.Windows.Input.KeyEventArgs ke) { if (masked) ke.Handled = true; };

            noticeTimer = new DispatcherTimer();
            noticeTimer.Interval = TimeSpan.FromSeconds(5);
            noticeTimer.Tick += delegate { noticeTimer.Stop(); HideNotice(); };

            timer = new DispatcherTimer();
            timer.Interval = TimeSpan.FromSeconds(2);
            timer.Tick += delegate { Refresh(); };
            timer.Start();
        }

        T Get<T>(string name) where T : class
        {
            T t = Window.FindName(name) as T;
            if (t == null) throw new InvalidOperationException("界面元素缺失：" + name);
            return t;
        }

        void OnLoaded(object sender, RoutedEventArgs e)
        {
            Refresh();
            if (shown) return;
            shown = true;
            // 无边框窗口在 Win11 默认直角；33 = 圆角偏好，旧系统不认，忽略返回值
            int round = 2;
            DwmSetWindowAttribute(new WindowInteropHelper(Window).Handle, 33, ref round, 4);
            root.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(200)));
            // 首次显示时置于所有窗口之上；Topmost 开关一次即可，不保持置顶
            Window.Topmost = true;
            Window.Activate();
            Window.Topmost = false;
            if (Pairings.All().Count == 0) navAdd.IsChecked = true;
            Background.Start();
            CheckUpdate(false);
            OfferFirewall(false);
        }

        void OfferFirewall(bool force)
        {
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool ask = !Setup.FirewallReady() && (force || !Setup.FirewallDeclined());
                if (!ask || closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    if (busy || masked) return;
                    if (Dialog.Ask(Window, "允许扫描电视",
                        "扫描电视时，Windows 防火墙可能拦截电视的回应。\n是否添加允许 MiTvMic 的防火墙规则？\n添加需要管理员权限，系统会弹出确认窗口。不添加时，也可以手动输入电视 IP。",
                        "添加", "暂不"))
                    {
                        RunOp(Op.Firewall);
                    }
                    else
                    {
                        Setup.DeclineFirewall();
                    }
                });
            });
        }

        void OnAutoClick(object sender, RoutedEventArgs e)
        {
            try
            {
                Installer.SetAutostart(autoBox.IsChecked == true);
            }
            catch (Exception ex)
            {
                ShowNotice("设置开机自启失败：" + ex.Message, false);
            }
            Refresh();
        }

        void OnProcClick(object sender, RoutedEventArgs e)
        {
            if (busy) return;
            busy = true;
            UpdateButtons();
            bool stop = Background.Running;
            ShowMask(stop ? "正在停止后台进程…" : "正在启动后台进程…");
            new Thread(delegate ()
            {
                string error = null;
                try
                {
                    if (stop)
                    {
                        if (!Background.Stop(5000)) error = "后台进程无响应";
                    }
                    else
                    {
                        Background.Start();
                        for (int i = 0; i < 30 && !Background.Running; i++) Thread.Sleep(100);
                    }
                }
                catch (Exception ex)
                {
                    error = ex.Message;
                }
                if (closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    busy = false;
                    HideMask();
                    if (error != null) ShowNotice("操作失败：" + error, false);
                    Refresh();
                });
            }).Start();
        }

        void OnUpdateClick(object sender, RoutedEventArgs e)
        {
            if (found != null) DownloadUpdate();
            else CheckUpdate(true);
        }

        void CheckUpdate(bool manual)
        {
            if (checking || busy) return;
            checking = true;
            updateText.Text = "正在检查…";
            updateBtn.IsEnabled = false;
            new Thread(delegate ()
            {
                Update.Info info = null;
                string error = null;
                try { info = Update.Latest(); }
                catch (Exception ex)
                {
                    error = ex.Message;
                    Log.Write("检查更新失败：" + ex.Message);
                }
                if (closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    checking = false;
                    updateBtn.IsEnabled = true;
                    if (error != null)
                    {
                        updateText.Text = "检查失败";
                        if (manual) ShowNotice("检查更新失败：" + error, false);
                    }
                    else if (info != null && info.Version > Update.Current)
                    {
                        found = info;
                        updateText.Text = "发现新版本 " + info.Version;
                        updateBtn.Content = "下载并更新";
                        updateBtn.Style = (Style)Window.FindResource("BtnAccent");
                        ShowNotice("发现新版本 " + info.Version + "，可在「关于」页下载更新。", true);
                    }
                    else
                    {
                        updateText.Text = "已是最新版本";
                        if (manual) ShowNotice("当前已是最新版本。", true);
                    }
                });
            }).Start();
        }

        void DownloadUpdate()
        {
            if (busy || found == null) return;
            busy = true;
            UpdateButtons();
            Update.Info info = found;
            string head = "正在下载新版本 " + info.Version + "…";
            ShowMask(head);
            new Thread(delegate ()
            {
                string error = null;
                try
                {
                    string file = Update.TempFile;
                    Update.Download(info, file, delegate (int pct) { Progress(pct > 0 ? head + pct + "%" : head); });
                    Background.Stop(5000);
                    error = Update.Replace(file);
                }
                catch (Exception ex)
                {
                    error = ex.Message;
                    Log.Write("下载更新失败：" + ex.Message);
                }
                if (closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    if (error == null)
                    {
                        try
                        {
                            Update.Restart();
                            closing = true;
                            Application.Current.Shutdown();
                            return;
                        }
                        catch (Exception ex) { error = "无法启动新版本：" + ex.Message; }
                    }
                    busy = false;
                    HideMask();
                    UpdateButtons();
                    Background.Start();
                    ShowNotice("更新失败：" + error, false);
                });
            }).Start();
        }

        static void OnOpenDataDir(object sender, RoutedEventArgs e)
        {
            try
            {
                Directory.CreateDirectory(Env.DataDir);
                System.Diagnostics.Process.Start("explorer.exe", "\"" + Env.DataDir + "\"");
            }
            catch (Exception ex) { Log.Write("打开目录失败：" + ex.Message); }
        }

        static void OnOpenLink(object sender, System.Windows.Navigation.RequestNavigateEventArgs e)
        {
            try { System.Diagnostics.Process.Start(e.Uri.AbsoluteUri); }
            catch (Exception ex) { Log.Write("打开链接失败：" + ex.Message); }
            e.Handled = true;
        }

        // 用 Hidden 不用 Collapsed，窗口高度才不随页面变
        void ShowPage(FrameworkElement page)
        {
            foreach (FrameworkElement p in new[] { pageAdd, pagePick, pageAbout })
            {
                p.Visibility = p == page ? Visibility.Visible : Visibility.Hidden;
            }
        }

        // 读缓存的在界面线程做，慢查询丢后台
        void Refresh()
        {
            if (closing) return;
            SyncLists();
            if (probing) return;
            probing = true;
            ThreadPool.QueueUserWorkItem(delegate
            {
                Snapshot s = new Snapshot();
                try
                {
                    s.Device = Config.Device;
                    s.Running = Background.Running;
                    s.Autostart = Installer.AutostartEnabled();
                    s.Card = Cards.Probe(s.Device);
                    s.Link = MiTvMic.Link.Get();
                    s.Mic = s.Connected ? DefaultMic.State(DefaultMic.Device) : MicState.Unavailable;
                }
                catch (Exception ex)
                {
                    s = null;
                    Log.Write("刷新状态失败：" + ex.Message);
                }
                probing = false;
                if (s == null || closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate { Apply(s); });
            });
        }

        void SyncLists()
        {
            string host = Config.Host;
            List<Pairings.Item> list = Pairings.All();
            bool same = pairedRows.Count == list.Count;
            for (int i = 0; same && i < list.Count; i++)
            {
                same = pairedRows[i].Ip == list[i].Ip && pairedRows[i].Name == NameOf(list[i].Name);
            }
            if (!same)
            {
                pairedRows.Clear();
                foreach (Pairings.Item p in list)
                {
                    Row r = new Row();
                    r.Name = NameOf(p.Name);
                    r.Ip = p.Ip;
                    pairedRows.Add(r);
                }
                pairedList.ItemsSource = null;
                pairedList.ItemsSource = pairedRows;
            }
            foreach (Row r in pairedRows)
            {
                r.State = r.Ip != host ? "" : Config.Enabled ? "使用中" : "已关闭";
            }
            foreach (Row r in foundRows)
            {
                r.State = list.Exists(delegate (Pairings.Item p) { return p.Ip == r.Ip; }) ? "已配对" : "";
            }
            UpdateButtons();
        }

        static string NameOf(string name)
        {
            return name.Length > 0 ? name : "电视";
        }

        void Apply(Snapshot s)
        {
            snap = s;
            Card card = s.Card;
            svcText.Text = "后台进程：" + (s.Running ? "运行中" : "已停止") + "　虚拟声卡 " + s.Device + "："
                + card.StateText + (card.Ready && card.WaveIsWide ? "（16 声道端）" : "");
            SetDot(s.Running);

            cableBtn.Visibility = Show(card.State == CardState.Missing);
            wideBtn.Visibility = Show(card.WideEnabled);
            micBtn.Visibility = Show(s.Mic == MicState.NotDefault);
            procBtn.Content = s.Running ? "停止后台进程" : "启动后台进程";
            autoBox.IsChecked = s.Autostart;

            linkText.Text = LinkLine(s);
            linkText.Visibility = Show(linkText.Text.Length > 0);
            aboutText.Text = "当前版本：" + Update.Format(Update.Current);
            UpdateButtons();
            CheckSwitch(s);
        }

        // 优先级：声卡原因 > 后台进程状态 > 后台进程上报的连接状态
        static string LinkLine(Snapshot s)
        {
            if (!s.Card.Ready) return s.Card.Hint(s.Device);
            if (!s.Running) return "";
            return s.Link.Length > 0 ? s.Link : "等待后台进程报告状态…";
        }

        static Visibility Show(bool on)
        {
            return on ? Visibility.Visible : Visibility.Collapsed;
        }

        void SetDot(bool running)
        {
            // 状态没变就别重置动画，否则每 2 秒闪一下
            if (dotRunning == running) return;
            dotRunning = running;
            dot.BeginAnimation(UIElement.OpacityProperty, null);
            dot.Opacity = 1;
            if (running)
            {
                dot.Fill = DotOk;
                DoubleAnimation a = new DoubleAnimation(1, 0.3, TimeSpan.FromMilliseconds(1100));
                a.AutoReverse = true;
                a.RepeatBehavior = RepeatBehavior.Forever;
                dot.BeginAnimation(UIElement.OpacityProperty, a);
            }
            else
            {
                dot.Fill = DotWarn;
            }
        }

        void UpdateButtons()
        {
            pairBtn.IsEnabled = !pairing && foundList.SelectedItem != null;
            scanBtn.IsEnabled = !scanning;
            procBtn.IsEnabled = cableBtn.IsEnabled = wideBtn.IsEnabled = micBtn.IsEnabled = !busy;
        }

        bool Write(Action change)
        {
            try
            {
                change();
                return true;
            }
            catch (UnauthorizedAccessException)
            {
                ShowNotice("无权修改配置：" + Env.DataDir, false);
            }
            catch (IOException ex)
            {
                ShowNotice("配置写入失败：" + ex.Message, false);
            }
            return false;
        }

        // 启用 / 停用 / 删除后后台进程要重连，等连接结果出来前盖住窗口，免得点太快来回切
        const double SwitchTimeout = 10;

        void BeginSwitch(string text)
        {
            if (!snap.Running) return;
            switching = true;
            switchStart = DateTime.Now;
            ShowMask(text);
            timer.Interval = TimeSpan.FromMilliseconds(500);
        }

        void ShowMask(string text)
        {
            masked = true;
            busyText.Text = text;
            busyMask.Visibility = Visibility.Visible;
            busyMask.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(150)));
        }

        void HideMask()
        {
            masked = false;
            DoubleAnimation fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(150));
            fade.Completed += delegate { if (!masked) busyMask.Visibility = Visibility.Collapsed; };
            busyMask.BeginAnimation(UIElement.OpacityProperty, fade);
        }

        void CheckSwitch(Snapshot s)
        {
            if (!switching) return;
            string link = s.Link;
            string host = Config.Host;
            bool done = false, ok = true;
            string text = link;
            if (!s.Running || !s.Card.Ready)
            {
                done = true;
                ok = false;
                text = s.Card.Ready ? "后台进程未运行，请先启动后台进程" : LinkLine(s);
            }
            else if (!Config.Enabled) done = link == "推流已关闭";
            else if (host.Length == 0) done = link.StartsWith("未选择");
            else if (link.StartsWith("已连接") && link.Contains(" " + host + "，")) done = true;
            else if (link.StartsWith("电视连接失败") || link.StartsWith("电视拒绝"))
            {
                done = true;
                ok = false;
            }
            if (!done && (DateTime.Now - switchStart).TotalSeconds > SwitchTimeout)
            {
                done = true;
                ok = false;
                text = "等待 " + SwitchTimeout + " 秒仍无结果，后台进程可能未响应";
            }
            if (!done) return;
            switching = false;
            HideMask();
            timer.Interval = TimeSpan.FromSeconds(2);
            ShowNotice(text, ok);
        }

        // 右键先选中点到的行；点空白处不弹菜单
        void OnPairedRightDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            DependencyObject d = e.OriginalSource as DependencyObject;
            while (d != null && !(d is ListBoxItem)) d = d is Visual ? VisualTreeHelper.GetParent(d) : null;
            ListBoxItem item = d as ListBoxItem;
            rightRow = item == null ? null : item.DataContext as Row;
            if (item != null) item.IsSelected = true;
        }

        void OnPairedMenuOpening(object sender, ContextMenuEventArgs e)
        {
            Row row = rightRow;
            if (row == null || masked)
            {
                e.Handled = true;
                return;
            }
            bool active = Config.Host == row.Ip && Config.Enabled;
            pairedMenu.Items.Clear();
            pairedMenu.Items.Add(MenuEntry(active ? "停用" : "启用", delegate
            {
                if (active) Stop(); else Use(row);
            }));
            pairedMenu.Items.Add(MenuEntry("删除配对", delegate { Delete(row); }));
        }

        MenuItem MenuEntry(string header, Action click)
        {
            MenuItem mi = new MenuItem();
            mi.Header = header;
            mi.Click += delegate { click(); };
            return mi;
        }

        void Use(Row row)
        {
            bool wrote = Write(delegate
            {
                Config.Set("host", row.Ip);
                Config.Set("enabled", "1");
            });
            SyncLists();
            if (wrote) BeginSwitch("正在连接 " + row.Name + "…");
        }

        void Stop()
        {
            bool wrote = Write(delegate { Config.Set("host", ""); });
            SyncLists();
            if (wrote) BeginSwitch("正在断开连接…");
        }

        void Delete(Row row)
        {
            bool wasHost = Config.Host == row.Ip;
            bool wrote = Write(delegate
            {
                Pairings.Remove(row.Ip);
                if (wasHost) Config.Set("host", "");
            });
            SyncLists();
            if (wrote && wasHost) BeginSwitch("正在断开连接…");
        }

        void OnScan(object sender, RoutedEventArgs e)
        {
            if (scanning) return;
            scanning = true;
            UpdateButtons();
            ShowMask("正在扫描电视…");
            string extra = manualBox.Text.Trim();
            new Thread(delegate ()
            {
                List<Protocol.Tv> tvs = new List<Protocol.Tv>();
                try { tvs = Protocol.Scan(extra); }
                catch (Exception ex) { Log.Write("扫描失败：" + ex.Message); }
                scanning = false;
                if (closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    HideMask();
                    foundRows.Clear();
                    foreach (Protocol.Tv tv in tvs)
                    {
                        Row r = new Row();
                        r.Name = NameOf(tv.Name);
                        r.Ip = tv.Ip.ToString();
                        foundRows.Add(r);
                    }
                    foundList.ItemsSource = null;
                    foundList.ItemsSource = foundRows;
                    if (tvs.Count == 0)
                    {
                        ShowNotice("未找到电视。请确认电视已开启「麦克风推流」，且与电脑处于同一局域网。", false);
                        OfferFirewall(true);
                    }
                    if (tvs.Count > 0) ShowNotice("发现 " + tvs.Count + " 台电视，请选中一台后点击「配对」。", true);
                    SyncLists();
                });
            }).Start();
        }

        void OnPair(object sender, RoutedEventArgs e)
        {
            Row row = foundList.SelectedItem as Row;
            IPAddress ip;
            if (row == null || !IPAddress.TryParse(row.Ip, out ip)) return;
            pairing = true;
            UpdateButtons();
            ShowMask("已向 " + row.Ip + " 发送配对请求\n请在电视上用遥控器选择「允许」（最长等待 60 秒）");
            new Thread(delegate ()
            {
                string error;
                string token = null;
                try { token = Protocol.Pair(ip, delegate { return closing; }, out error); }
                catch (Exception ex) { error = ex.Message; }
                pairing = false;
                if (closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    HideMask();
                    OnPaired(row, token, error);
                });
            }).Start();
        }

        void OnPaired(Row row, string token, string error)
        {
            if (token == null)
            {
                ShowNotice("配对失败：" + error + "。可再次点击「配对」重试。", false);
                return;
            }
            Write(delegate { Pairings.Add(row.Ip, row.Name, token); });
            ShowNotice("已与 " + row.Ip + " 配对。请在「选择电视」页右键该电视，选择「启用」。", true);
            SyncLists();
        }

        void RunOp(Op op)
        {
            if (busy) return;
            busy = true;
            UpdateButtons();
            ShowMask("正在处理…");
            new Thread(delegate ()
            {
                Outcome o;
                try { o = Setup.Run(op, Progress); }
                catch (Exception ex) { o = Outcome.Fail("操作失败：" + ex.Message); }
                if (closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    busy = false;
                    HideMask();
                    ShowNotice(o.Message, o.Ok);
                    Refresh();
                });
            }).Start();
        }

        void OnSetMic(object sender, RoutedEventArgs e)
        {
            if (busy) return;
            busy = true;
            UpdateButtons();
            ShowMask("正在设置默认麦克风…");
            new Thread(delegate ()
            {
                Outcome o = DefaultMic.Set(DefaultMic.Device);
                if (closing) return;
                Window.Dispatcher.BeginInvoke((Action)delegate
                {
                    busy = false;
                    HideMask();
                    ShowNotice(o.Message, o.Ok);
                    Refresh();
                });
            }).Start();
        }

        // 提权在另一个进程里，进度只能靠回调
        void Progress(string text)
        {
            if (closing) return;
            Window.Dispatcher.BeginInvoke((Action)delegate { busyText.Text = text; });
        }

        void ShowNotice(string text, bool ok)
        {
            noticeText.Text = text;
            notice.Background = new SolidColorBrush(ok
                ? Color.FromRgb(0x1B, 0x3A, 0x2A) : Color.FromRgb(0x42, 0x1C, 0x22));
            notice.BorderBrush = new SolidColorBrush(ok
                ? Color.FromRgb(0x2E, 0x7D, 0x4F) : Color.FromRgb(0x8B, 0x2E, 0x36));
            notice.Visibility = Visibility.Visible;
            noticeToken++;
            DoubleAnimation fade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(260));
            DoubleAnimation rise = new DoubleAnimation(20, 0, TimeSpan.FromMilliseconds(320));
            rise.EasingFunction = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.5 };
            notice.BeginAnimation(UIElement.OpacityProperty, fade);
            noticeShift.BeginAnimation(TranslateTransform.YProperty, rise);
            noticeTimer.Stop();
            noticeTimer.Start();
        }

        void HideNotice()
        {
            int token = noticeToken;
            DoubleAnimation fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(450));
            DoubleAnimation sink = new DoubleAnimation(0, 8, TimeSpan.FromMilliseconds(450));
            fade.Completed += delegate { if (token == noticeToken) notice.Visibility = Visibility.Collapsed; };
            notice.BeginAnimation(UIElement.OpacityProperty, fade);
            noticeShift.BeginAnimation(TranslateTransform.YProperty, sink);
        }
    }
}
