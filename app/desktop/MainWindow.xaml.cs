using System.Collections.ObjectModel;
using System.Net;
using DshRemote.Models;
using DshRemote.Services;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;
using Windows.System;

namespace DshRemote;

public sealed partial class MainWindow : Window
{
    private AppSettings _settings = SettingsStore.Load();
    private RemoteApi? _api;
    private readonly ObservableCollection<SessionItem> _sessions = new();
    private readonly Dictionary<string, ApprovalDto> _approvals = new();
    private readonly Dictionary<string, TextBlock> _countdownLabels = new();
    private readonly DispatcherTimer _countdownTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private CancellationTokenSource? _eventsCts;
    private string? _currentSessionId;
    private int _loadGeneration;
    private bool _busy;
    private bool _sending;
    private bool _suppressToggle;

    public MainWindow()
    {
        InitializeComponent();

        AppWindow.Resize(new SizeInt32(1120, 760));
        SessionList.ItemsSource = _sessions;
        ServerLabel.Text = _settings.ServerUrl;
        _countdownTimer.Tick += (_, _) => TickCountdowns();
        _countdownTimer.Start();

        _ = ConnectAsync();

        Closed += (_, _) =>
        {
            _countdownTimer.Stop();
            _eventsCts?.Cancel();
            _api?.Dispose();
        };
    }

    // ---------- 连接 ----------

    private void ShowOffline(bool show, string? message = null)
    {
        OfflineOverlay.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        OfflineError.Text = message ?? string.Empty;
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        Busy.IsActive = busy;
        RefreshButton.IsEnabled = !busy;
        RetryButton.IsEnabled = !busy;
    }

