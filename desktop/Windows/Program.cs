using System.Diagnostics;
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
    Process? core;string? config;CancellationTokenSource? operation;
    readonly TextBox proxy=new(){Dock=DockStyle.Bottom,ReadOnly=true,Height=50,Multiline=true};
    public MainForm(){
        Text="VLESS Card · Xray · Windows preview 0.1.0";Width=740;Height=430;MinimumSize=new Size(600,360);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Top,Height=45};buttons.Controls.AddRange([import,cloud,connect]);
        Controls.Add(status);Controls.Add(proxy);Controls.Add(buttons);Controls.Add(routes);Controls.Add(input);
        import.Click+=async(_,_)=>await LoadRoutes(false);cloud.Click+=async(_,_)=>await LoadRoutes(true);
        connect.Click+=async(_,_)=>{if(core!=null){Stop();status.Text="Xray остановлен";return;}await Start();};
        FormClosing+=(_,_)=>{operation?.Cancel();Stop();};
    }
    async Task<string> Fetch(string url,CancellationToken ct){
        var uri=new Uri(url);if(uri.Scheme!="https" || !string.IsNullOrEmpty(uri.UserInfo))throw new FormatException("Нужен HTTPS-адрес без учётных данных");
        using var client=new HttpClient(new HttpClientHandler{AllowAutoRedirect=false}){Timeout=TimeSpan.FromSeconds(12)};
        using var response=await client.GetAsync(uri,HttpCompletionOption.ResponseHeadersRead,ct);response.EnsureSuccessStatusCode();
        using var stream=await response.Content.ReadAsStreamAsync(ct);using var buffer=new MemoryStream();var chunk=new byte[8192];int n;
        while((n=await stream.ReadAsync(chunk,ct))>0){if(buffer.Length+n>2*1024*1024)throw new FormatException("Ответ больше 2 МБ");buffer.Write(chunk,0,n);}
        return System.Text.Encoding.UTF8.GetString(buffer.ToArray());
    }
    async Task LoadRoutes(bool fromCloud){
        if(operation!=null)return;operation=new();import.Enabled=cloud.Enabled=connect.Enabled=false;
        try{
            var text=input.Text.Trim();if(fromCloud)text=await Fetch("https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/cloud-manifest.json",operation.Token);
            else if(text.StartsWith("https://",StringComparison.OrdinalIgnoreCase))text=await Fetch(text,operation.Token);
            var rows=fromCloud?Route.Manifest(text,DateTimeOffset.UtcNow.ToUnixTimeSeconds()):Route.Subscription(text);
            routes.Items.Clear();routes.Items.AddRange(rows.Cast<object>().ToArray());if(rows.Count>0)routes.SelectedIndex=0;
            input.Clear();status.Text=$"Импортировано {rows.Count} поддерживаемых конфигураций. Облачный порядок — подсказка, а не замер скорости на этом ПК.";
        }catch{status.Text="Не удалось загрузить поддерживаемые конфигурации. Проверьте адрес, сеть и формат. Секреты в журнал не записываются.";}
        finally{operation.Dispose();operation=null;import.Enabled=cloud.Enabled=connect.Enabled=true;}
    }
    async Task Start(){
        if(operation!=null || routes.SelectedItem is not Route route)return;operation=new();connect.Enabled=import.Enabled=cloud.Enabled=false;
        try{
            var exe=Path.Combine(AppContext.BaseDirectory,"xray.exe");if(!File.Exists(exe))throw new IOException();
            // Independent verification on each launch: pin the bundled executable, not merely its download archive.
            var expected=File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"xray.exe.sha256")).Trim();
            using(var file=File.OpenRead(exe))if(!Convert.ToHexString(SHA256.HashData(file)).Equals(expected,StringComparison.OrdinalIgnoreCase))throw new IOException();
            var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();var port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
            var user=RandomNumberGenerator.GetHexString(16);var pass=RandomNumberGenerator.GetHexString(32);
            var folder=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"VlessCard","sessions");Directory.CreateDirectory(folder);
            config=Path.Combine(folder,Guid.NewGuid()+".json");File.WriteAllText(config,route.Config(port,user,pass));
            core=Process.Start(new ProcessStartInfo(exe){UseShellExecute=false,CreateNoWindow=true,ArgumentList={"run","-c",config}})??throw new IOException();
            bool listening=false;for(int i=0;i<30;i++){
                operation.Token.ThrowIfCancellationRequested();if(core.HasExited)throw new IOException();
                try{using var socket=new TcpClient();await socket.ConnectAsync(IPAddress.Loopback,port,operation.Token);listening=true;break;}catch(SocketException){await Task.Delay(100,operation.Token);}
            }
            if(!listening)throw new IOException();
            using var handler=new HttpClientHandler{Proxy=new WebProxy($"socks5://127.0.0.1:{port}"){Credentials=new NetworkCredential(user,pass)},UseProxy=true,AllowAutoRedirect=false};
            using var client=new HttpClient(handler){Timeout=TimeSpan.FromSeconds(8)};
            using var response=await client.GetAsync("https://www.gstatic.com/generate_204",operation.Token);
            if(response.StatusCode!=HttpStatusCode.NoContent)throw new IOException();
            proxy.Text=$"SOCKS5: 127.0.0.1:{port}\r\nЛогин: {user}   Пароль: {pass}";
            status.Text="HTTPS через Xray подтверждён. Укажите этот SOCKS5 в нужном приложении. Это проверка доступности, не тест пропускной способности. Данные подключения хранятся только в текущей сессии.";connect.Text="Отключить";
        }catch{Stop();status.Text="Маршрут не подтвердил HTTPS или ядро не прошло проверку. Попробуйте другую конфигурацию. Системный прокси не менялся.";}
        finally{operation.Dispose();operation=null;connect.Enabled=import.Enabled=cloud.Enabled=true;}
    }
    void Stop(){try{if(core is {HasExited:false}){core.Kill(true);core.WaitForExit(3000);}}catch{}finally{core?.Dispose();core=null;}
        if(config!=null){try{File.Delete(config);}catch{}config=null;}proxy.Clear();connect.Text="Подключить Xray";
    }
}
