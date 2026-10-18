using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

// Windows closes this job handle even on a GUI crash: no orphaned probe cores remain running.
static class ChildProcessJob
{
    [StructLayout(LayoutKind.Sequential)]struct BasicLimit{public long ProcessTime,JobTime;public uint Flags;public UIntPtr MinWorking,MaxWorking;public uint ActiveProcesses;public UIntPtr Affinity;public uint Priority,Scheduling;}
    [StructLayout(LayoutKind.Sequential)]struct IoCounters{public ulong ReadOps,WriteOps,OtherOps,ReadBytes,WriteBytes,OtherBytes;}
    [StructLayout(LayoutKind.Sequential)]struct ExtendedLimit{public BasicLimit Basic;public IoCounters Io;public UIntPtr ProcessMemory,JobMemory,PeakProcessMemory,PeakJobMemory;}
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern SafeFileHandle CreateJobObject(IntPtr attributes,string? name);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool SetInformationJobObject(SafeFileHandle job,int kind,IntPtr data,uint length);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool AssignProcessToJobObject(SafeFileHandle job,IntPtr process);
    static readonly Lazy<SafeFileHandle> Job=new(()=>{
        var handle=CreateJobObject(IntPtr.Zero,null);if(handle.IsInvalid)throw new Win32Exception(Marshal.GetLastWin32Error());
        var limits=new ExtendedLimit{Basic=new BasicLimit{Flags=0x2000}};var size=Marshal.SizeOf<ExtendedLimit>();var p=Marshal.AllocHGlobal(size);
        try{Marshal.StructureToPtr(limits,p,false);if(!SetInformationJobObject(handle,9,p,(uint)size))throw new Win32Exception(Marshal.GetLastWin32Error());return handle;}
        catch{handle.Dispose();throw;}finally{Marshal.FreeHGlobal(p);}
    });
    public static void Attach(System.Diagnostics.Process process){if(!AssignProcessToJobObject(Job.Value,process.Handle))throw new Win32Exception(Marshal.GetLastWin32Error());}
}