    /// <summary>
    /// The desktop client talks to the plugin on this machine; loopback peers need no pairing.
    /// Only the phone pairs, and its code is shown from the sidebar dialog.
    /// </summary>
    private async Task ConnectAsync()
    {
        SetBusy(true);
        ShowOffline(false);
        try
        {
            _api?.Dispose();
            _api = new RemoteApi(_settings.ServerUrl, null);
            await LoadSessionsAsync();
            ServerLabel.Text = _settings.ServerUrl;
            StartEventStream();
        }
        catch (Exception ex)
        {
            ServerLabel.Text = $"{_settings.ServerUrl} · 未连接";
            ShowOffline(true, $"无法连接插件服务：{ex.Message}");
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void OnRetryClick(object sender, RoutedEventArgs e)
    {
        _ = ConnectAsync();
    }

    // ---------- 会话 ----------

    private async Task LoadSessionsAsync()
    {
        if (_api is null)
        {
            return;
        }

        var sessions = await _api.GetSessionsAsync();
        _sessions.Clear();
        foreach (var dto in sessions.OrderByDescending(session => session.UpdatedAt ?? 0))
        {
            _sessions.Add(new SessionItem
            {
                Id = dto.Id,
                Title = TitleOf(dto),
                Subtitle = $"{FormatWhen(dto.UpdatedAt)} · {(dto.Running ? "运行中" : "空闲")}",
                Running = dto.Running,
            });
        }

        EmptyState.Visibility = _sessions.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (_sessions.Count > 0 && SessionList.SelectedIndex < 0)
        {
            SessionList.SelectedIndex = 0;
        }
    }

    private async void OnRefreshClick(object sender, RoutedEventArgs e)
    {
        if (_busy || _api is null)
        {
            return;
        }

        SetBusy(true);
        try
        {
            await LoadSessionsAsync();
            ServerLabel.Text = _settings.ServerUrl;
        }
        catch (Exception ex)
        {
            ServerLabel.Text = $"{_settings.ServerUrl} · 刷新失败：{ex.Message}";
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async void OnSessionSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SessionList.SelectedItem is not SessionItem item || _api is null)
        {
            EmptyState.Visibility = Visibility.Visible;
            DetailPanel.Visibility = Visibility.Collapsed;
            return;
        }

        EmptyState.Visibility = Visibility.Collapsed;
        DetailPanel.Visibility = Visibility.Visible;
        DetailTitle.Text = item.Title;
        DetailPath.Text = item.Id;
        DetailStatus.Text = item.Running ? "状态：运行中" : "状态：空闲";
        _currentSessionId = item.Id;
        ComposerBox.IsEnabled = true;
        SendButton.IsEnabled = true;

        await LoadMessagesAsync(item.Id);
        await LoadPolicyAsync(item.Id);
        RenderApprovals();
    }

    private async Task LoadMessagesAsync(string sessionId)
    {
        var generation = ++_loadGeneration;
        MessagePanel.Children.Clear();

        try
        {
            var history = await _api!.GetMessagesAsync(sessionId);
            if (generation != _loadGeneration)
            {
                return;
            }

            foreach (var message in history.Messages)
            {
                AddBubble(message);
            }

            DetailStatus.Text = $"{(SessionList.SelectedItem as SessionItem)?.Status ?? ""} · {history.Messages.Count} 条消息";
            ScrollToBottom();
        }
        catch (Exception ex)
        {
            if (generation == _loadGeneration)
            {
                DetailStatus.Text = $"消息加载失败：{ex.Message}";
            }
        }
    }

    // ---------- 审批策略 ----------

    private async Task LoadPolicyAsync(string sessionId)
    {
        if (_api is null)
        {
            return;
        }

        try
        {
            var policy = await _api.GetApprovalPolicyAsync(sessionId);
            _suppressToggle = true;
            ApprovalToggle.IsOn = policy == "ask";
            _suppressToggle = false;
        }
        catch (Exception ex)
        {
            DetailStatus.Text = $"审批策略读取失败：{ex.Message}";
        }
    }

    private async void OnApprovalToggled(object sender, RoutedEventArgs e)
    {
        if (_suppressToggle || _api is null || _currentSessionId is null)
        {
            return;
        }

        var desired = ApprovalToggle.IsOn ? "ask" : "never";
        try
        {
            var applied = await _api.SetApprovalPolicyAsync(_currentSessionId, desired);
            _suppressToggle = true;
            ApprovalToggle.IsOn = applied == "ask";
            _suppressToggle = false;
        }
        catch (Exception ex)
        {
            DetailStatus.Text = $"审批策略切换失败：{ex.Message}";
            _suppressToggle = true;
            ApprovalToggle.IsOn = !ApprovalToggle.IsOn;
            _suppressToggle = false;
        }
    }

    // ---------- 实时流 ----------

    private void StartEventStream()
    {
        _eventsCts?.Cancel();
        var cts = new CancellationTokenSource();
        _eventsCts = cts;
        var api = _api;
        if (api is null)
        {
            return;
        }

        _ = Task.Run(async () =>
        {
            await api.StreamEventsAsync(
                frame => DispatcherQueue.TryEnqueue(() => OnFrame(frame)),
                cts.Token);
        });
    }

    private void OnFrame(EventFrame frame)
    {
        switch (frame.Kind)
        {
            case "message" when frame.Message is not null:
                if (_currentSessionId is not null && frame.Message.SessionId == _currentSessionId)
                {
                    AddBubble(frame.Message);
                    ScrollToBottom();
                }
                break;

            case "approval" when frame.Approval is not null:
                _approvals[frame.Approval.Id] = frame.Approval;
                RenderApprovals();
                break;

            case "approval-resolved" when frame.Resolved is not null:
                _approvals.Remove(frame.Resolved.Id);
                RenderApprovals();
                break;
        }
    }

    // ---------- 审批卡片 ----------

    private void RenderApprovals()
    {
        ApprovalPanel.Children.Clear();
        _countdownLabels.Clear();

        foreach (var approval in _approvals.Values
                     .Where(item => item.SessionId == _currentSessionId)
                     .OrderBy(item => item.ExpiresAt))
        {
            ApprovalPanel.Children.Add(BuildApprovalCard(approval));
        }

        ApprovalPanel.Visibility = ApprovalPanel.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        TickCountdowns();
    }

    private Border BuildApprovalCard(ApprovalDto approval)
    {
        var title = new TextBlock
        {
            Text = $"待审批 · {approval.ToolName ?? "工具调用"}",
            Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"],
        };

        var reason = new TextBlock
        {
            Text = approval.Headline,
            TextWrapping = TextWrapping.Wrap,
        };

        var countdown = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = ThemeBrush("TextFillColorSecondaryBrush", Colors.Gray),
        };
        _countdownLabels[approval.Id] = countdown;

        var allow = new Button
        {
            Content = "允许一次",
            Style = (Style)Application.Current.Resources["AccentButtonStyle"],
        };
        allow.Click += async (_, _) => await AnswerAsync(approval.Id, "allowed-once");

        var deny = new Button { Content = "拒绝" };
        deny.Click += async (_, _) => await AnswerAsync(approval.Id, "rejected");

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        buttons.Children.Add(allow);
        buttons.Children.Add(deny);

        var content = new StackPanel { Spacing = 6 };
        content.Children.Add(title);
        content.Children.Add(reason);
        content.Children.Add(countdown);
        content.Children.Add(buttons);

        return new Border
        {
            Padding = new Thickness(12),
            CornerRadius = new CornerRadius(8),
            Background = ThemeBrush("CardBackgroundFillColorDefaultBrush", Colors.WhiteSmoke),
            BorderBrush = ThemeBrush("CardStrokeColorDefaultBrush", Colors.LightGray),
            BorderThickness = new Thickness(1),
            Child = content,
        };
    }

    private async Task AnswerAsync(string id, string outcome)
    {
        if (_api is null)
        {
            return;
        }

        try
        {
            await _api.AnswerApprovalAsync(id, outcome);
            _approvals.Remove(id);
            RenderApprovals();
        }
        catch (Exception ex)
        {
            DetailStatus.Text = $"审批提交失败：{ex.Message}";
        }
    }

    private void TickCountdowns()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        foreach (var (id, label) in _countdownLabels)
        {
            var approval = _approvals.GetValueOrDefault(id);
            if (approval is null)
            {
                continue;
            }
            var remaining = approval.ExpiresAt - now;
            label.Text = remaining > 0
                ? $"{(int)Math.Ceiling(remaining / 1000.0)} 秒内未处理将回退到电脑端"
                : "已回退到电脑端";
        }
    }

