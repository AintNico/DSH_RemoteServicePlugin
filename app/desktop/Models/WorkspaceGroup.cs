using System.Collections.ObjectModel;
using System.Text.Json.Serialization;

namespace DshRemote.Models;

public sealed class WorkspaceDto
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("title")]
    public string Title { get; set; } = "";

    [JsonPropertyName("path")]
    public string Path { get; set; } = "";

    [JsonPropertyName("sessions")]
    public List<SessionDto> Sessions { get; set; } = new();
}

public sealed class WorkspacesDto
{
    [JsonPropertyName("workspaces")]
    public List<WorkspaceDto> Workspaces { get; set; } = new();

    [JsonPropertyName("ungrouped")]
    public List<SessionDto> Ungrouped { get; set; } = new();
}

/// <summary>
/// One section of the session list. It is both the group items holder (for a grouped
/// CollectionViewSource) and the header model.
/// </summary>
public sealed class WorkspaceGroup : ObservableCollection<SessionItem>
{
    public string Title { get; init; } = "";

    public string Subtitle { get; init; } = "";
}
