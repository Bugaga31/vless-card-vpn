using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Themes.Fluent;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using VlessCard;

internal static class Program {
 [STAThread] public static int Main(string[] args) {
  if(args.Length==1&&args[0]=="--licenses") {foreach(var name in new[]{"VlessCard.Notices","VlessCard.XrayLicense"}) {using var stream=typeof(Program).Assembly.GetManifestResourceStream(name);if(stream!=null){using var reader=new StreamReader(stream);Console.WriteLine(reader.ReadToEnd());}}return 0;}
  if(args.Length==2&&args[0]=="--verify-bundle") {
   try {using var limit=new CancellationTokenSource(TimeSpan.FromSeconds(15));var core=BundledCore.ResolveAsync(limit.Token).GetAwaiter().GetResult();
    using var p=Process.Start(new ProcessStartInfo(core){UseShellExecute=false,ArgumentList={"run","-test","-c",args[1]}})!;
    if(!p.WaitForExit(10000)){p.Kill(true);return 2;}return p.ExitCode;
   }catch {return 3;}
  }
  return AppBuilder.Configure<DesktopApp>().UsePlatformDetect().StartWithClassicDesktopLifetime(args);
 }
}
public sealed class DesktopApp:Application {
 public override void Initialize(){Styles.Add(new FluentTheme());}
 public override void OnFrameworkInitializationCompleted(){if(ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)desktop.MainWindow=new MainWindow();base.OnFrameworkInitializationCompleted();}
}
public sealed class MainWindow:Window {
 readonly TextBox input=new(){AcceptsReturn=true,MinHeight=92,Watermark="VLESS-ссылки или HTTPS-адрес подписки",TextWrapping=TextWrapping.Wrap};
 readonly ComboBox routes=new(){HorizontalAlignment=HorizontalAlignment.Stretch,PlaceholderText="Импортируйте серверы"};
 readonly TextBlock status=new(){Text="Готов к импорту. Настройки сети и системный прокси не меняются.",TextWrapping=TextWrapping.Wrap,MinHeight=56};
 readonly TextBox proxy=new(){IsReadOnly=true,AcceptsReturn=true,MinHeight=72,Watermark="После подключения здесь появятся адрес и данные SOCKS5",TextWrapping=TextWrapping.Wrap};
 readonly Button import=new(){Content="Импорт",MinHeight=44};readonly Button cloud=new(){Content="Публичное облако",MinHeight=44};
 readonly Button connect=new(){Content="Подключить",MinHeight=44};readonly Button automatic=new(){Content="Авто · сравнить",MinHeight=44};readonly Button cancel=new(){Content="Отмена",MinHeight=44,IsEnabled=false};
 readonly Button copy=new(){Content="Копировать SOCKS5",MinHeight=44,IsEnabled=false};
 readonly AutoSelector selector=new();readonly List<Route> catalog=new();XraySession? session;CancellationTokenSource? operation;bool closed;
 public MainWindow(){
  Title="VLESS Card · Desktop preview 0.3.0";Width=760;Height=730;MinWidth=560;MinHeight=540;
  var heading=new TextBlock{Text="VLESS Card",FontSize=28,FontWeight=FontWeight.SemiBold};
  var subtitle=new TextBlock{Text="Desktop preview 0.3.0 · Xray",FontSize=14,Opacity=.7};
  var scope=new TextBlock{Text="Локальный SOCKS5 · не системный VPN",FontSize=16,FontWeight=FontWeight.SemiBold,TextWrapping=TextWrapping.Wrap};
  var hint=new TextBlock{Text="VLESS TCP/raw + TLS/Reality. Настройте SOCKS5 в нужном приложении. Это предварительная версия без TUN, WARP и Android-маскировок.",TextWrapping=TextWrapping.Wrap,Opacity=.8};
  var commands=new WrapPanel{Orientation=Orientation.Horizontal};foreach(var b in new[]{import,cloud,connect,automatic,cancel}){b.Margin=new Thickness(0,0,8,8);commands.Children.Add(b);}
  var panel=new StackPanel{Spacing=12,Margin=new Thickness(24)};panel.Children.Add(heading);panel.Children.Add(subtitle);
  panel.Children.Add(new Border{Padding=new Thickness(16),CornerRadius=new CornerRadius(10),BorderThickness=new Thickness(1),BorderBrush=Brushes.Gray,Child=new StackPanel{Spacing=8,Children={scope,hint}}});
  panel.Children.Add(input);panel.Children.Add(routes);panel.Children.Add(commands);panel.Children.Add(status);panel.Children.Add(proxy);panel.Children.Add(copy);
  panel.Children.Add(new TextBlock{Text="Подписки загружаются напрямую по HTTPS. Конфигурация временно хранится в профиле пользователя. EXE пока без подписи издателя.",TextWrapping=TextWrapping.Wrap,FontSize=14,Opacity=.7});Content=new ScrollViewer{Content=panel};
  import.Click+=async(_,_)=>await Load(false);cloud.Click+=async(_,_)=>await Load(true);automatic.Click+=async(_,_)=>await Auto();cancel.Click+=(_,_)=>operation?.Cancel();
  connect.Click+=async(_,_)=>{if(session!=null){Stop();status.Text="Отключено. Системные настройки не менялись.";}else await Start();};
  copy.Click+=async(_,_)=>{if(Clipboard!=null&&session!=null)await Clipboard.SetTextAsync(proxy.Text??"");};
  Closed+=(_,_)=>{closed=true;operation?.Cancel();Stop();};
 }
 void Busy(bool value){import.IsEnabled=cloud.IsEnabled=connect.IsEnabled=automatic.IsEnabled=!value;cancel.IsEnabled=value;copy.IsEnabled=!value&&session!=null;}
 void Status(string text){if(!closed)status.Text=text;}
 static string NetworkKey()=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("|",NetworkInterface.GetAllNetworkInterfaces().Where(n=>n.OperationalStatus==OperationalStatus.Up&&n.NetworkInterfaceType!=NetworkInterfaceType.Loopback).Select(n=>n.Id+":"+string.Join(",",n.GetIPProperties().GatewayAddresses.Select(g=>g.Address.ToString()))).Order()))));
 async Task<string> Fetch(string url,CancellationToken ct,int seconds=12){
  var uri=new Uri(url);if(uri.Scheme!="https"||!string.IsNullOrEmpty(uri.UserInfo))throw new FormatException();
  using var client=new HttpClient(new HttpClientHandler{AllowAutoRedirect=false}){Timeout=TimeSpan.FromSeconds(seconds)};
  using var response=await client.GetAsync(uri,HttpCompletionOption.ResponseHeadersRead,ct);response.EnsureSuccessStatusCode();
  using var stream=await response.Content.ReadAsStreamAsync(ct);using var buffer=new MemoryStream();var bytes=new byte[8192];int n;
  while((n=await stream.ReadAsync(bytes,ct))>0){if(buffer.Length+n>2*1024*1024)throw new FormatException();buffer.Write(bytes,0,n);}return Encoding.UTF8.GetString(buffer.ToArray());
 }
 async Task<string> PublicCloud(CancellationToken ct){
  foreach(var url in new[]{"https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/cloud-manifest.json","https://cdn.jsdelivr.net/gh/Bugaga31/vless-card-vpn@servers/cloud-manifest.json"}){
   ct.ThrowIfCancellationRequested();try{var text=await Fetch(url,ct,4);Route.Manifest(text,DateTimeOffset.UtcNow.ToUnixTimeSeconds());return text;}
   catch(OperationCanceledException) when(!ct.IsCancellationRequested){}catch(Exception e) when(e is HttpRequestException or FormatException or System.Text.Json.JsonException or InvalidOperationException or KeyNotFoundException){}
  }throw new IOException();
 }
 void SetRoutes(IEnumerable<Route> list){catalog.Clear();catalog.AddRange(list);routes.ItemsSource=null;routes.ItemsSource=catalog.ToArray();if(catalog.Count>0)routes.SelectedIndex=0;}
 async Task Load(bool fromCloud){if(operation!=null)return;operation=new();Busy(true);
  try{var text=input.Text?.Trim()??"";if(fromCloud)text=await PublicCloud(operation.Token);else if(text.StartsWith("https://",StringComparison.OrdinalIgnoreCase))text=await Fetch(text,operation.Token);
   var rows=fromCloud?Route.Manifest(text,DateTimeOffset.UtcNow.ToUnixTimeSeconds()):Route.Subscription(text);operation.Token.ThrowIfCancellationRequested();if(closed)return;SetRoutes(rows);input.Text="";Status($"Импортировано {rows.Count}. Облачный порядок не заменяет измерение на этом компьютере.");}
  catch(OperationCanceledException){Status("Импорт отменён.");}catch{Status("Не удалось импортировать. Нужны VLESS TCP/TLS/Reality или доступная HTTPS-подписка. Секреты в журнал не записываются.");}
  finally{operation.Dispose();operation=null;if(!closed)Busy(false);}
 }
 void Adopt(XraySession ready,Route route){var old=session;session=ready;old?.Dispose();routes.SelectedItem=route;proxy.Text=$"SOCKS5: 127.0.0.1:{ready.Port}\nЛогин: {ready.User}\nПароль: {ready.Password}";connect.Content="Отключить";}
 async Task Start(){if(operation!=null||routes.SelectedItem is not Route route)return;operation=new();operation.CancelAfter(TimeSpan.FromSeconds(12));Busy(true);XraySession? trial=null;
  try{var network=NetworkKey();trial=await XraySession.OpenAsync(route,operation.Token);await trial.VerifyAsync(operation.Token);operation.Token.ThrowIfCancellationRequested();if(closed||network!=NetworkKey())throw new IOException();Adopt(trial,route);trial=null;Status("HTTPS через Xray подтверждён. Укажите SOCKS5 ниже в настройках нужного приложения. Telegram и полный системный VPN этим не проверены.");}
  catch(OperationCanceledException){Status("Проверка отменена. Прежнее соединение сохранено.");}catch{Status("HTTPS не подтверждён. Проверьте сервер или попробуйте Авто.");}
  finally{trial?.Dispose();operation.Dispose();operation=null;if(!closed)Busy(false);}
 }
 async Task Auto(){if(operation!=null)return;operation=new();Busy(true);var trials=new Dictionary<Route,XraySession>();
  try{var network=NetworkKey();if(catalog.Count==0){Status("Получаю публичные конфигурации…");var text=await PublicCloud(operation.Token);if(closed)return;SetRoutes(Route.Manifest(text,DateTimeOffset.UtcNow.ToUnixTimeSeconds()));}
   var result=await selector.RunAsync(catalog,network,async(r,ct)=>{using var one=CancellationTokenSource.CreateLinkedTokenSource(ct);one.CancelAfter(1000);try{using var tcp=new TcpClient();await tcp.ConnectAsync(r.Host,r.Port,one.Token);return true;}catch(OperationCanceledException) when(!ct.IsCancellationRequested){return false;}catch(SocketException){return false;}},
    async(r,ct)=>{XraySession? trial=null;try{trial=await XraySession.OpenAsync(r,ct);var measured=await trial.MeasureAsync(ct);trials[r]=trial;trial=null;return measured;}finally{trial?.Dispose();}},operation.Token,Status);
   operation.Token.ThrowIfCancellationRequested();if(closed||NetworkKey()!=network)throw new IOException();
   if(result.Winner is not Route winner||result.Measurement is not ProbeResult sample||!trials.Remove(winner,out var best)){Status("В этой порции HTTPS не подтверждён. Можно повторить Авто или импортировать другую подписку. Прежнее соединение сохранено.");return;}
   Adopt(best,winner);Status(sample.Kbps>0?$"Лучший из {result.Compared} проверенных: {sample.Kbps/1000.0:F1} Мбит/с · HTTPS {sample.LatencyMs} мс. SOCKS5 указан ниже.":"HTTPS подтверждён, замер скорости неизвестен. Запасной маршрут, не гарантированно самый быстрый.");
  }catch(OperationCanceledException){Status("Авто отменено. Прежнее соединение сохранено.");}catch{Status("Авто не завершено: проверьте сеть и подписку. Прежнее соединение сохранено.");}
  finally{foreach(var trial in trials.Values)trial.Dispose();operation.Dispose();operation=null;if(!closed)Busy(false);}
 }
 void Stop(){session?.Dispose();session=null;proxy.Text="";connect.Content="Подключить";copy.IsEnabled=false;}
}
