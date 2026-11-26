using System.Text.Json.Serialization;

namespace DshRemote.Models;

public sealed class PairingInfoDto
{
    [JsonPropertyName("code")]
    public string Code { get; set; } = "";

    [JsonPropertyName("expiresAt")]
    public long ExpiresAt { get; set; }
}

public sealed class DeviceDto
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("createdAt")]
    public long CreatedAt { get; set; }

    [JsonPropertyName("lastSeenAt")]
    public long LastSeenAt { get; set; }

    [JsonPropertyName("expiresAt")]
    public long ExpiresAt { get; set; }
}

public sealed class PairingResponseDto
{
    [JsonPropertyName("pairing")]
    public PairingInfoDto? Pairing { get; set; }

    [JsonPropertyName("devices")]
    public List<DeviceDto> Devices { get; set; } = new();

    /// <summary>LAN address the phone should use, as the plugin sees it.</summary>
    [JsonPropertyName("url")]
    public string? Url { get; set; }

    /// <summary>`dshremote://pair?...` — what the QR encodes and what the phone parses.</summary>
    [JsonPropertyName("payload")]
    public string? Payload { get; set; }
}

public sealed class PairingOnlyDto
{
    [JsonPropertyName("pairing")]
    public PairingInfoDto? Pairing { get; set; }
}
