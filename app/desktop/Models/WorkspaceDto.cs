using System.Text.Json.Serialization;

namespace DshRemote.Models;

public sealed class WorkspaceEntryDto
{
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("directory")]
    public bool Directory { get; set; }

    [JsonPropertyName("size")]
    public long? Size { get; set; }

    public string Display => Directory ? $"📁 {Name}" : $"📄 {Name}";

    public string Detail => Directory ? "文件夹" : FormatSize(Size);

    private static string FormatSize(long? bytes) => bytes switch
    {
        null => "",
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:F1} KB",
        _ => $"{bytes / (1024.0 * 1024.0):F1} MB",
    };
}

public sealed class WorkspaceListDto
{
    [JsonPropertyName("path")]
    public string Path { get; set; } = ".";

    [JsonPropertyName("entries")]
    public List<WorkspaceEntryDto> Entries { get; set; } = new();
}

public sealed class SignedFileDto
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("size")]
    public long? Size { get; set; }

    [JsonPropertyName("expiresAt")]
    public long ExpiresAt { get; set; }
}
