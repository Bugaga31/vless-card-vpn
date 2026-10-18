using System.Net.NetworkInformation;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using VlessCard;

ApplicationConfiguration.Initialize();Application.Run(new MainForm());
sealed class MainForm:Form
{
    readonly TextBox input=new(){Multiline=true,Height=75,Dock=DockStyle.Top,PlaceholderText="Вставьте VLESS-ссылки или HTTPS-адрес подписки"};
    readonly ComboBox routes=new(){Dock=DockStyle.Top,DropDownStyle=ComboBoxStyle.DropDownList};
    readonly Label status=new(){Dock=DockStyle.Fill,Text="Windows preview: локальный SOCKS5, не системный VPN. Поддерживаются VLESS TCP/TLS/Reality. Настройки Windows не меняются.",Padding=new Padding(12)};
    readonly Button connect=new(){Text="Подключить Xray",AutoSize=true};
    readonly Button import=new(){Text="Импорт",AutoSize=true};readonly Button cloud=new(){Text="Публичное облако",AutoSize=true};
    XraySession? session;CancellationTokenSource? operation;readonly AutoSelector selector=new();
    readonly Button automatic=new(){Text="Авто · найти быстрее",AutoSize=true};readonly Button cancel=new(){Text="Отменить поиск",AutoSize=true,Enabled=false};
    readonly TextBox proxy=new(){Dock=DockStyle.Bottom,ReadOnly=true,Height=50,Multiline=true};
    public MainForm(){
        Text="VLESS Card · Xray · Windows preview 0.2.0";Width=780;Height=480;MinimumSize=new Size(600,360);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Top,Height=85};buttons.Controls.AddRange([import,cloud,connect,automatic,cancel]);
        Controls.Add(status);Controls.Add(proxy);Controls.Add(buttons);Controls.Add(routes);Controls.Add(input);
        import.Click+=async(_,_)=>await LoadRoutes(false);cloud.Click+=async(_,_)=>await LoadRoutes(true);
        connect.Click+=async(_,_)=>{if(session!=null){Stop();status.Text="Xray остановлен";return;}await Start();};
        automatic.Click+=async(_,_)=>await AutoConnect();cancel.Click+=(_,_)=>operation?.Cancel();
        FormClosing+=(_,_)=>{operation?.Cancel();Stop();};
    }
    async Task<string> Fetch(string url,CancellationToken ct,int seconds=12){
        var uri=new Uri(url);if(uri.Scheme!="https" || !string.IsNullOrEmpty(uri.UserInfo))throw new FormatException("Нужен HTTPS-адрес без учётных данных");
        using var client=new HttpClient(new HttpClientHandler{AllowAutoRedirect=false}){Timeout=TimeSpan.FromSeconds(seconds)};
        using var response=await client.GetAsync(uri,HttpCompletionOption.ResponseHeadersRead,ct);response.EnsureSuccessStatusCode();
        using var stream=await response.Content.ReadAsStreamAsync(ct);using var buffer=new MemoryStream();var chunk=new byte[8192];int n;
        while((n=await stream.ReadAsync(chunk,ct))>0){if(buffer.Length+n>2*1024*1024)throw new FormatException("Ответ больше 2 МБ");buffer.Write(chunk,0,n);}
        return System.Text.Encoding.UTF8.GetString(buffer.ToArray());
    }
    async Task<string> FetchPublicCloud(CancellationToken ct){
        foreach(var url in new[]{"https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/cloud-manifest.json","https://cdn.jsdelivr.net/gh/Bugaga31/vless-card-vpn@servers/cloud-manifest.json"}) {
            ct.ThrowIfCancellationRequested();
            try{var text=await Fetch(url,ct,4);Route.Manifest(text,DateTimeOffset.UtcNow.ToUnixTimeSeconds());return text;}
            catch(OperationCanceledException) when(!ct.IsCancellationRequested){}
            catch(Exception e) when(e is HttpRequestException or FormatException or System.Text.Json.JsonException or InvalidOperationException or KeyNotFoundException){}
        }
        throw new IOException("Public mirrors unavailable");
    }
    async Task LoadRoutes(bool fromCloud){
        if(operation!=null)return;operation=new();Busy(true);
        try{
            var text=input.Text.Trim();if(fromCloud)text=await FetchPublicCloud(operation.Token);
            else if(text.StartsWith("https://",StringComparison.OrdinalIgnoreCase))text=await Fetch(text,operation.Token);
            var rows=fromCloud?Route.Manifest(text,DateTimeOffset.UtcNow.ToUnixTimeSeconds()):Route.Subscription(text);
            routes.Items.Clear();routes.Items.AddRange(rows.Cast<object>().ToArray());if(rows.Count>0)routes.SelectedIndex=0;
            input.Clear();status.Text=$"Импортировано {rows.Count} поддерживаемых конфигураций. Облачный порядок — подсказка, а не замер скорости на этом ПК.";
        }catch{status.Text="Не удалось загрузить поддерживаемые конфигурации. Проверьте адрес, сеть и формат. Секреты в журнал не записываются.";}
        finally{operation.Dispose();operation=null;Busy(false);}
    }
    void Busy(bool value){import.Enabled=cloud.Enabled=connect.Enabled=automatic.Enabled=!value;cancel.Enabled=value;}
    static string NetworkKey()=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("|",NetworkInterface.GetAllNetworkInterfaces()
        .Where(n=>n.OperationalStatus==OperationalStatus.Up&&n.NetworkInterfaceType!=NetworkInterfaceType.Loopback)
        .Select(n=>n.Id+":"+string.Join(",",n.GetIPProperties().GatewayAddresses.Select(g=>g.Address.ToString()))).Order()))));
    void Adopt(XraySession ready,Route route){var old=session;session=ready;old?.Dispose();routes.SelectedItem=route;
        proxy.Text=$"SOCKS5: 127.0.0.1:{ready.Port}\r\nЛогин: {ready.User}   Пароль: {ready.Password}";connect.Text="Отключить";
    }
    async Task Start(){
        if(operation!=null||routes.SelectedItem is not Route route)return;operation=new();operation.CancelAfter(TimeSpan.FromSeconds(12));Busy(true);XraySession? trial=null;
        try{trial=await XraySession.OpenAsync(route,operation.Token);await trial.VerifyAsync(operation.Token);operation.Token.ThrowIfCancellationRequested();Adopt(trial,route);trial=null;
            status.Text="HTTPS через Xray подтверждён. Настройте SOCKS5 в нужном приложении. Для сравнения скорости нажмите Авто.";
        }catch(OperationCanceledException){status.Text="Проверка отменена; прежнее соединение сохранено.";}
        catch{status.Text="HTTPS не подтверждён. Попробуйте Авто; прежнее соединение сохранено.";}
        finally{trial?.Dispose();operation.Dispose();operation=null;Busy(false);}
    }
    async Task AutoConnect(){
        if(operation!=null)return;operation=new();Busy(true);var trials=new Dictionary<Route,XraySession>();var network="";
        try{
            network=NetworkKey();
            if(routes.Items.Count==0){
                status.Text="Получаю публичные конфигурации…";
                var text=await FetchPublicCloud(operation.Token);
                routes.Items.AddRange(Route.Manifest(text,DateTimeOffset.UtcNow.ToUnixTimeSeconds()).Cast<object>().ToArray());
            }
            var result=await selector.RunAsync(routes.Items.Cast<Route>().ToList(),network,
                async(r,ct)=>{using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(1000);
                    try{using var tcp=new TcpClient();await tcp.ConnectAsync(r.Host,r.Port,timeout.Token);return true;}
                    catch(OperationCanceledException) when(!ct.IsCancellationRequested){return false;}catch(SocketException){return false;}},
                async(r,ct)=>{XraySession? trial=null;try{trial=await XraySession.OpenAsync(r,ct);var measured=await trial.MeasureAsync(ct);trials[r]=trial;trial=null;return measured;}finally{trial?.Dispose();}},
                operation.Token,text=>status.Text=text);
            operation.Token.ThrowIfCancellationRequested();
            if(NetworkKey()!=network)throw new IOException("Network changed");
            if(result.Winner is not Route winner || result.Measurement is not ProbeResult sample || !trials.Remove(winner,out var best)){
                status.Text="Проверенные маршруты не прошли HTTPS. Прежнее соединение не выключено. Можно импортировать другую подписку и повторить Авто.";return;
            }
            Adopt(best,winner);
            status.Text=sample.Kbps>0?$"Лучший из {result.Compared} проверенных: {sample.Kbps/1000.0:F1} Мбит/с · HTTPS {sample.LatencyMs} мс. Облачный пинг не решает выбор. SOCKS5 указан ниже.":
                "HTTPS работает, но скорость измерить не удалось. Это запасной маршрут, не подтверждённый самый быстрый. SOCKS5 указан ниже.";
        }catch(OperationCanceledException){status.Text="Автоподбор отменён. Прежнее соединение сохранено.";}
        catch{status.Text="Автоподбор не завершён: проверьте сеть и подписку. Прежнее соединение сохранено.";}
        finally{foreach(var trial in trials.Values)trial.Dispose();operation.Dispose();operation=null;Busy(false);}
    }
    void Stop(){session?.Dispose();session=null;proxy.Clear();connect.Text="Подключить Xray";}
}