    // ---------- 渲染 ----------

    private void AddBubble(MessageDto message)
    {
        var isUser = message.Role == "user";
        var isTool = message.Role == "tool";

        var text = new TextBlock
        {
            Text = message.Text,
            TextWrapping = TextWrapping.Wrap,
            IsTextSelectionEnabled = true,
        };

        var bubble = new Border
        {
            Padding = new Thickness(12, 8, 12, 8),
            CornerRadius = new CornerRadius(10),
            MaxWidth = 640,
            HorizontalAlignment = isUser ? HorizontalAlignment.Right : HorizontalAlignment.Left,
            Child = text,
        };

        if (isUser)
        {
            bubble.Background = ThemeBrush("AccentFillColorDefaultBrush", Colors.SteelBlue);
            text.Foreground = new SolidColorBrush(Colors.White);
        }
        else if (isTool)
        {
            text.FontSize = 12;
            text.Foreground = ThemeBrush("TextFillColorSecondaryBrush", Colors.Gray);
        }
        else
        {
            bubble.Background = ThemeBrush("CardBackgroundFillColorDefaultBrush", Colors.WhiteSmoke);
            bubble.BorderBrush = ThemeBrush("CardStrokeColorDefaultBrush", Colors.LightGray);
            bubble.BorderThickness = new Thickness(1);
        }

        MessagePanel.Children.Add(bubble);
    }

    private static Brush ThemeBrush(string key, Windows.UI.Color fallback)
    {
        if (Application.Current.Resources.TryGetValue(key, out var value) && value is Brush brush)
        {
            return brush;
        }
        return new SolidColorBrush(fallback);
    }

