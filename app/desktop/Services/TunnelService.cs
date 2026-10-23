using System.Diagnostics;
using System.Text.RegularExpressions;

namespace DshRemote.Services;

/// <summary>
/// Publishes the local plugin through a zero-install SSH reverse tunnel (localhost.run),
/// which terminates TLS at its edge and hands back an https hostname. It reuses the OpenSSH
/// client that ships with Windows, so there is nothing to download or install.
///
/// The tunnel is only a transport: the phone still has to present its device token, and the
/// plugin refuses loopback trust for requests that carry forwarding headers.
/// </summary>
public sealed class TunnelService : IDisposable
{
    private static readonly Regex UrlPattern = new(
        @"https://[a-z0-9][a-z0-9.-]*\.(?:lhr\.life|localhost\.run)",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private Process? _process;

    public bool IsRunning => _process is { HasExited: false };

    public string? PublicUrl { get; private set; }

    public event Action<string>? StatusChanged;

    public event Action<string?>? UrlChanged;

    /// <summary>The Windows OpenSSH client is required; it is present on Windows 10 1809+.</summary>
    public static bool SshAvailable()
    {
        try
        {
            using var probe = Process.Start(new ProcessStartInfo("ssh", "-V")
            {
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            if (probe is null)
            {
                return false;
            }
            probe.WaitForExit(3000);
            return true;
        }
        catch
        {
            return false;
        }
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (IsRunning)
        {
            return;
        }

        StatusChanged?.Invoke("正在建立 SSH 隧道…");
        var info = new ProcessStartInfo("ssh")
        {
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var argument in new[]
                 {
                     "-o", "StrictHostKeyChecking=accept-new",
                     "-o", "ServerAliveInterval=30",
                     "-o", "ServerAliveCountMax=3",
                     "-o", "ExitOnForwardFailure=yes",
                     "-o", "BatchMode=yes",
                     "-T",
                     "-R", "80:127.0.0.1:7317",
                     "nokey@localhost.run",
                 })
        {
            info.ArgumentList.Add(argument);
        }

        _process = new Process { StartInfo = info, EnableRaisingEvents = true };
        _process.ErrorDataReceived += (_, e) => HandleLine(e.Data);
        _process.OutputDataReceived += (_, e) => HandleLine(e.Data);
        _process.Exited += (_, _) =>
        {
            PublicUrl = null;
            UrlChanged?.Invoke(null);
            StatusChanged?.Invoke("隧道已断开");
        };

        _process.Start();
        _process.BeginErrorReadLine();
        _process.BeginOutputReadLine();

        var deadline = DateTime.UtcNow.AddSeconds(35);
        while (DateTime.UtcNow < deadline && PublicUrl is null && !cancellationToken.IsCancellationRequested)
        {
            if (_process.HasExited)
            {
                StatusChanged?.Invoke("SSH 连接失败（可能出站 22 端口被限制）");
                return;
            }
            await Task.Delay(300, cancellationToken);
        }

        StatusChanged?.Invoke(PublicUrl is null ? "等待公网地址超时" : "隧道已就绪");
    }

    public Task StopAsync()
    {
        try
        {
            if (_process is { HasExited: false })
            {
                _process.Kill(entireProcessTree: true);
            }
        }
        catch
        {
            // The process may already be gone.
        }
        finally
        {
            _process = null;
            PublicUrl = null;
            UrlChanged?.Invoke(null);
            StatusChanged?.Invoke("隧道已停止");
        }
        return Task.CompletedTask;
    }

    private void HandleLine(string? line)
    {
        if (string.IsNullOrWhiteSpace(line))
        {
            return;
        }

        var match = UrlPattern.Match(line);
        if (match.Success && match.Value != PublicUrl)
        {
            PublicUrl = match.Value;
            UrlChanged?.Invoke(PublicUrl);
            StatusChanged?.Invoke("隧道已就绪");
        }
    }

    public void Dispose()
    {
        try
        {
            if (_process is { HasExited: false })
            {
                _process.Kill(entireProcessTree: true);
            }
        }
        catch
        {
            // Ignore shutdown races.
        }
        _process?.Dispose();
    }
}
