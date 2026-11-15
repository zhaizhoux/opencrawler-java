// OpenCrawlerJava 一键启动器：Win32 GUI 程序，无控制台、零第三方依赖。
// 编译：g++ -O2 -s -mwindows WinLauncher.cpp -o OpenCrawlerJava.exe
//
// 行为：优先定位 build\opencrawler-java.jar（启动器放在项目根目录 / desktop\ /
// build\ 下都能找到），缺失时自动调用 build.bat，最后用 javaw 启动 desktop 服务。
// 额外参数原样转发，例如：
//   OpenCrawlerJava.exe --no-browser --port 9000
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

static std::wstring Join(const std::wstring& dir, const wchar_t* tail) {
    return dir + L"\\" + tail;
}

static std::wstring Parent(const std::wstring& path) {
    size_t pos = path.find_last_of(L"\\/");
    return pos == std::wstring::npos ? std::wstring(L".") : path.substr(0, pos);
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
                             FALSE, CREATE_NO_WINDOW, nullptr, dir.c_str(), &si, &pi);
    if (!ok) return false;
    if (wait) WaitForSingleObject(pi.hProcess, 120000);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return true;
}

static void Fail(const wchar_t* msg) {
    MessageBoxW(nullptr, msg, L"OpenCrawlerJava 启动失败", MB_ICONERROR | MB_OK);
}

// 跳过 GetCommandLineW() 里的 argv[0]，把其余参数原样转给主程序。
static std::wstring ExtraArgs() {
    std::wstring cl = GetCommandLineW();
    std::size_t i = 0;
    if (!cl.empty() && cl[0] == L'"') {
        std::size_t end = cl.find(L'"', 1);
        i = end == std::wstring::npos ? cl.size() : end + 1;
    } else {
        i = cl.find_first_of(L" \t");
        if (i == std::wstring::npos) i = cl.size();
    }
    while (i < cl.size() && (cl[i] == L' ' || cl[i] == L'\t')) ++i;
    return cl.substr(i);
}

static std::wstring FindFile(const std::wstring& here, const wchar_t* relative) {
    const std::wstring candidates[] = {
        Join(here, relative),
        Join(here + L"\\..", relative),
        Join(here + L"\\..\\..", relative),
    };
    for (const auto& p : candidates) if (Exists(p)) return p;
    return L"";
}

int WINAPI WinMain(HINSTANCE, HINSTANCE, LPSTR, int) {
    const std::wstring here = DirOfModule();
    std::wstring jar = FindFile(here, L"build\\opencrawler-java.jar");
    std::wstring root = jar.empty() ? here : Parent(Parent(jar));

    if (jar.empty()) {
        std::wstring build = FindFile(here, L"build.bat");
        if (build.empty() || !Run(L"", L"cmd.exe /c \"" + build + L"\"", Parent(build), true)) {
            Fail(L"找不到 build.bat，请先运行 build.bat 构建 jar。");
            return 1;
        }
        jar = FindFile(here, L"build\\opencrawler-java.jar");
        root = jar.empty() ? here : Parent(Parent(jar));
    }
    if (jar.empty()) {
        Fail(L"构建未生成 build\\opencrawler-java.jar，请确认已安装 JDK 21+。");
        return 1;
    }
    const std::wstring extra = ExtraArgs();
    const std::wstring suffix = extra.empty() ? L"" : L" " + extra;
    std::wstring args = L"javaw.exe -jar \"" + jar + L"\" desktop" + suffix;
    if (Run(L"", args, root)) return 0;
    args = L"\"C:\\Windows\\System32\\javaw.exe\" -jar \"" + jar + L"\" desktop" + suffix;
    if (Run(L"C:\\Windows\\System32\\javaw.exe", args, root)) return 0;
    Fail(L"找不到 javaw.exe。请安装 JDK 21+ 并加入 PATH，或直接用 java -jar 运行 desktop 命令。");
    return 1;
}
