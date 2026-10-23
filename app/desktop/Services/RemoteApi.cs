using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using DshRemote.Models;

namespace DshRemote.Services;

public sealed class ApiException : Exception
{
    public HttpStatusCode? Status { get; }
    public string Code { get; }

    public ApiException(string code, string message, HttpStatusCode? status = null) : base(message)
    {
        Code = code;
        Status = status;
    }
}

/// <summary>Thin client over the plugin's HTTP API.</summary>
public sealed class RemoteApi : IDisposable
{
    private static readonly JsonSerializerOptions Options = new() { PropertyNameCaseInsensitive = true };

    private readonly HttpClient _http;

    public string ServerUrl { get; }
    public string? Token { get; }

    public RemoteApi(string serverUrl, string? token)
    {
        ServerUrl = serverUrl.TrimEnd('/');
        Token = token;
        _http = new HttpClient { BaseAddress = new Uri(ServerUrl + "/"), Timeout = TimeSpan.FromSeconds(8) };
        if (!string.IsNullOrEmpty(token))
        {
            _http.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
        }
    }

    public async Task<HealthDto> HealthAsync(CancellationToken cancellationToken = default)
    {
        using var response = await _http.GetAsync("api/health", cancellationToken);
        return await ReadAsync<HealthDto>(response, cancellationToken);
    }

