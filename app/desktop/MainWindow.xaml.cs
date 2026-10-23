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
    private CancellationTokenSource? _eventsCts;
    private string? _currentSessionId;
    private int _loadGeneration;
    private bool _busy;
    private bool _sending;

    public MainWindow()
    {
        InitializeComponent();

        AppWindow.Resize(new SizeInt32(1120, 760));
        SessionList.ItemsSource = _sessions;
        ServerBox.Text = _settings.ServerUrl;
        DeviceBox.Text = Environment.MachineName;
        ServerLabel.Text = _settings.ServerUrl;

        if (string.IsNullOrEmpty(_settings.Token))
        {
            ShowPairOverlay(true);
        }
        else
        {
            _ = ConnectAsync();
        }

        Closed += (_, _) =>
        {
            _eventsCts?.Cancel();
            _api?.Dispose();
        };
    }

    // ---------- 配对 ----------

    private void ShowPairOverlay(bool show)
    {
        PairOverlay.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        PairError.Text = string.Empty;
        if (show)
        {
            CodeBox.Focus(FocusState.Programmatic);
        }
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        Busy.IsActive = busy;
        RefreshButton.IsEnabled = !busy;
        PairButton.IsEnabled = !busy;
    }

    private async Task ConnectAsync()
    {
        SetBusy(true);
        try
        {
            _api?.Dispose();
            _api = new RemoteApi(_settings.ServerUrl, _settings.Token);
            await LoadSessionsAsync();
            ServerLabel.Text = _settings.ServerUrl;
            StartEventStream();
        }
        catch (ApiException ex) when (ex.Status == HttpStatusCode.Unauthorized)
        {
            _settings.Token = null;
            SettingsStore.Save(_settings);
            ShowPairOverlay(true);
        }
        catch (Exception ex)
        {
            ServerLabel.Text = $"{_settings.ServerUrl} · 未连接";
            PairError.Text = $"无法连接插件服务：{ex.Message}";
            ShowPairOverlay(true);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async void OnPairClick(object sender, RoutedEventArgs e)
    {
        if (_busy)
        {
            return;
        }

        var server = ServerBox.Text.Trim();
        var code = CodeBox.Text.Trim();
        var device = string.IsNullOrWhiteSpace(DeviceBox.Text) ? "Windows" : DeviceBox.Text.Trim();

        if (!Uri.TryCreate(server, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
        {
            PairError.Text = "服务地址需要以 http:// 或 https:// 开头";
            return;
        }
        if (code.Length != 6)
        {
            PairError.Text = "配对码是 6 位数字";
            return;
        }

        SetBusy(true);
        PairError.Text = string.Empty;
        try
        {
            using var api = new RemoteApi(server, null);
            var token = await api.PairAsync(code, device);

            _settings = new AppSettings { ServerUrl = server, Token = token };
            SettingsStore.Save(_settings);
            ServerLabel.Text = server;
            ShowPairOverlay(false);

            _api?.Dispose();
            _api = new RemoteApi(_settings.ServerUrl, _settings.Token);
            await LoadSessionsAsync();
            StartEventStream();
        }
        catch (Exception ex)
        {
            PairError.Text = ex.Message;
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void OnRepairClick(object sender, RoutedEventArgs e)
    {
        _eventsCts?.Cancel();
        _settings.Token = null;
        SettingsStore.Save(_settings);
        _api?.Dispose();
        _api = null;
        _currentSessionId = null;
        _sessions.Clear();
        MessagePanel.Children.Clear();
        DetailPanel.Visibility = Visibility.Collapsed;
        EmptyState.Visibility = Visibility.Visible;
        ShowPairOverlay(true);
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
                message => DispatcherQueue.TryEnqueue(() => OnLiveMessage(message)),
                cts.Token);
        });
    }

    private void OnLiveMessage(MessageDto message)
    {
        if (_currentSessionId is null || message.SessionId != _currentSessionId)
        {
            return;
        }

        AddBubble(message);
        ScrollToBottom();
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
