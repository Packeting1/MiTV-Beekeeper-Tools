using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Media.Animation;

namespace MiTvMic
{
    // 与主界面同风格的对话框，用来代替系统 MessageBox。没有 Application 时（安装向导阶段）也能显示。
    static class Dialog
    {
        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        public static void Info(Window owner, string title, string message)
        {
            Ask(owner, title, message, "确定", null);
        }

        // 点「是」返回 true；点「否」、按 Esc 或关闭窗口返回 false
        public static bool Ask(Window owner, string title, string message, string yes, string no)
        {
            Window w;
            using (Stream s = typeof(Dialog).Assembly.GetManifestResourceStream("Dialog.xaml"))
            {
                w = (Window)XamlReader.Load(s);
            }
            w.Title = title;
            ((TextBlock)w.FindName("TitleText")).Text = title;
            ((TextBlock)w.FindName("MessageText")).Text = message;
            Button yesBtn = (Button)w.FindName("YesBtn");
            Button noBtn = (Button)w.FindName("NoBtn");
            yesBtn.Content = yes;
            if (no == null) noBtn.Visibility = Visibility.Collapsed;
            else noBtn.Content = no;

            bool result = false;
            yesBtn.Click += delegate { result = true; w.Close(); };
            noBtn.Click += delegate { w.Close(); };
            FrameworkElement root = (FrameworkElement)w.FindName("Root");
            root.MouseLeftButtonDown += delegate
            {
                try { w.DragMove(); }
                catch (InvalidOperationException) { }
            };

            if (owner != null && owner.IsVisible)
            {
                w.Owner = owner;
                w.ShowInTaskbar = false;
                w.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            }
            else
            {
                w.Topmost = true;
                w.WindowStartupLocation = WindowStartupLocation.CenterScreen;
            }
            w.SourceInitialized += delegate
            {
                int round = 2;
                DwmSetWindowAttribute(new WindowInteropHelper(w).Handle, 33, ref round, 4);
            };
            w.Loaded += delegate
            {
                root.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
                yesBtn.Focus();
            };
            w.ShowDialog();
            return result;
        }
    }
}