    public async Task<string> PairAsync(string code, string deviceName, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            "api/pair",
            new { code, deviceName },
            cancellationToken);
        var result = await ReadAsync<PairResultDto>(response, cancellationToken);
        if (string.IsNullOrEmpty(result.Token))
        {
            throw new ApiException("pair-failed", "配对失败：服务端没有返回令牌", response.StatusCode);
        }
        return result.Token;
    }

    public async Task<List<SessionDto>> GetSessionsAsync(CancellationToken cancellationToken = default)
    {
        using var response = await _http.GetAsync("api/sessions", cancellationToken);
        var result = await ReadAsync<SessionListDto>(response, cancellationToken);
        return result.Sessions;
    }

    public async Task<MessagesDto> GetMessagesAsync(string sessionId, CancellationToken cancellationToken = default)
    {
        using var response = await _http.GetAsync(
            $"api/sessions/{Uri.EscapeDataString(sessionId)}/messages", cancellationToken);
        return await ReadAsync<MessagesDto>(response, cancellationToken);
    }

    public async Task SendPromptAsync(string sessionId, string text, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            $"api/sessions/{Uri.EscapeDataString(sessionId)}/prompt",
            new { text },
            cancellationToken);
        await ReadAsync<PromptResultDto>(response, cancellationToken);
    }

    public async Task CancelTurnAsync(string sessionId, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsync(
            $"api/sessions/{Uri.EscapeDataString(sessionId)}/cancel", null, cancellationToken);
        await ReadAsync<PromptResultDto>(response, cancellationToken);
    }

    public async Task<bool> AnswerApprovalAsync(string id, string outcome, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            $"api/approvals/{Uri.EscapeDataString(id)}/answer",
            new { outcome },
            cancellationToken);
        if (response.StatusCode == HttpStatusCode.Conflict)
        {
            return false;
        }
        await ReadAsync<JsonElement>(response, cancellationToken);
        return true;
    }

    public async Task<string> GetApprovalPolicyAsync(string sessionId, CancellationToken cancellationToken = default)
    {
        using var response = await _http.GetAsync(
            $"api/sessions/{Uri.EscapeDataString(sessionId)}/approval-policy", cancellationToken);
        var value = await ReadAsync<PolicyDto>(response, cancellationToken);
        return value.Policy;
    }

    public async Task<string> SetApprovalPolicyAsync(string sessionId, string policy, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            $"api/sessions/{Uri.EscapeDataString(sessionId)}/approval-policy",
            new { policy },
            cancellationToken);
        var value = await ReadAsync<PolicyDto>(response, cancellationToken);
        return value.Policy;
    }

    /// <summary>Consumes the server-sent event feed until cancellation, reconnecting on failure.</summary>
    public async Task StreamEventsAsync(Action<EventFrame> onFrame, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, "api/events");
                using var response = await _http.SendAsync(
                    request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                if (!response.IsSuccessStatusCode)
                {
                    await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken);
                    continue;
                }

                await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
                using var reader = new StreamReader(stream);
                while (!cancellationToken.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync(cancellationToken);
                    if (line is null) break;
                    if (!line.StartsWith("data: ", StringComparison.Ordinal)) continue;

                    var frame = DecodeFrame(line[6..]);
                    if (frame is not null) onFrame(frame);
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception)
            {
                // Transient network failure: back off and resubscribe.
            }

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private static EventFrame? DecodeFrame(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            if (!document.RootElement.TryGetProperty("kind", out var kindElement)) return null;

            return kindElement.GetString() switch
            {
                "message" => new EventFrame
                {
                    Kind = "message",
                    Message = JsonSerializer.Deserialize<MessageDto>(json, Options),
                },
                "approval" => new EventFrame
                {
                    Kind = "approval",
                    Approval = JsonSerializer.Deserialize<ApprovalDto>(json, Options),
                },
                "approval-resolved" => new EventFrame
                {
                    Kind = "approval-resolved",
                    Resolved = JsonSerializer.Deserialize<ApprovalResolvedDto>(json, Options),
                },
                _ => null,
            };
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private async Task<T> ReadAsync<T>(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (!response.IsSuccessStatusCode)
        {
            var code = $"http-{(int)response.StatusCode}";
            string message = code;
            try
            {
                using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
                if (document.RootElement.TryGetProperty("error", out var error))
                {
                    code = error.GetString() ?? code;
                }
                message = code switch
                {
                    "bad-code" => "配对码不正确",
                    "expired" => "配对码已过期，请在插件服务上重新生成",
                    "too-many-attempts" => "尝试次数过多，请稍后再试",
                    "unauthorized" => "登录已失效，请重新配对",
                    "not-pairing" => "当前没有可用的配对码",
                    _ => code,
                };
            }
            catch (JsonException)
            {
                // Keep the HTTP status as the message.
            }
            throw new ApiException(code, message, response.StatusCode);
        }

        var value = await response.Content.ReadFromJsonAsync<T>(Options, cancellationToken);
        return value ?? throw new ApiException("empty-response", "服务端返回了空响应");
    }

    public async Task<List<WorkspaceEntryDto>> ListWorkspaceAsync(string sessionId, string path, CancellationToken cancellationToken = default)
    {
        using var response = await _http.GetAsync(
            $"api/sessions/{Uri.EscapeDataString(sessionId)}/workspace?path={Uri.EscapeDataString(path)}",
            cancellationToken);
        var value = await ReadAsync<WorkspaceListDto>(response, cancellationToken);
        return value.Entries;
    }

    public async Task<SignedFileDto> SignFileAsync(string sessionId, string path, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            $"api/sessions/{Uri.EscapeDataString(sessionId)}/files/sign",
            new { path },
            cancellationToken);
        return await ReadAsync<SignedFileDto>(response, cancellationToken);
    }

    public async Task<byte[]> DownloadAsync(string urlPath, CancellationToken cancellationToken = default)
    {
        return await _http.GetByteArrayAsync(urlPath.TrimStart('/'), cancellationToken);
    }

    public async Task<object> UploadFileAsync(
        string sessionId,
        string fileName,
        Stream content,
        string? caption,
        CancellationToken cancellationToken = default)
    {
        var query = $"api/sessions/{Uri.EscapeDataString(sessionId)}/upload?name={Uri.EscapeDataString(fileName)}";
        if (!string.IsNullOrWhiteSpace(caption))
        {
            query += $"&text={Uri.EscapeDataString(caption)}";
        }

        using var body = new StreamContent(content);
        body.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
        using var response = await _http.PostAsync(query, body, cancellationToken);
        return await ReadAsync<JsonElement>(response, cancellationToken);
    }

    public void Dispose() => _http.Dispose();
}