    private void ScrollToBottom()
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            MessageScroll.UpdateLayout();
            MessageScroll.ChangeView(null, MessageScroll.ScrollableHeight, null, true);
        });
    }

    // ---------- 发送 ----------

    private void OnComposerKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter)
        {
            e.Handled = true;
            _ = SendAsync();
        }
    }

    private async void OnSendClick(object sender, RoutedEventArgs e)
    {
        await SendAsync();
    }

    private async Task SendAsync()
    {
        if (_sending || _api is null || _currentSessionId is null)
        {
            return;
        }

        var text = ComposerBox.Text?.Trim();
        if (string.IsNullOrEmpty(text))
        {
            return;
        }

        _sending = true;
        SendButton.IsEnabled = false;
        try
        {
            await _api.SendPromptAsync(_currentSessionId, text);
            ComposerBox.Text = string.Empty;
        }
        catch (Exception ex)
        {
            DetailStatus.Text = $"发送失败：{ex.Message}";
        }
        finally
        {
            _sending = false;
            SendButton.IsEnabled = true;
            ComposerBox.Focus(FocusState.Programmatic);
        }
    }

    private async void OnStopClick(object sender, RoutedEventArgs e)
    {
        if (_api is null || _currentSessionId is null)
        {
            return;
        }

        try
        {
            await _api.CancelTurnAsync(_currentSessionId);
        }
        catch (Exception ex)
        {
            DetailStatus.Text = $"停止失败：{ex.Message}";
        }
    }

    // ---------- 文件 ----------

    private async void OnAttachClick(object sender, RoutedEventArgs e)
    {
        if (_api is null || _currentSessionId is null || _sending)
        {
            return;
        }

        try
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker();
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
            picker.FileTypeFilter.Add("*");

            var file = await picker.PickSingleFileAsync();
            if (file is null)
            {
                return;
            }

            DetailStatus.Text = $"正在上传 {file.Name}…";
            await using var stream = await file.OpenStreamForReadAsync();
            await _api.UploadFileAsync(_currentSessionId, file.Name, stream, ComposerBox.Text?.Trim());
            ComposerBox.Text = string.Empty;
            DetailStatus.Text = $"已发送 {file.Name}";
        }
        catch (Exception ex)
        {
            DetailStatus.Text = $"上传失败：{ex.Message}";
        }
    }

    private async void OnFilesClick(object sender, RoutedEventArgs e)
    {
        if (_api is null || _currentSessionId is null)
        {
            return;
        }

        var sessionId = _currentSessionId;
        var pathBlock = new TextBlock { Text = ".", Opacity = 0.7, FontSize = 12 };
        var statusBlock = new TextBlock { Text = string.Empty, Opacity = 0.8, FontSize = 12, TextWrapping = TextWrapping.Wrap };
        var list = new ListView
        {
            Height = 380,
            Width = 460,
            IsItemClickEnabled = true,
            SelectionMode = ListViewSelectionMode.None,
        };

        var content = new StackPanel { Spacing = 8 };
        content.Children.Add(pathBlock);
        content.Children.Add(list);
        content.Children.Add(statusBlock);

        var dialog = new ContentDialog
        {
            Title = "工作区文件",
            Content = content,
            CloseButtonText = "关闭",
            XamlRoot = Root.XamlRoot,
        };

        var entries = new List<WorkspaceEntryDto>();

        async Task LoadAsync(string path)
        {
            pathBlock.Text = path;
            entries = await _api.ListWorkspaceAsync(sessionId, path);
            list.ItemsSource = entries;
        }

        async Task DownloadAsync(string path)
        {
            try
            {
                statusBlock.Text = "正在准备下载…";
                var signed = await _api.SignFileAsync(sessionId, path);
                var bytes = await _api.DownloadAsync(signed.Url);

                var directory = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "DSH");
                Directory.CreateDirectory(directory);
                var target = Path.Combine(directory, signed.Name);
                await File.WriteAllBytesAsync(target, bytes);
                statusBlock.Text = $"已保存到 {target}";
            }
            catch (Exception ex)
            {
                statusBlock.Text = $"下载失败：{ex.Message}";
            }
        }

        list.ItemClick += async (_, args) =>
        {
            if (args.ClickedItem is not WorkspaceEntryDto entry)
            {
                return;
            }

            if (entry.Directory)
            {
                var next = pathBlock.Text == "." ? entry.Name : $"{pathBlock.Text}/{entry.Name}";
                try
                {
                    await LoadAsync(next);
                    statusBlock.Text = string.Empty;
                }
                catch (Exception ex)
                {
                    statusBlock.Text = $"打开失败：{ex.Message}";
                }
            }
            else
            {
                var path = pathBlock.Text == "." ? entry.Name : $"{pathBlock.Text}/{entry.Name}";
                await DownloadAsync(path);
            }
        };

        list.ItemTemplate = (DataTemplate)Root.Resources["WorkspaceEntryTemplate"];

        try
        {
            await LoadAsync(".");
        }
        catch (Exception ex)
        {
            statusBlock.Text = $"读取失败：{ex.Message}";
        }

        await dialog.ShowAsync();
    }

    // ---------- 配对码 ----------

    /// <summary>The pairing endpoints are loopback-only, and this app always runs on the plugin's machine.</summary>
    private static string LocalBase(string server)
    {
        return Uri.TryCreate(server, UriKind.Absolute, out var uri)
            ? $"http://127.0.0.1:{uri.Port}"
            : "http://127.0.0.1:7317";
    }

    private static string DescribePairing(PairingInfoDto? info)
    {
        if (info is null || string.IsNullOrEmpty(info.Code))
        {
            return "当前没有有效配对码";
        }
        var left = TimeSpan.FromMilliseconds(Math.Max(0, info.ExpiresAt - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        return $"配对码：{info.Code}　剩余 {(int)left.TotalMinutes}:{left.Seconds:D2}";
    }

    private async void OnPairingInfoClick(object sender, RoutedEventArgs e)
    {
        var local = new RemoteApi(LocalBase(_settings.ServerUrl), null);

        var codeBlock = new TextBlock
        {
            FontSize = 34,
            FontFamily = new FontFamily("Consolas"),
            IsTextSelectionEnabled = true,
            Text = "------",
        };
        var countdown = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = ThemeBrush("TextFillColorSecondaryBrush", Colors.Gray),
        };
        var deviceList = new StackPanel { Spacing = 6 };
        var status = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = ThemeBrush("TextFillColorSecondaryBrush", Colors.Gray),
            TextWrapping = TextWrapping.Wrap,
        };

        var regenerate = new Button { Content = "重新生成" };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        actions.Children.Add(regenerate);

        var stack = new StackPanel { Spacing = 10, Width = 440 };
        stack.Children.Add(codeBlock);
        stack.Children.Add(countdown);
        stack.Children.Add(actions);
        stack.Children.Add(new TextBlock { Text = "已配对设备", Margin = new Thickness(0, 8, 0, 0) });
        stack.Children.Add(deviceList);
        stack.Children.Add(status);

        var dialog = new ContentDialog
        {
            Title = "配对码与设备",
            Content = stack,
            CloseButtonText = "关闭",
            XamlRoot = Root.XamlRoot,
        };

        PairingInfoDto? current = null;

        async Task ReloadAsync()
        {
            try
            {
                var response = await local.GetPairingAsync();
                current = response.Pairing;
                if (current is null)
                {
                    current = await local.RegeneratePairingAsync();
                }
                codeBlock.Text = current?.Code ?? "------";

                deviceList.Children.Clear();
                if (response.Devices.Count == 0)
                {
                    deviceList.Children.Add(new TextBlock { Text = "还没有设备配对", Opacity = 0.6 });
                }
                foreach (var device in response.Devices)
                {
                    var row = new Grid();
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                    var info = new StackPanel { Spacing = 2 };
                    info.Children.Add(new TextBlock { Text = device.Name });
                    info.Children.Add(new TextBlock
                    {
                        Text = "配对于 " + DateTimeOffset.FromUnixTimeMilliseconds(device.CreatedAt).ToLocalTime().ToString("MM-dd HH:mm"),
                        FontSize = 12,
                        Opacity = 0.6,
                    });
                    row.Children.Add(info);

                    var revoke = new Button { Content = "吊销" };
                    Grid.SetColumn(revoke, 1);
                    revoke.Click += async (_, _) =>
                    {
                        await local.RevokeDeviceAsync(device.Id);
                        status.Text = $"已吊销 {device.Name}";
                        await ReloadAsync();
                    };
                    row.Children.Add(revoke);
                    deviceList.Children.Add(row);
                }
            }
            catch (Exception ex)
            {
                status.Text = $"读取失败：{ex.Message}";
            }
        }

        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        timer.Tick += (_, _) =>
        {
            if (current is null)
            {
                countdown.Text = string.Empty;
                return;
            }
            var left = TimeSpan.FromMilliseconds(Math.Max(0, current.ExpiresAt - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
            countdown.Text = left.TotalSeconds <= 0
                ? "已过期，点击“重新生成”"
                : $"剩余 {(int)left.TotalMinutes}:{left.Seconds:D2}";
        };
        dialog.Closed += (_, _) =>
        {
            timer.Stop();
            local.Dispose();
        };

        regenerate.Click += async (_, _) =>
        {
            try
            {
                current = await local.RegeneratePairingAsync();
                codeBlock.Text = current?.Code ?? "------";
                status.Text = "已生成新的配对码";
            }
            catch (Exception ex)
            {
                status.Text = $"生成失败：{ex.Message}";
            }
        };

        await ReloadAsync();
        timer.Start();
        await dialog.ShowAsync();
    }

    // ---------- 工具方法 ----------

    private static string TitleOf(SessionDto session)
    {
        var cwd = session.Cwd ?? string.Empty;
        var name = Path.GetFileName(cwd.TrimEnd('\\', '/'));
        return string.IsNullOrEmpty(name) ? session.Id : name;
    }

    private static string FormatWhen(long? timestamp)
    {
        if (timestamp is null)
        {
            return "时间未知";
        }
        var time = DateTimeOffset.FromUnixTimeMilliseconds(timestamp.Value).ToLocalTime();
        var delta = DateTimeOffset.Now - time;
        if (delta.TotalMinutes < 1) return "刚刚";
        if (delta.TotalHours < 1) return $"{(int)delta.TotalMinutes} 分钟前";
        if (delta.TotalDays < 1) return $"{(int)delta.TotalHours} 小时前";
        return time.ToString("M月d日");
    }
}
