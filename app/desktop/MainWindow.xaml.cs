using System.Collections.ObjectModel;
using System.Net;
using DshRemote.Models;
using DshRemote.Services;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Graphics;

namespace DshRemote;

public sealed partial class MainWindow : Window
{
    private AppSettings _settings = SettingsStore.Load();
    private RemoteApi? _api;
    private readonly ObservableCollection<SessionItem> _sessions = new();
    private bool _busy;

    public MainWindow()
    {
        InitializeComponent();

        AppWindow.Resize(new SizeInt32(1040, 720));

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

        Closed += (_, _) => _api?.Dispose();
    }

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

    private void OnSessionSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SessionList.SelectedItem is not SessionItem item)
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
    }

    private void OnRepairClick(object sender, RoutedEventArgs e)
    {
        _settings.Token = null;
        SettingsStore.Save(_settings);
        _api?.Dispose();
        _api = null;
        _sessions.Clear();
        DetailPanel.Visibility = Visibility.Collapsed;
        EmptyState.Visibility = Visibility.Visible;
        ShowPairOverlay(true);
    }
}
