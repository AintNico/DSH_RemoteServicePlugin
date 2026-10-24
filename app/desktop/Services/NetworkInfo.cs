using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace DshRemote.Services;

/// <summary>
/// Finds the address a phone should use. The window itself always talks to 127.0.0.1, but the
/// pairing screen has to show the LAN address, which changes when DHCP hands out a new lease.
/// </summary>
public static class NetworkInfo
{
    public static string? LanIPv4()
    {
        var withGateway = new List<string>();
        var others = new List<string>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up)
            {
                continue;
            }
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            IPInterfaceProperties properties;
            try
            {
                properties = nic.GetIPProperties();
            }
            catch
            {
                continue;
            }

            var hasGateway = properties.GatewayAddresses.Any(gateway =>
                gateway.Address.AddressFamily == AddressFamily.InterNetwork &&
                !gateway.Address.Equals(IPAddress.Any));

            foreach (var address in properties.UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork)
                {
                    continue;
                }
                if (IPAddress.IsLoopback(address.Address))
                {
                    continue;
                }

                var text = address.Address.ToString();
                if (hasGateway)
                {
                    withGateway.Add(text);
                }
                else
                {
                    others.Add(text);
                }
            }
        }

        return withGateway.FirstOrDefault() ?? others.FirstOrDefault();
    }

    public static string LanBaseUrl(int port)
    {
        var ip = LanIPv4();
        return string.IsNullOrEmpty(ip) ? string.Empty : $"http://{ip}:{port}";
    }
}
