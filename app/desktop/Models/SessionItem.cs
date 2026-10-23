using System.Text.Json.Serialization;

namespace DshRemote.Models;

public sealed class SessionDto
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("running")]
    public bool Running { get; set; }

    [JsonPropertyName("blank")]
    public bool Blank { get; set; }

    [JsonPropertyName("updatedAt")]
    public long? UpdatedAt { get; set; }

    [JsonPropertyName("cwd")]
    public string? Cwd { get; set; }

    [JsonPropertyName("origin")]
    public string? Origin { get; set; }
}

public sealed class SessionListDto
{
    [JsonPropertyName("sessions")]
    public List<SessionDto> Sessions { get; set; } = new();
}

public sealed class PairResultDto
{
    [JsonPropertyName("token")]
    public string Token { get; set; } = "";

    [JsonPropertyName("error")]
    public string? Error { get; set; }
}

public sealed class HealthDto
{
    [JsonPropertyName("ok")]
    public bool Ok { get; set; }

    [JsonPropertyName("pairing")]
    public bool Pairing { get; set; }
}

/// <summary>Row model for the session list.</summary>
public sealed class SessionItem
{
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public string Subtitle { get; init; } = "";
    public bool Running { get; init; }

    public string Status => Running ? "运行中" : "空闲";
    public string Dot => Running ? "●" : "○";
}
