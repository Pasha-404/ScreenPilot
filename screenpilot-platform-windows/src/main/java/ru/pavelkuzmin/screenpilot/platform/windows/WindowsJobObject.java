package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.util.Objects;

/**
 * Owns a Windows Job Object configured to terminate every assigned process when the job closes.
 * This prevents an orphaned mpv.exe if ScreenPilot exits unexpectedly.
 */
public final class WindowsJobObject implements AutoCloseable {

    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x0000_2000;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private final HANDLE jobHandle;
    private boolean closed;

    private WindowsJobObject(HANDLE jobHandle) {
        this.jobHandle = jobHandle;
    }

    public static WindowsJobObject create() throws IOException {
        requireWindows();
        HANDLE handle = JobObjectKernel32.INSTANCE.CreateJobObject(null, null);
        if (isInvalid(handle)) {
            throw nativeFailure("CreateJobObject");
        }

        JobObjectExtendedLimitInformation limits = new JobObjectExtendedLimitInformation();
        limits.basicLimitInformation.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        limits.write();
        if (!JobObjectKernel32.INSTANCE.SetInformationJobObject(
                handle,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                limits.getPointer(),
                limits.size())) {
            Kernel32.INSTANCE.CloseHandle(handle);
            throw nativeFailure("SetInformationJobObject");
        }
        return new WindowsJobObject(handle);
    }

    public void assign(Process process) throws IOException {
        Objects.requireNonNull(process, "process");
        if (closed) {
            throw new IllegalStateException("Windows Job Object is already closed");
        }
        HANDLE processHandle = Kernel32.INSTANCE.OpenProcess(PROCESS_TERMINATE | PROCESS_SET_QUOTA, false,
                Math.toIntExact(process.pid()));
        if (isInvalid(processHandle)) {
            throw nativeFailure("OpenProcess for pid " + process.pid());
        }
        try {
            if (!JobObjectKernel32.INSTANCE.AssignProcessToJobObject(jobHandle, processHandle)) {
                throw nativeFailure("AssignProcessToJobObject for pid " + process.pid());
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(processHandle);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            Kernel32.INSTANCE.CloseHandle(jobHandle);
            closed = true;
        }
    }

    private static void requireWindows() {
        if (!Platform.isWindows()) {
            throw new UnsupportedOperationException("Windows Job Objects are available only on Windows");
        }
    }

    private static boolean isInvalid(HANDLE handle) {
        return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == -1L;
    }

    private static IOException nativeFailure(String operation) {
        return new IOException(operation + " failed with Win32 error " + Kernel32.INSTANCE.GetLastError());
    }

    private interface JobObjectKernel32 extends StdCallLibrary {
        JobObjectKernel32 INSTANCE = Native.load("kernel32", JobObjectKernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        HANDLE CreateJobObject(Pointer jobAttributes, String name);

        boolean SetInformationJobObject(HANDLE job, int informationClass, Pointer information, int informationLength);

        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);
    }

    @Structure.FieldOrder({
            "perProcessUserTimeLimit",
            "perJobUserTimeLimit",
            "limitFlags",
            "minimumWorkingSetSize",
            "maximumWorkingSetSize",
            "activeProcessLimit",
            "affinity",
            "priorityClass",
            "schedulingClass"
    })
    public static final class JobObjectBasicLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public long minimumWorkingSetSize;
        public long maximumWorkingSetSize;
        public int activeProcessLimit;
        public long affinity;
        public int priorityClass;
        public int schedulingClass;
    }

    @Structure.FieldOrder({
            "readOperationCount",
            "writeOperationCount",
            "otherOperationCount",
            "readTransferCount",
            "writeTransferCount",
            "otherTransferCount"
    })
    public static final class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;
    }

    @Structure.FieldOrder({
            "basicLimitInformation",
            "ioInfo",
            "processMemoryLimit",
            "jobMemoryLimit",
            "peakProcessMemoryUsed",
            "peakJobMemoryUsed"
    })
    public static final class JobObjectExtendedLimitInformation extends Structure {
        public JobObjectBasicLimitInformation basicLimitInformation = new JobObjectBasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public long processMemoryLimit;
        public long jobMemoryLimit;
        public long peakProcessMemoryUsed;
        public long peakJobMemoryUsed;
    }
}
