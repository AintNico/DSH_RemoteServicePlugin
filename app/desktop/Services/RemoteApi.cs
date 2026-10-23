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

    public void Dispose() => _http.Dispose();
}
