[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Executable
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path

if (-not ('ScreenPilot.NativeIconInspector' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;

namespace ScreenPilot {
    public static class NativeIconInspector {
        private const uint LOAD_LIBRARY_AS_DATAFILE = 0x00000002;
        private const ushort RT_ICON = 3;
        private const ushort RT_GROUP_ICON = 14;

        [UnmanagedFunctionPointer(CallingConvention.Winapi, CharSet = CharSet.Unicode)]
        private delegate bool EnumResNameProc(IntPtr module, IntPtr type, IntPtr name, IntPtr parameter);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryEx(string fileName, IntPtr file, uint flags);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool FreeLibrary(IntPtr module);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool EnumResourceNames(IntPtr module, IntPtr type, EnumResNameProc callback, IntPtr parameter);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint SizeofResource(IntPtr module, IntPtr resource);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr LockResource(IntPtr loadedResource);

        private static IntPtr ResourceId(ushort id) {
            return new IntPtr(id);
        }

        private static byte[] ResourceBytes(IntPtr module, IntPtr name, ushort type) {
            IntPtr resource = FindResource(module, name, ResourceId(type));
            if (resource == IntPtr.Zero) {
                throw new InvalidOperationException("Required icon resource is missing.");
            }
            uint size = SizeofResource(module, resource);
            IntPtr loaded = LoadResource(module, resource);
            IntPtr data = LockResource(loaded);
            if (size == 0 || loaded == IntPtr.Zero || data == IntPtr.Zero) {
                throw new InvalidOperationException("Icon resource could not be read.");
            }
            byte[] bytes = new byte[checked((int)size)];
            Marshal.Copy(data, bytes, 0, bytes.Length);
            return bytes;
        }

        public static void Verify(string executable) {
            IntPtr module = LoadLibraryEx(executable, IntPtr.Zero, LOAD_LIBRARY_AS_DATAFILE);
            if (module == IntPtr.Zero) {
                throw new InvalidOperationException("Could not open EXE as a resource module: " + Marshal.GetLastWin32Error());
            }
            try {
                var names = new List<IntPtr>();
                EnumResNameProc collect = (ignoredModule, ignoredType, name, ignoredParameter) => {
                    names.Add(name);
                    return true;
                };
                if (!EnumResourceNames(module, ResourceId(RT_GROUP_ICON), collect, IntPtr.Zero) || names.Count == 0) {
                    throw new InvalidOperationException("EXE does not contain an RT_GROUP_ICON resource.");
                }

                byte[] group = ResourceBytes(module, names[0], RT_GROUP_ICON);
                if (group.Length < 6 || BitConverter.ToUInt16(group, 2) != 1) {
                    throw new InvalidOperationException("RT_GROUP_ICON has an invalid directory header.");
                }
                ushort count = BitConverter.ToUInt16(group, 4);
                if (count == 0 || group.Length < 6 + count * 14) {
                    throw new InvalidOperationException("RT_GROUP_ICON has an invalid entry count.");
                }

                bool has256 = false;
                for (int index = 0; index < count; index++) {
                    int offset = 6 + index * 14;
                    byte width = group[offset];
                    byte height = group[offset + 1];
                    ushort iconId = BitConverter.ToUInt16(group, offset + 12);
                    ResourceBytes(module, ResourceId(iconId), RT_ICON);
                    if (width == 0 && height == 0) {
                        has256 = true;
                    }
                }
                if (!has256) {
                    throw new InvalidOperationException("RT_GROUP_ICON does not contain a 256x256 icon layer.");
                }
            } finally {
                FreeLibrary(module);
            }
        }
    }
}
'@
}

[ScreenPilot.NativeIconInspector]::Verify($resolvedExecutable)
Write-Host "[PASS] The packaged EXE contains RT_GROUP_ICON, RT_ICON and a 256x256 layer."
