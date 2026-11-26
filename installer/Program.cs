using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

internal static class Program
{
    private const string Repository = "nexaridev/falcon-language";
    private const int MinimumJava = 17;
    private static readonly HttpClient Http = CreateHttpClient();

    private sealed record Release(string Tag, string JarUrl, string? ChecksumUrl);

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("FalconSetup/1.0");
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return client;
    }

    private static async Task<int> Main(string[] args)
    {
        if (args.Contains("--help", StringComparer.OrdinalIgnoreCase) || args.Contains("-h", StringComparer.OrdinalIgnoreCase))
        {
            Console.WriteLine("FalconSetup installs a user-local JDK and Falcon CLI. It needs no admin rights.");
            Console.WriteLine("It adds 'falcon' and 'fc' to your user environment and checks for existing installs.");
            return 0;
        }
        bool update = false;
        int waitPid = 0;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--update") update = true;
            else if (args[i] == "--wait-pid" && i + 1 < args.Length && int.TryParse(args[++i], out waitPid) && waitPid > 0) { }
            else
            {
                Console.Error.WriteLine("Unknown installer option. Use --help.");
                return 2;
            }
        }
        if (waitPid != 0 && !update)
        {
            Console.Error.WriteLine("--wait-pid can only be used with --update.");
            return 2;
        }

        string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string falconHome = Path.Combine(localAppData, "Falcon");
        string bin = Path.Combine(falconHome, "bin");
        Directory.CreateDirectory(bin);

        try
        {
            if (update)
            {
                await UpdateExistingAsync(falconHome, waitPid);
                return 0;
            }
            Console.WriteLine("Falcon setup — checking Java and Falcon...");
            string? javaHome = FindUsableJdk();
            if (javaHome is null)
            {
                javaHome = await InstallJdkAsync(falconHome);
                Console.WriteLine("Installed a user-local Temurin JDK 17.");
            }
            else Console.WriteLine($"Found a usable JDK at {javaHome}.");

            string jarPath = Path.Combine(falconHome, "falcon.jar");
            Release? release = await FindLatestReleaseAsync();
            if (release is not null && await IsInstalledVersionAsync(javaHome, jarPath, release.Tag))
                Console.WriteLine($"Falcon {release.Tag.TrimStart('v', 'V')} is already installed; keeping it.");
            else if (release is not null)
            {
                Console.WriteLine($"Downloading Falcon {release.Tag.TrimStart('v', 'V')}...");
                string stagedJar = Path.Combine(falconHome, "falcon.jar.download");
                try
                {
                    await DownloadAsync(release.JarUrl, stagedJar, "Falcon");
                    if (release.ChecksumUrl is not null)
                    {
                        string checksum = Path.Combine(falconHome, "falcon.jar.sha256");
                        await DownloadAsync(release.ChecksumUrl, checksum, "Falcon checksum");
                        await VerifyChecksumAsync(stagedJar, checksum);
                        File.Delete(checksum);
                    }
                    File.Move(stagedJar, jarPath, true);
                }
                finally
                {
                    if (File.Exists(stagedJar)) File.Delete(stagedJar);
                }
            }
            else
            {
                Console.WriteLine("No Falcon release is published yet; building the main branch locally...");
                await BuildFromSourceAsync(falconHome, javaHome, jarPath);
            }

            WriteLaunchers(bin);
            InstallUpdaterCopy(falconHome);
            SetUserEnvironment(falconHome, bin, javaHome);
            AddPowerShellAliasProfiles();
            if (OperatingSystem.IsWindows()) AddCommandPromptAlias(bin);
            BroadcastEnvironmentChange();

            Console.WriteLine();
            Console.WriteLine("Falcon is ready. Open a new terminal and run 'falcon --version'.");
            Console.WriteLine("In PowerShell and interactive Command Prompt, 'fc' also starts Falcon.");
            Console.WriteLine($"Install folder: {falconHome}");
            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine($"Falcon setup couldn't finish: {exception.Message}");
            return 1;
        }
    }

    private static async Task UpdateExistingAsync(string falconHome, int waitPid)
    {
        Console.WriteLine("Falcon updater — checking the latest release...");
        string? javaHome = FindUsableJdk();
        if (javaHome is null) throw new InvalidOperationException("No usable JDK was found. Run FalconSetup.exe to install Falcon and Java first.");
        Release? release = await FindLatestReleaseAsync();
        if (release is null) throw new IOException("Couldn't find a published Falcon release with a falcon.jar asset.");
        string jarPath = Path.Combine(falconHome, "falcon.jar");
        if (await IsInstalledVersionAsync(javaHome, jarPath, release.Tag))
        {
            Console.WriteLine($"Falcon {release.Tag.TrimStart('v', 'V')} is already current.");
            return;
        }

        string stagedJar = Path.Combine(falconHome, "falcon.jar.update");
        string? checksum = null;
        try
        {
            await DownloadAsync(release.JarUrl, stagedJar, "Falcon");
            if (release.ChecksumUrl is not null)
            {
                checksum = Path.Combine(falconHome, "falcon.jar.update.sha256");
                await DownloadAsync(release.ChecksumUrl, checksum, "Falcon checksum");
                await VerifyChecksumAsync(stagedJar, checksum);
            }
            if (waitPid > 0)
            {
                Console.WriteLine("Waiting for the running Falcon command to exit...");
                try
                {
                    using Process process = Process.GetProcessById(waitPid);
                    if (!process.WaitForExit(120_000)) throw new TimeoutException("The Falcon command is still running after two minutes; the installed version was left unchanged.");
                }
                catch (ArgumentException) { /* The command exited before the updater started waiting. */ }
            }
            File.Move(stagedJar, jarPath, true);
            Console.WriteLine($"Updated Falcon to {release.Tag.TrimStart('v', 'V')}.");
        }
        finally
        {
            if (File.Exists(stagedJar)) File.Delete(stagedJar);
            if (checksum is not null && File.Exists(checksum)) File.Delete(checksum);
        }
    }

    private static void InstallUpdaterCopy(string falconHome)
    {
        string? currentExecutable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(currentExecutable) || !File.Exists(currentExecutable)) return;
        string destination = Path.Combine(falconHome, "FalconSetup.exe");
        if (!Path.GetFullPath(currentExecutable).Equals(Path.GetFullPath(destination), StringComparison.OrdinalIgnoreCase))
            File.Copy(currentExecutable, destination, true);
    }

    private static string? FindUsableJdk()
    {
        var candidates = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        AddHome(Environment.GetEnvironmentVariable("JAVA_HOME"));
        AddHome(Environment.GetEnvironmentVariable("JAVA_HOME", EnvironmentVariableTarget.User));
        foreach (string path in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try { AddHome(Directory.GetParent(Path.GetFullPath(path.Trim().Trim('"')))?.FullName); }
            catch { }
        }
        foreach (string home in candidates)
        {
            string java = Path.Combine(home, "bin", "java.exe");
            string javac = Path.Combine(home, "bin", "javac.exe");
            if (File.Exists(java) && File.Exists(javac) && GetJavaMajor(java) >= MinimumJava) return home;
        }
        return null;

        void AddHome(string? value)
        {
            if (string.IsNullOrWhiteSpace(value)) return;
            try { candidates.Add(Path.GetFullPath(value.Trim().Trim('"'))); }
            catch { }
        }
    }

    private static int GetJavaMajor(string java)
    {
        try
        {
            var info = new ProcessStartInfo(java, "--version") { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
            using var process = Process.Start(info);
            if (process is null) return 0;
            string text = process.StandardOutput.ReadToEnd() + process.StandardError.ReadToEnd();
            if (!process.WaitForExit(10_000) || process.ExitCode != 0) return 0;
            Match match = Regex.Match(text, "(?:openjdk|java)\\s+(?:version\\s+)?[\\\"]?(?<major>\\d+)", RegexOptions.IgnoreCase);
            return match.Success && int.TryParse(match.Groups["major"].Value, out int major) ? major : 0;
        }
        catch { return 0; }
    }

    private static async Task<string> InstallJdkAsync(string falconHome)
    {
        string url = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk";
        string stage = Path.Combine(falconHome, "jdk-install-" + Guid.NewGuid().ToString("N"));
        string archive = stage + ".zip";
        Directory.CreateDirectory(stage);
        try
        {
            await DownloadAsync(url, archive, "Temurin JDK 17");
            ZipFile.ExtractToDirectory(archive, stage);
            string? javaFile = Directory.EnumerateFiles(stage, "java.exe", SearchOption.AllDirectories)
                .FirstOrDefault(path => Path.GetFileName(Path.GetDirectoryName(path))?.Equals("bin", StringComparison.OrdinalIgnoreCase) == true);
            if (javaFile is null) throw new InvalidDataException("The Temurin archive did not contain java.exe.");
            string home = Directory.GetParent(Path.GetDirectoryName(javaFile)!)!.FullName;
            if (!File.Exists(Path.Combine(home, "bin", "javac.exe"))) throw new InvalidDataException("The Temurin archive did not contain javac.exe.");
            string target = Path.Combine(falconHome, "jdk");
            if (Directory.Exists(target)) Directory.Delete(target, true);
            Directory.Move(home, target);
            return target;
        }
        finally
        {
            if (File.Exists(archive)) File.Delete(archive);
            if (Directory.Exists(stage)) Directory.Delete(stage, true);
        }
    }

    private static async Task<Release?> FindLatestReleaseAsync()
    {
        try
        {
            using HttpResponseMessage response = await Http.GetAsync($"https://api.github.com/repos/{Repository}/releases/latest");
            if (!response.IsSuccessStatusCode) return null;
            using JsonDocument document = JsonDocument.Parse(await response.Content.ReadAsStreamAsync());
            JsonElement root = document.RootElement;
            string tag = root.GetProperty("tag_name").GetString() ?? "";
            string? jarUrl = null, checksumUrl = null;
            foreach (JsonElement asset in root.GetProperty("assets").EnumerateArray())
            {
                string name = asset.GetProperty("name").GetString() ?? "";
                string url = asset.GetProperty("browser_download_url").GetString() ?? "";
                if (name.Equals("falcon.jar", StringComparison.OrdinalIgnoreCase)) jarUrl = url;
                else if (name.Equals("falcon.jar.sha256", StringComparison.OrdinalIgnoreCase)) checksumUrl = url;
            }
            return string.IsNullOrWhiteSpace(tag) || jarUrl is null ? null : new Release(tag, jarUrl, checksumUrl);
        }
        catch (HttpRequestException) { return null; }
        catch (JsonException) { return null; }
    }

    private static async Task<bool> IsInstalledVersionAsync(string javaHome, string jarPath, string releaseTag)
    {
        if (!File.Exists(jarPath)) return false;
        try
        {
            var info = new ProcessStartInfo(Path.Combine(javaHome, "bin", "java.exe"))
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            info.ArgumentList.Add("-jar");
            info.ArgumentList.Add(jarPath);
            info.ArgumentList.Add("--version");
            using Process? process = Process.Start(info);
            if (process is null) return false;
            Task<string> stdout = process.StandardOutput.ReadToEndAsync();
            Task<string> stderr = process.StandardError.ReadToEndAsync();
            if (!process.WaitForExit(10_000) || process.ExitCode != 0) return false;
            string text = await stdout + await stderr;
            Match match = Regex.Match(text, "Falcon\\s+(?<version>\\d+(?:\\.\\d+)+)", RegexOptions.IgnoreCase);
            return match.Success && match.Groups["version"].Value.Equals(releaseTag.TrimStart('v', 'V'), StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static async Task VerifyChecksumAsync(string file, string checksumFile)
    {
        string expected = (await File.ReadAllTextAsync(checksumFile)).Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)[0];
        await using var stream = File.OpenRead(file);
        string actual = Convert.ToHexString(await SHA256.HashDataAsync(stream));
        if (!actual.Equals(expected, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Falcon's downloaded JAR did not match its SHA-256 checksum.");
    }

    private static async Task BuildFromSourceAsync(string falconHome, string javaHome, string jarPath)
    {
        string stage = Path.Combine(Path.GetTempPath(), "falcon-setup-" + Guid.NewGuid().ToString("N"));
        string archive = stage + ".zip";
        Directory.CreateDirectory(stage);
        try
        {
            await DownloadAsync($"https://github.com/{Repository}/archive/refs/heads/main.zip", archive, "Falcon source");
            ZipFile.ExtractToDirectory(archive, stage);
            string? buildScript = Directory.EnumerateFiles(stage, "build.ps1", SearchOption.AllDirectories).FirstOrDefault();
            if (buildScript is null) throw new InvalidDataException("The Falcon source archive did not contain build.ps1.");
            string sourceRoot = Path.GetDirectoryName(buildScript)!;
            var info = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, WorkingDirectory = sourceRoot };
            info.ArgumentList.Add("-NoProfile");
            info.ArgumentList.Add("-ExecutionPolicy");
            info.ArgumentList.Add("Bypass");
            info.ArgumentList.Add("-File");
            info.ArgumentList.Add(buildScript);
            info.Environment["JAVA_HOME"] = javaHome;
            info.Environment["PATH"] = Path.Combine(javaHome, "bin") + Path.PathSeparator + (Environment.GetEnvironmentVariable("PATH") ?? "");
            using Process? process = Process.Start(info);
            if (process is null) throw new IOException("Could not start the Falcon source build.");
            await process.WaitForExitAsync();
            if (process.ExitCode != 0) throw new IOException($"Falcon source build exited with code {process.ExitCode}.");
            string builtJar = Path.Combine(sourceRoot, "build", "falcon.jar");
            if (!File.Exists(builtJar)) throw new FileNotFoundException("The Falcon build completed without producing build/falcon.jar.");
            File.Copy(builtJar, jarPath, true);
        }
        finally
        {
            if (File.Exists(archive)) File.Delete(archive);
            if (Directory.Exists(stage)) Directory.Delete(stage, true);
        }
    }

    private static async Task DownloadAsync(string url, string destination, string label)
    {
        using HttpResponseMessage response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
        response.EnsureSuccessStatusCode();
        long? total = response.Content.Headers.ContentLength;
        await using Stream input = await response.Content.ReadAsStreamAsync();
        await using var output = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None, 1024 * 128, true);
        byte[] buffer = new byte[1024 * 128];
        long downloaded = 0;
        int read;
        int lastPercent = -1;
        Console.Write($"{label}: downloading...");
        while ((read = await input.ReadAsync(buffer)) != 0)
        {
            await output.WriteAsync(buffer.AsMemory(0, read));
            downloaded += read;
            if (total is > 0)
            {
                int percent = (int)(downloaded * 100 / total.Value);
                if (percent / 10 > lastPercent / 10)
                {
                    lastPercent = percent;
                    Console.Write($" {percent}%");
                }
            }
        }
        Console.WriteLine($" done ({downloaded / (1024 * 1024)} MiB).");
    }

    private static void WriteLaunchers(string bin)
    {
        string falcon = Path.Combine(bin, "falcon.cmd");
        File.WriteAllText(falcon, "@echo off\r\njava -jar \"%LOCALAPPDATA%\\Falcon\\falcon.jar\" %*\r\nexit /b %errorlevel%\r\n");
        File.WriteAllText(Path.Combine(bin, "fc.cmd"), "@call \"%~dp0falcon.cmd\" %*\r\nexit /b %errorlevel%\r\n");
    }

    private static void SetUserEnvironment(string falconHome, string bin, string javaHome)
    {
        Environment.SetEnvironmentVariable("JAVA_HOME", javaHome, EnvironmentVariableTarget.User);
        Environment.SetEnvironmentVariable("FALCON_HOME", falconHome, EnvironmentVariableTarget.User);
        string current = Environment.GetEnvironmentVariable("Path", EnvironmentVariableTarget.User) ?? "";
        var entries = current.Split(';', StringSplitOptions.RemoveEmptyEntries)
            .Select(value => value.Trim().Trim('"')).Where(value => !string.IsNullOrWhiteSpace(value)).ToList();
        string[] additions = [bin, Path.Combine(javaHome, "bin")];
        foreach (string addition in additions.Reverse())
        {
            entries.RemoveAll(value => SamePath(value, addition));
            entries.Insert(0, addition);
        }
        Environment.SetEnvironmentVariable("Path", string.Join(';', entries), EnvironmentVariableTarget.User);
    }

    private static bool SamePath(string left, string right)
    {
        try { return Path.GetFullPath(left).TrimEnd('\\').Equals(Path.GetFullPath(right).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase); }
        catch { return left.Equals(right, StringComparison.OrdinalIgnoreCase); }
    }

    private static void AddPowerShellAliasProfiles()
    {
        string documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        string begin = "# >>> Falcon CLI alias >>>";
        string end = "# <<< Falcon CLI alias <<<";
        string block = begin + Environment.NewLine + "Set-Alias -Name fc -Value falcon -Scope Global -Force" + Environment.NewLine + end;
        foreach (string shell in new[] { "PowerShell", "WindowsPowerShell" })
        {
            string profile = Path.Combine(documents, shell, "Microsoft.PowerShell_profile.ps1");
            Directory.CreateDirectory(Path.GetDirectoryName(profile)!);
            string text = File.Exists(profile) ? File.ReadAllText(profile) : "";
            int start = text.IndexOf(begin, StringComparison.Ordinal);
            if (start >= 0)
            {
                int finish = text.IndexOf(end, start, StringComparison.Ordinal);
                if (finish >= 0) text = text.Remove(start, finish + end.Length - start).TrimEnd();
            }
            File.WriteAllText(profile, text.Length == 0 ? block + Environment.NewLine : text + Environment.NewLine + Environment.NewLine + block + Environment.NewLine);
        }
    }

    [SupportedOSPlatform("windows")]
    private static void AddCommandPromptAlias(string bin)
    {
        string fcCommand = Path.Combine(bin, "fc.cmd");
        using RegistryKey key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Command Processor")
            ?? throw new IOException("Could not open your Command Prompt settings.");
        string existing = key.GetValue("AutoRun", "", RegistryValueOptions.DoNotExpandEnvironmentNames)?.ToString() ?? "";
        string marker = "doskey fc=call \"" + fcCommand + "\" $*";
        if (!existing.Contains("doskey fc=", StringComparison.OrdinalIgnoreCase))
        {
            string updated = string.IsNullOrWhiteSpace(existing) ? marker : existing.TrimEnd() + " & " + marker;
            RegistryValueKind kind;
            try { kind = key.GetValueKind("AutoRun"); }
            catch (IOException) { kind = RegistryValueKind.String; }
            key.SetValue("AutoRun", updated, kind);
        }
    }

    private static void BroadcastEnvironmentChange()
    {
        SendMessageTimeout(new IntPtr(0xffff), 0x001A, IntPtr.Zero, "Environment", 0x0002, 5000, out _);
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint message, IntPtr wParam, string lParam,
        uint flags, uint timeout, out IntPtr result);
}
