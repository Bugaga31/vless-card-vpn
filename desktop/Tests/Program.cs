using VlessCard;
using System.Text;
using System.Text.Json;
var link="vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls&type=tcp&sni=example.com#Test";
int tests=0;
void Check(bool ok){tests++;if(!ok)throw new Exception("Check "+tests+" failed");}
void Reject(string s){bool rejected=false;try{Route.Parse(s);}catch{rejected=true;}Check(rejected);}
var r=Route.Parse(link);Check(r.Sni=="example.com");Check(r.Port==443);
using(var d=JsonDocument.Parse(r.Config(12345,"user","secret"))){Check(d.RootElement.GetProperty("inbounds")[0].GetProperty("listen").GetString()=="127.0.0.1");Check(d.RootElement.GetProperty("inbounds")[0].GetProperty("settings").GetProperty("auth").GetString()=="password");}
Reject(link.Replace("type=tcp","type=ws"));Reject(link.Replace("security=tls","security=none"));Reject(link.Replace("security=tls","security=reality"));
Reject(link.Replace("type=tcp","type=tcp&allowInsecure=1"));Reject(link.Replace("00000000-0000-0000-0000-000000000001","invalid"));
Check(Route.Subscription(link+"\n"+link).Count==1);
Check(Route.Subscription(Convert.ToBase64String(Encoding.UTF8.GetBytes(link))).Count==1);
var manifest=JsonSerializer.Serialize(new{schema=1,stamp=10000,expires=11000,nodes=new[]{new{tier="silver",link,kbps=9000,speed_measured_at=10000}}});
Check(Route.Manifest(manifest,10000).Count==1);
bool expired=false;try{Route.Manifest(manifest,12000);}catch{expired=true;}Check(expired);
Directory.CreateDirectory("test-output");File.WriteAllText("test-output/xray-smoke.json",r.Config(12345,"test","test"));
Console.WriteLine($"{tests} desktop core checks passed");
