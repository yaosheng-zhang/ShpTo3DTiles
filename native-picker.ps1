param(
    [ValidateSet('shp','prj','texture','directory','result')][string]$Kind,
    [string]$InitialPath = ''
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class NativePicker {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct Filter { [MarshalAs(UnmanagedType.LPWStr)] public string Name; [MarshalAs(UnmanagedType.LPWStr)] public string Pattern; }
    [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellItem {
        void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
        void GetParent(out IShellItem parent);
        void GetDisplayName(uint kind, out IntPtr name);
        void GetAttributes(uint mask, out uint attributes);
        void Compare(IShellItem other, uint hint, out int order);
    }
    [ComImport, Guid("D57C7288-D4AD-4768-BE02-9D969532D960"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IFileOpenDialog {
        [PreserveSig] int Show(IntPtr owner);
        void SetFileTypes(uint count, [MarshalAs(UnmanagedType.LPArray, SizeParamIndex=0)] Filter[] filters);
        void SetFileTypeIndex(uint index);
        void GetFileTypeIndex(out uint index);
        void Advise(IntPtr events, out uint cookie);
        void Unadvise(uint cookie);
        void SetOptions(uint options);
        void GetOptions(out uint options);
        void SetDefaultFolder(IShellItem folder);
        void SetFolder(IShellItem folder);
        void GetFolder(out IShellItem folder);
        void GetCurrentSelection(out IShellItem item);
        void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string name);
        void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string title);
        void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string text);
        void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string text);
        void GetResult(out IShellItem result);
    }
    [DllImport("shell32.dll", CharSet=CharSet.Unicode, PreserveSig=false)]
    static extern void SHCreateItemFromParsingName(string path, IntPtr context, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out IShellItem item);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();

    public static string Select(string kind, string initial) {
        object instance = Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")));
        var dialog = (IFileOpenDialog)instance;
        try {
            uint options; dialog.GetOptions(out options);
            dialog.SetOptions(options | 0x40u | 0x800u | (kind == "directory" ? 0x20u : 0x1000u));
            if (kind != "directory") {
                string pattern = kind == "shp" ? "*.shp" : kind == "prj" ? "*.prj" : kind == "result" ? "tileset.json" : "*.jpg;*.jpeg;*.png";
                dialog.SetFileTypes(1, new Filter[] { new Filter { Name = pattern, Pattern = pattern } });
            }
            dialog.SetTitle(kind == "directory" ? "选择输出文件夹" : kind == "result" ? "选择 tileset.json" : kind == "texture" ? "选择纹理图片" : "选择 " + kind.ToUpper() + " 文件");
            string folder = String.IsNullOrWhiteSpace(initial) ? null : (System.IO.Directory.Exists(initial) ? initial : System.IO.Path.GetDirectoryName(initial));
            if (!String.IsNullOrEmpty(folder) && System.IO.Directory.Exists(folder)) {
                IShellItem item; Guid iid = new Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE");
                SHCreateItemFromParsingName(folder, IntPtr.Zero, ref iid, out item);
                try { dialog.SetFolder(item); } finally { Marshal.ReleaseComObject(item); }
            }
            int status = dialog.Show(GetForegroundWindow());
            if (status == unchecked((int)0x800704C7)) return null;
            Marshal.ThrowExceptionForHR(status);
            IShellItem result; dialog.GetResult(out result);
            try {
                IntPtr name; result.GetDisplayName(0x80058000, out name);
                try { return Marshal.PtrToStringUni(name); } finally { Marshal.FreeCoTaskMem(name); }
            } finally { Marshal.ReleaseComObject(result); }
        } finally { Marshal.ReleaseComObject(instance); }
    }
}
'@
try {
    $selectedPath = [NativePicker]::Select($Kind, $InitialPath)
    @{ path = $selectedPath; cancelled = ($null -eq $selectedPath) } | ConvertTo-Json -Compress
} catch {
    @{ error = $_.Exception.Message } | ConvertTo-Json -Compress
    exit 1
}


