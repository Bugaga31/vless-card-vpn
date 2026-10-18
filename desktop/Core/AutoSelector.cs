namespace VlessCard;

public record ProbeResult(bool HttpsOk, int Kbps, int LatencyMs);
public record Selection(Route? Winner, ProbeResult? Measurement, int Compared, bool TimedOut);

/// Bounded real-throughput selection: a low TCP ping is never a certified download speed.
public sealed class AutoSelector
{
    readonly Dictionary<Route,(ProbeResult sample,DateTimeOffset at)> samples=new();
    readonly Dictionary<Route,DateTimeOffset> cooldown=new();
    string network="";int cursor;
    public static bool Better(ProbeResult a,ProbeResult b)=>a.HttpsOk && (!b.HttpsOk || a.Kbps>b.Kbps || a.Kbps==b.Kbps && a.LatencyMs<b.LatencyMs);
    public async Task<Selection> RunAsync(IReadOnlyList<Route> catalog,string networkKey,
        Func<Route,CancellationToken,Task<bool>> tcp,Func<Route,CancellationToken,Task<ProbeResult>> probe,
        CancellationToken cancel,Action<string>? progress=null,TimeSpan? budget=null)
    {
        if(network!=networkKey){samples.Clear();cooldown.Clear();network=networkKey;cursor=0;}
        var now=DateTimeOffset.UtcNow;
        if(samples.Count>600)samples.Clear();if(cooldown.Count>600)cooldown.Clear();
        var available=catalog.Distinct().Where(r=>!cooldown.TryGetValue(r,out var till)||till<=now).ToList();
        var leaders=available.Where(r=>samples.TryGetValue(r,out var s)&&now-s.at<TimeSpan.FromMinutes(5)).OrderByDescending(r=>samples[r].sample.Kbps).Take(2);
        var exploration=available.Skip(available.Count==0?0:cursor%available.Count).Concat(available).Take(6);
        var candidates=leaders.Take(1).Concat(exploration.Take(1)).Concat(available.Take(12)).Concat(exploration).Distinct().Take(24).ToList();
        cursor+=6;
        using var total=CancellationTokenSource.CreateLinkedTokenSource(cancel);total.CancelAfter(budget??TimeSpan.FromSeconds(24));
        Route? winner=null;ProbeResult? best=null;int compared=0;bool timedOut=false;
        try{
            progress?.Invoke($"Быстрый отсев: до {candidates.Count} конфигураций");
            var reachable=new System.Collections.Concurrent.ConcurrentDictionary<Route,bool>();
            using(var phase=CancellationTokenSource.CreateLinkedTokenSource(total.Token)){
                phase.CancelAfter(TimeSpan.FromSeconds(5));
                try{await Parallel.ForEachAsync(candidates,new ParallelOptions{MaxDegreeOfParallelism=4,CancellationToken=phase.Token},async(r,ct)=>{
                    try{if(await tcp(r,ct))reachable[r]=true;}catch(OperationCanceledException) when(!cancel.IsCancellationRequested){}catch(Exception e) when(e is System.IO.IOException or System.Net.Sockets.SocketException){}
                });}catch(OperationCanceledException) when(!cancel.IsCancellationRequested){}
            }
            // Preserve queue order; TCP completion time does not mean download speed.
            var ready=candidates.Where(reachable.ContainsKey).ToList();
            var shortlist=ready.DistinctBy(r=>r.Host.ToLowerInvariant()).Concat(ready).Distinct().Take(4).ToList();
            foreach(var route in shortlist){
                total.Token.ThrowIfCancellationRequested();progress?.Invoke($"Xray: сравниваю скорость {compared+1}/{shortlist.Count}");
                ProbeResult result;
                using var one=CancellationTokenSource.CreateLinkedTokenSource(total.Token);one.CancelAfter(TimeSpan.FromSeconds(5));
                try{result=await probe(route,one.Token);}
                catch(OperationCanceledException) when(!total.IsCancellationRequested){continue;}
                catch(Exception e) when(e is System.IO.IOException or System.Net.Http.HttpRequestException or System.Net.Sockets.SocketException){result=new(false,0,0);}
                compared++;
                if(!result.HttpsOk){cooldown[route]=DateTimeOffset.UtcNow.AddMinutes(2);continue;}
                samples[route]=(result,DateTimeOffset.UtcNow);
                if(best==null||Better(result,best)){winner=route;best=result;}
            }
        }catch(OperationCanceledException) when(!cancel.IsCancellationRequested){timedOut=true;}
        cancel.ThrowIfCancellationRequested();return new(winner,best,compared,timedOut);
    }
}
