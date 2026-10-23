using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace DshRemote.Models;

/// <summary>A single rendered chat message. Kept tiny so a long history stays cheap.</summary>
public sealed class MessageItem
{
    public string Role { get; init; } = "";

    public string Text { get; init; } = "";
}

/// <summary>Picks the bubble template for user, assistant and tool messages.</summary>
public sealed partial class MessageTemplateSelector : DataTemplateSelector
{
    public DataTemplate? User { get; set; }

    public DataTemplate? Assistant { get; set; }

    public DataTemplate? Tool { get; set; }

    protected override DataTemplate SelectTemplateCore(object item)
    {
        return SelectTemplateCore(item, null!);
    }

    protected override DataTemplate SelectTemplateCore(object item, DependencyObject container)
    {
        var role = (item as MessageItem)?.Role;
        return role switch
        {
            "user" => User ?? Assistant!,
            "tool" => Tool ?? Assistant!,
            _ => Assistant!,
        };
    }
}
