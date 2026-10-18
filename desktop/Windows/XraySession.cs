using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using VlessCard;

/// Every trial owns its process, credentials, sockets and temporary configuration.
sealed class XraySession:IDisposable
{
    Process? process;string? config;readonly HttpClient client;
    public int Port {get;}public string User {get;}=RandomNumberGenerator.GetHexString(16);
    public string Password {get;}=RandomNumberGenerator.GetHexString(32);
    XraySession(int port){Port=port;client=new(new HttpClientHandler{Proxy=new WebProxy($"socks5://127.0.0.1:{Port}"){Credentials=new NetworkCredential(User,Password)},UseProxy=true,AllowAutoRedirect=false,AutomaticDecompression=DecompressionMethods.None}){Timeout=Timeout.InfiniteTimeSpan};}
    public static async Task<XraySession> OpenAsync(Route route,CancellationToken ct){
        var exe=Path.Combine(AppContext.BaseDirectory,"xray.exe");
        var expected=File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"xray.exe.sha256")).Trim();
        await using(var file=File.OpenRead(exe))if(!Convert.ToHexString(await SHA256.HashDataAsync(file,ct)).Equals(expected,StringComparison.OrdinalIgnoreCase))throw new IOException("Core verification failed");
        var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();var port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
        var s=new XraySession(port);
        try{
            var folder=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"VlessCard","sessions");Directory.CreateDirectory(folder);
            s.config=Path.Combine(folder,Guid.NewGuid()+".json");await File.WriteAllTextAsync(s.config,route.Config(port,s.User,s.Password),ct);
            s.process=Process.Start(new ProcessStartInfo(exe){UseShellExecute=false,CreateNoWindow=true,ArgumentList={"run","-c",s.config}})??throw new IOException();
            ChildProcessJob.Attach(s.process);
            for(int i=0;i<30;i++){
                ct.ThrowIfCancellationRequested();if(s.process.HasExited)throw new IOException();
                try{using var socket=new TcpClient();await socket.ConnectAsync(IPAddress.Loopback,port,ct);return s;}
                catch(SocketException){await Task.Delay(75,ct);}
            }
            throw new IOException("Local listener did not start");
        }catch{s.Dispose();throw;}
    }
    public async Task<int> VerifyAsync(CancellationToken ct){
        var watch=Stopwatch.StartNew();using var response=await client.GetAsync("https://www.gstatic.com/generate_204",HttpCompletionOption.ResponseHeadersRead,ct);
        if(response.StatusCode!=HttpStatusCode.NoContent)throw new IOException("HTTPS verification failed");return (int)Math.Max(1,watch.ElapsedMilliseconds);
    }
    public async Task<ProbeResult> MeasureAsync(CancellationToken ct){
        int latency=await VerifyAsync(ct);int kbps=0;
        using var transfer=CancellationTokenSource.CreateLinkedTokenSource(ct);transfer.CancelAfter(TimeSpan.FromMilliseconds(2400));
        try{
            const int size=2*1024*1024;var watch=Stopwatch.StartNew();
            using var response=await client.GetAsync($"https://speed.cloudflare.com/__down?bytes={size}&nonce={Guid.NewGuid():N}",HttpCompletionOption.ResponseHeadersRead,transfer.Token);
            response.EnsureSuccessStatusCode();if(response.Content.Headers.ContentEncoding.Count>0)return new(true,0,latency);
            using var stream=await response.Content.ReadAsStreamAsync(transfer.Token);var buffer=new byte[16384];int bytes=0,n;
            try{while(bytes<size&&(n=await stream.ReadAsync(buffer.AsMemory(0,Math.Min(buffer.Length,size-bytes)),transfer.Token))>0)bytes+=n;}
            catch(OperationCanceledException) when(!ct.IsCancellationRequested){}
            // A completed 204 proves availability only. Require a meaningful payload to rate throughput.
            if(bytes>=65536)kbps=(int)Math.Clamp(bytes*8L/Math.Max(1,watch.ElapsedMilliseconds),1,10000000);
        }catch(OperationCanceledException) when(!ct.IsCancellationRequested){}
        catch(Exception e) when(e is IOException or HttpRequestException){}
        ct.ThrowIfCancellationRequested();return new(true,kbps,latency);
    }
    public void Dispose(){client.Dispose();try{if(process is {HasExited:false}){process.Kill(true);process.WaitForExit(3000);}}catch{}finally{process?.Dispose();process=null;}
        if(config!=null){try{File.Delete(config);}catch{}config=null;}
    }
}
