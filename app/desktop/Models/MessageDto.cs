using System.Text.Json.Serialization;

namespace DshRemote.Models;

public sealed class MessageDto
{
    [JsonPropertyName("sessionId")]
    public string? SessionId { get; set; }

    [JsonPropertyName("seq")]
    public long Seq { get; set; }

    [JsonPropertyName("role")]
    public string Role { get; set; } = "";

    [JsonPropertyName("text")]
    public string Text { get; set; } = "";

    [JsonPropertyName("time")]
    public long Time { get; set; }

    [JsonPropertyName("interrupted")]
    public bool Interrupted { get; set; }

    [JsonPropertyName("isError")]
    public bool IsError { get; set; }
}

public sealed class MessagesDto
{
    [JsonPropertyName("messages")]
    public List<MessageDto> Messages { get; set; } = new();

    [JsonPropertyName("throughSeq")]
    public long? ThroughSeq { get; set; }
}

public sealed class PromptResultDto
{
    [JsonPropertyName("accepted")]
    public bool Accepted { get; set; }
}
