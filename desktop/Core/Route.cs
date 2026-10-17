using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
namespace VlessCard;

// Preview intentionally accepts only VLESS TCP/TLS/Reality. Unsupported transports are rejected, never rewritten.
public record Route(string Name,string Host,int Port,string Id,string Security,string Sni,string Fingerprint,string PublicKey,string ShortId,string Flow)
{
    static string Decode(string text) => Uri.UnescapeDataString(text);
    public static Route Parse(string text)
    {
        var u=new Uri(text.Trim());
        if(u.Scheme!="vless" || u.Port is <1 or >65535 || !Guid.TryParse(Decode(u.UserInfo),out var id) || u.Host.Length==0)
            throw new FormatException("Нужна ссылка VLESS с UUID и явным портом");
        var q=u.Query.TrimStart('?').Split('&',StringSplitOptions.RemoveEmptyEntries)
            .Select(x=>x.Split('=',2)).ToDictionary(x=>Decode(x[0]),x=>x.Length>1?Decode(x[1]):"",StringComparer.OrdinalIgnoreCase);
        string Get(string key,string fallback="")=>q.GetValueOrDefault(key,fallback);
        var security=Get("security","none");var type=Get("type","tcp");
        if(type!="tcp" && type!="raw") throw new FormatException("В preview поддерживается только TCP/raw");
        if(security!="tls" && security!="reality") throw new FormatException("В preview требуется TLS или Reality");
        if(Get("allowInsecure")=="1" || Get("allowInsecure")=="true") throw new FormatException("Небезопасный TLS не поддерживается");
        if(security=="reality" && Get("pbk").Length==0) throw new FormatException("Не указан публичный ключ Reality");
        if(Get("flow") is not "" and not "xtls-rprx-vision") throw new FormatException("Неподдерживаемый flow");
        return new(Decode(u.Fragment.TrimStart('#')).Replace('\n',' ').Replace('\r',' '),u.IdnHost,u.Port,id.ToString(),security,Get("sni",Get("serverName",u.IdnHost)),Get("fp","chrome"),Get("pbk"),Get("sid"),Get("flow"));
    }
    public override string ToString()=>string.IsNullOrWhiteSpace(Name)?Host:Name;
    public string Config(int localPort,string user,string pass)
    {
        var tls=new JsonObject { ["serverName"]=Sni,["fingerprint"]=Fingerprint };
        if(Security=="reality") {tls["publicKey"]=PublicKey;tls["shortId"]=ShortId;}
        var stream=new JsonObject { ["network"]="tcp",["security"]=Security,[Security+"Settings"]=tls };
        return new JsonObject {
            ["log"]=new JsonObject{["loglevel"]="none"},
            ["inbounds"]=new JsonArray(new JsonObject{["listen"]="127.0.0.1",["port"]=localPort,["protocol"]="socks",
                ["settings"]=new JsonObject{["auth"]="password",["udp"]=false,["accounts"]=new JsonArray(new JsonObject{["user"]=user,["pass"]=pass})}}),
            ["outbounds"]=new JsonArray(new JsonObject{["protocol"]="vless",["settings"]=new JsonObject{["vnext"]=new JsonArray(new JsonObject{
                ["address"]=Host,["port"]=Port,["users"]=new JsonArray(new JsonObject{["id"]=Id,["encryption"]="none",["flow"]=Flow})})},["streamSettings"]=stream})
        }.ToJsonString();
    }
    public static IReadOnlyList<Route> Subscription(string text)
    {
        if(Encoding.UTF8.GetByteCount(text)>2*1024*1024) throw new FormatException("Лимит подписки 2 МБ");
        if(!text.Contains("vless://")) {
            try {var b=text.Trim().Replace('-','+').Replace('_','/');text=Encoding.UTF8.GetString(Convert.FromBase64String(b.PadRight((b.Length+3)/4*4,'=')));}
            catch(FormatException){throw new FormatException("Ожидается текстовая или Base64-подписка");}
        }
        var rows=new List<Route>();
        foreach(var line in text.Split('\n')) {try {rows.Add(Parse(line));}catch(Exception e) when(e is FormatException or ArgumentException){} if(rows.Count>=300) break;}
        return rows.Distinct().ToList();
    }
    public static IReadOnlyList<Route> Manifest(string json,long now)
    {
        if(Encoding.UTF8.GetByteCount(json)>2*1024*1024) throw new FormatException("Лимит манифеста 2 МБ");
        using var doc=JsonDocument.Parse(json);var root=doc.RootElement;
        var stamp=root.GetProperty("stamp").GetInt64();var expires=root.GetProperty("expires").GetInt64();
        if(root.GetProperty("schema").GetInt32()!=1 || stamp>now+300 || expires<=now || expires<stamp || expires-stamp>86400)
            throw new FormatException("Устаревший или неизвестный манифест");
        var nodes=root.GetProperty("nodes");if(nodes.GetArrayLength()>300)throw new FormatException("Слишком много конфигураций");
        var rows=new List<(Route route,int speed)>();
        foreach(var n in nodes.EnumerateArray()) {
            if(n.GetProperty("tier").GetString() is not ("gold" or "silver"))continue;
            try {
                var route=Parse(n.GetProperty("link").GetString()!);int speed=0;
                if(n.TryGetProperty("speed_measured_at",out var at) && at.TryGetInt64(out var date) && date>=now-3600 && date<=now && n.TryGetProperty("kbps",out var kb) && kb.TryGetInt32(out var value))speed=Math.Clamp(value,0,10000000);
                rows.Add((route,speed));
            }catch(Exception e) when(e is FormatException or ArgumentException){}
        }
        return rows.OrderByDescending(x=>x.speed).Select(x=>x.route).Distinct().ToList();
    }
}
