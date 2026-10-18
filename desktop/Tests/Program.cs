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
Check(AutoSelector.Better(new(true,20000,250),new(true,500,10)));
Check(!AutoSelector.Better(new(true,0,5),new(true,500,300)));
Check(!AutoSelector.Better(new(false,90000,5),new(true,500,300)));
var slow=r;var fast=r with{Host="fast.example",Name="Fast"};var selector=new AutoSelector();int active=0,peak=0;
var selected=await selector.RunAsync(new[]{slow,fast},"network-A",(_,_)=>Task.FromResult(true),async(route,ct)=>{active++;peak=Math.Max(peak,active);await Task.Delay(1,ct);active--;return route==fast?new(true,20000,250):new(true,500,10);},CancellationToken.None);
Check(selected.Winner==fast);Check(peak==1);Check(selected.Compared==2);
using(var canceled=new CancellationTokenSource()){canceled.Cancel();bool propagated=false;try{await selector.RunAsync(new[]{r},"network-B",(_,_)=>Task.FromResult(true),(_,_)=>Task.FromResult(new ProbeResult(true,9000,10)),canceled.Token);}catch(OperationCanceledException){propagated=true;}Check(propagated);}
var unavailable=await new AutoSelector().RunAsync(new[]{r},"network-A",(_,_)=>Task.FromResult(false),(_,_)=>throw new Exception("Should not test closed port"),CancellationToken.None);Check(unavailable.Winner==null);
var fallback=await new AutoSelector().RunAsync(new[]{r},"network-A",(_,_)=>Task.FromResult(true),(_,_)=>Task.FromResult(new ProbeResult(true,0,50)),CancellationToken.None);Check(fallback.Winner==r&&fallback.Measurement!.Kbps==0);
Console.WriteLine($"{tests} desktop checks including throughput selection passed");
var paused=new AutoSelector();int probes=0;
await paused.RunAsync(new[]{r},"net-A",(_,_)=>Task.FromResult(true),(_,_)=>{probes++;return Task.FromResult(new ProbeResult(false,0,0));},CancellationToken.None);
await paused.RunAsync(new[]{r},"net-A",(_,_)=>Task.FromResult(true),(_,_)=>{probes++;return Task.FromResult(new ProbeResult(true,9000,50));},CancellationToken.None);Check(probes==1);
await paused.RunAsync(new[]{r},"net-B",(_,_)=>Task.FromResult(true),(_,_)=>{probes++;return Task.FromResult(new ProbeResult(true,9000,50));},CancellationToken.None);Check(probes==2);
var many=Enumerable.Range(0,40).Select(i=>r with{Host=$"host{i}.example",Name=$"R{i}"}).ToArray();int tcpActive=0,tcpPeak=0,tcpTotal=0,deepTotal=0;
await new AutoSelector().RunAsync(many,"net-A",async(_,ct)=>{Interlocked.Increment(ref tcpTotal);var x=Interlocked.Increment(ref tcpActive);int prev;do{prev=tcpPeak;if(prev>=x)break;}while(Interlocked.CompareExchange(ref tcpPeak,x,prev)!=prev);await Task.Delay(2,ct);Interlocked.Decrement(ref tcpActive);return true;},(_,_)=>{deepTotal++;return Task.FromResult(new ProbeResult(true,1000,20));},CancellationToken.None);
Check(tcpPeak<=4);Check(tcpTotal<=24);Check(deepTotal<=4);
var rotating=new AutoSelector();var tried=new List<Route>();
for(int i=0;i<2;i++)await rotating.RunAsync(many,"net-A",(_,_)=>Task.FromResult(true),(route,_)=>{tried.Add(route);return Task.FromResult(new ProbeResult(true,1000,20));},CancellationToken.None);
Check(tried.Contains(many[6]));
var bounded=await new AutoSelector().RunAsync(new[]{r,fast},"net-A",(_,_)=>Task.FromResult(true),async(route,ct)=>{if(route==r)return new(true,5000,80);await Task.Delay(5000,ct);return new(true,9000,10);},CancellationToken.None,budget:TimeSpan.FromMilliseconds(100));
Check(bounded.TimedOut&&bounded.Winner==r);
Console.WriteLine($"{tests} desktop checks passed (bounded probes, cancellation, network cooldown, exploration)");
