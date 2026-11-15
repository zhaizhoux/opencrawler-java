// OpenCrawlerJava 一键启动器：Win32 GUI 程序，无控制台、零第三方依赖。
// 编译：g++ -O2 -s -mwindows WinLauncher.cpp -o OpenCrawlerJava.exe
// 行为：若 jar 不存在先调用 build.bat，然后 javaw -jar ... desktop 打开桌面窗口。
#include <windows.h>

#include <string>
#include <vector>

static std::wstring DirOfModule() {
    std::vector<wchar_t> buf(MAX_PATH * 4, L'\0');
    GetModuleFileNameW(nullptr, buf.data(), static_cast<DWORD>(buf.size()));
    std::wstring p(buf.data());
    size_t pos = p.find_last_of(L"\\/");
    return pos == std::wstring::npos ? std::wstring(L".") : p.substr(0, pos);
}

static bool Exists(const std::wstring& p) {
    return GetFileAttributesW(p.c_str()) != INVALID_FILE_ATTRIBUTES;
}

static bool Run(const std::wstring& exe, const std::wstring& args, const std::wstring& dir,
                bool wait = false) {
    std::vector<wchar_t> cmd(args.begin(), args.end());
    cmd.push_back(L'\0');
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    BOOL ok = CreateProcessW(exe.empty() ? nullptr : exe.c_str(), cmd.data(), nullptr, nullptr,
                             FALSE, 0, nullptr, dir.c_str(), &si, &pi);
    if (!ok) return false;
    if (wait) WaitForSingleObject(pi.hProcess, 120000);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return true;
}

static void Fail(const wchar_t* msg) {
    MessageBoxW(nullptr, msg, L"OpenCrawlerJava 启动失败", MB_ICONERROR | MB_OK);
}

int WINAPI WinMain(HINSTANCE, HINSTANCE, LPSTR, int) {
    std::wstring here = DirOfModule();
    std::wstring root = here + L"\\..";
    std::wstring jar = root + L"\\build\\opencrawler-java.jar";
    std::wstring build = root + L"\\build.bat";

    if (!Exists(jar)) {
        if (!Exists(build) || !Run(L"", L"cmd.exe /c \"" + build + L"\"", root, true)) {
            Fail(L"找不到 build.bat，请先运行 build.bat 构建 jar。");
            return 1;
        }
    }
    if (!Exists(jar)) {
        Fail(L"构建未生成 build\\opencrawler-java.jar，请确认已安装 JDK 21+。");
        return 1;
    }
    std::wstring args = L"javaw.exe -jar \"" + jar + L"\" desktop";
    if (Run(L"", args, root)) return 0;
    if (Run(L"C:\\Windows\\System32\\javaw.exe", args, root)) return 0;
    Fail(L"找不到 javaw.exe。请安装 JDK 21+ 并加入 PATH，或直接用 java -jar 运行 desktop 命令。");
    return 1;
}
