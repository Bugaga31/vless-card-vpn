using System.Reflection;
using System.Security.Cryptography;
namespace VlessCard;

/// A verified official core, embedded at build time; never downloads executables at runtime.
public static class BundledCore
{
    static async Task<bool> Matches(string file,string expected,CancellationToken ct) {
        if(!File.Exists(file))return false;
        await using var stream=File.OpenRead(file);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream,ct)).Equals(expected,StringComparison.OrdinalIgnoreCase);
    }
    public static async Task<string> ResolveAsync(CancellationToken ct) {
        var name=OperatingSystem.IsWindows()?"xray.exe":"xray";
        var assembly=Assembly.GetEntryAssembly()!;
        using var digest=assembly.GetManifestResourceStream("VlessCard.CoreDigest");
        if(digest==null) {
            // Legacy Windows preview keeps a separately pinned adjacent core.
            var external=Path.Combine(AppContext.BaseDirectory,name);
            var checksum=File.ReadAllText(external+".sha256").Trim();
            if(!await Matches(external,checksum,ct))throw new IOException("Core verification failed");
            return external;
        }
        using var reader=new StreamReader(digest);var expected=(await reader.ReadToEndAsync(ct)).Trim().ToLowerInvariant();
        if(expected.Length!=64||expected.Any(c=>!Uri.IsHexDigit(c)))throw new IOException("Invalid embedded core digest");
        var folder=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"VlessCard","cores",expected);
        Directory.CreateDirectory(folder);
        if(!OperatingSystem.IsWindows())File.SetUnixFileMode(folder,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
        var output=Path.Combine(folder,name);
        if(await Matches(output,expected,ct))return output;
        var temporary=Path.Combine(folder,Guid.NewGuid().ToString("N")+".tmp");
        try {
            using var embedded=assembly.GetManifestResourceStream("VlessCard.BundledXray")??throw new IOException("Bundled core missing");
            var options=new FileStreamOptions{Mode=FileMode.CreateNew,Access=FileAccess.Write,Share=FileShare.None};
            if(!OperatingSystem.IsWindows())options.UnixCreateMode=UnixFileMode.UserRead|UnixFileMode.UserWrite;
            await using(var stream=new FileStream(temporary,options))await embedded.CopyToAsync(stream,ct);
            if(!await Matches(temporary,expected,ct))throw new IOException("Bundled core checksum mismatch");
            if(!OperatingSystem.IsWindows())File.SetUnixFileMode(temporary,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
            ct.ThrowIfCancellationRequested();File.Move(temporary,output,true);return output;
        } finally {try {File.Delete(temporary);}catch {}}
    }
}
