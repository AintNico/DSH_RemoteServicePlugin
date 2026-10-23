using System.Text.Json;

namespace DshRemote.Services;

public sealed class AppSettings
{
    public string ServerUrl { get; set; } = "http://127.0.0.1:7317";
    public string? Token { get; set; }
}

/// <summary>Settings live next to the app data, not in the repo.</summary>
public static class SettingsStore
{
    private static readonly string Directory =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DshRemote");

    private static readonly string FilePath = Path.Combine(Directory, "settings.json");

    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var text = File.ReadAllText(FilePath).TrimStart('\uFEFF');
                return JsonSerializer.Deserialize<AppSettings>(text, Options) ?? new AppSettings();
            }
        }
        catch
        {
            // Corrupt settings should never block startup.
        }
        return new AppSettings();
    }

    public static void Save(AppSettings settings)
    {
        try
        {
            System.IO.Directory.CreateDirectory(Directory);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(settings, Options));
        }
        catch
        {
            // Persisting preferences is best-effort.
        }
    }
}
