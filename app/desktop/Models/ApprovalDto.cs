using System.Text.Json.Serialization;

namespace DshRemote.Models;

public sealed class ApprovalDto
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("sessionId")]
    public string? SessionId { get; set; }

    [JsonPropertyName("toolName")]
    public string? ToolName { get; set; }

    [JsonPropertyName("reason")]
    public string? Reason { get; set; }

    [JsonPropertyName("displayReason")]
    public Dictionary<string, string>? DisplayReason { get; set; }

    [JsonPropertyName("expiresAt")]
    public long ExpiresAt { get; set; }

    [JsonPropertyName("retry")]
    public bool Retry { get; set; }

    public string Headline => string.IsNullOrEmpty(Reason) ? (ToolName ?? "工具调用") : Reason;
}

public sealed class ApprovalResolvedDto
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("outcome")]
    public string? Outcome { get; set; }
}

/// <summary>One decoded server-sent event frame.</summary>
public sealed class EventFrame
{
    public string Kind { get; init; } = "";
    public MessageDto? Message { get; init; }
    public ApprovalDto? Approval { get; init; }
    public ApprovalResolvedDto? Resolved { get; init; }
}

public sealed class PolicyDto
{
    [JsonPropertyName("policy")]
    public string Policy { get; set; } = "never";

    [JsonPropertyName("error")]
    public string? Error { get; set; }

    [JsonPropertyName("message")]
    public string? Message { get; set; }
}
