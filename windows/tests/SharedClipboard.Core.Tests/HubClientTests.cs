using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace SharedClipboard.Core.Tests;

public sealed class HubClientTests : IDisposable
{
    readonly ClipdServer _server = new(maxFileMb: 20, quotaMb: 80);
    readonly List<IDisposable> _dispose = [];
    readonly string _tmp = Directory.CreateTempSubdirectory("clip-client").FullName;

    public void Dispose()
    {
        foreach (var d in _dispose)
            d.Dispose();
        _server.Dispose();
        Directory.Delete(_tmp, true);
    }

    sealed class Recorder
    {
        public readonly BlockingCollection<ClipItem> Added = new();
        public readonly BlockingCollection<string> Removed = new();
        public readonly BlockingCollection<IReadOnlyList<ClipItem>> Synced = new();
        public readonly BlockingCollection<HubState> States = new();
    }

    (HubClient hub, Recorder rec) Connect(string device, AppSettings? settings = null)
    {
        var hub = new HubClient();
        _dispose.Add(hub);
        var rec = new Recorder();
        hub.ItemAdded += rec.Added.Add;
        hub.ItemRemoved += rec.Removed.Add;
        hub.Synced += rec.Synced.Add;
        hub.StateChanged += rec.States.Add;
        hub.Configure(settings ?? _server.Settings(device));
        return (hub, rec);
    }

    static T Take<T>(BlockingCollection<T> c)
    {
        Assert.True(c.TryTake(out var v, TimeSpan.FromSeconds(10)), $"timed out waiting for {typeof(T).Name}");
        return v!;
    }

    static void WaitState(Recorder rec, HubState want)
    {
        while (Take(rec.States) != want)
        {
        }
    }

    [Fact]
    public async Task TextGoesToOtherDevice()
    {
        var (pc, pcRec) = Connect("ПК");
        var (laptop, laptopRec) = Connect("Ноутбук");
        Take(pcRec.Synced);
        Take(laptopRec.Synced);

        var sent = await pc.SendTextAsync("привет с ПК");
        var got = Take(laptopRec.Added);
        Assert.Equal(sent.Id, got.Id);
        Assert.Equal("привет с ПК", got.Text);
        Assert.Equal("ПК", got.From);
        Assert.Equal(sent.Id, Take(pcRec.Added).Id); // echo to the sender

        var url = await laptop.SendTextAsync("https://example.com/x");
        Assert.True(Take(pcRec.Added).IsUrl);
        Assert.Equal(2, pc.Items.Count);

        await pc.DeleteAsync(url.Id);
        Assert.Equal(url.Id, Take(laptopRec.Removed));
        Assert.Single(laptop.Items);
    }

    [Fact]
    public async Task HistoryArrivesOnConnect()
    {
        var (pc, pcRec) = Connect("ПК");
        Take(pcRec.Synced);
        await pc.SendTextAsync("раз");
        await pc.SendTextAsync("два");

        var (_, laptopRec) = Connect("Ноутбук");
        var snapshot = Take(laptopRec.Synced);
        Assert.Equal(["раз", "два"], snapshot.Select(i => i.Text));
    }

    [Fact]
    public async Task FileRoundTripThroughCache()
    {
        var (pc, pcRec) = Connect("ПК");
        var (laptop, laptopRec) = Connect("Ноутбук");
        Take(pcRec.Synced);
        Take(laptopRec.Synced);

        var src = Path.Combine(_tmp, "Презентация.pptx");
        var data = RandomNumberGenerator.GetBytes(20 << 20);
        await File.WriteAllBytesAsync(src, data);

        var progress = new List<double>();
        var up = await pc.UploadFileAsync(src, progress: new SyncProgress(progress.Add));
        Assert.Equal("Презентация.pptx", up.File!.Name);
        Assert.Equal(data.Length, up.File.Size);
        Assert.Equal(1.0, progress[^1], 3);

        var item = Take(laptopRec.Added);
        var cache = new FileCache(Path.Combine(_tmp, "cache"), laptop);
        Assert.False(cache.IsReady(item));
        var path = await cache.EnsureAsync(item);
        Assert.True(cache.IsReady(item));
        Assert.Equal("Презентация.pptx", Path.GetFileName(path));
        Assert.Equal(data, await File.ReadAllBytesAsync(path));

        // Second call is served from disk.
        Assert.Equal(path, await cache.EnsureAsync(item));

        cache.Cleanup([]);
        Assert.False(File.Exists(path));
    }

    [Fact]
    public async Task SenderAdoptsItsOwnUpload()
    {
        var (pc, pcRec) = Connect("ПК");
        Take(pcRec.Synced);
        var src = Path.Combine(_tmp, "photo.jpg");
        await File.WriteAllBytesAsync(src, RandomNumberGenerator.GetBytes(1000));
        var item = await pc.UploadFileAsync(src);
        var cache = new FileCache(Path.Combine(_tmp, "cache"), pc);
        cache.Adopt(item, src);
        Assert.True(cache.IsReady(item));
    }

    [Fact]
    public async Task RejectsTooLargeFileBeforeUploading()
    {
        var (pc, pcRec) = Connect("ПК");
        Take(pcRec.Synced);
        var src = Path.Combine(_tmp, "big.bin");
        await using (var f = File.Create(src))
            f.SetLength((20 << 20) + 1);
        var e = await Assert.ThrowsAsync<ClipException>(() => pc.UploadFileAsync(src));
        Assert.Contains("больше", e.Message);
    }

    [Fact]
    public async Task CorruptedDownloadIsRejected()
    {
        var (pc, pcRec) = Connect("ПК");
        Take(pcRec.Synced);
        var src = Path.Combine(_tmp, "a.txt");
        await File.WriteAllTextAsync(src, "hello");
        var item = await pc.UploadFileAsync(src);
        item.File!.Sha256 = new string('0', 64);
        var dest = Path.Combine(_tmp, "out", "a.txt");
        await Assert.ThrowsAsync<ClipException>(() => pc.DownloadAsync(item, dest));
        Assert.False(File.Exists(dest));
        Assert.False(File.Exists(dest + ".part"));
    }

    [Fact]
    public async Task WrongTokenIsReported()
    {
        var settings = _server.Settings("ПК");
        settings.Token = "wrong";
        var (_, rec) = Connect("ПК", settings);
        WaitState(rec, HubState.AuthFailed);
        Assert.Equal("Роутер отвечает, но токен неверный", await HubClient.CheckAsync(settings));
        Assert.Null(await HubClient.CheckAsync(_server.Settings("ПК")));
    }

    [Fact]
    public async Task ReconnectsAfterRouterRestart()
    {
        var settings = new AppSettings { ServerUrl = "http://127.0.0.1:1", Token = "x", DeviceName = "ПК" };
        var (hub, rec) = Connect("ПК", settings);
        WaitState(rec, HubState.Offline);
        Assert.Equal("Нет связи с 127.0.0.1:1", await HubClient.CheckAsync(settings));

        hub.Configure(_server.Settings("ПК"));
        WaitState(rec, HubState.Online);
    }

    [Theory]
    [InlineData("clip.lan", "http://clip.lan:8765/", null)]
    [InlineData("192.168.8.1:8765", "http://192.168.8.1:8765/", null)]
    [InlineData("http://clip.lan", "http://clip.lan:8765/", null)]
    [InlineData("http://clip.lan:80", "http://clip.lan/", null)]
    [InlineData(" http://192.168.8.1:8765/#token=AbC123 ", "http://192.168.8.1:8765/", "AbC123")]
    [InlineData("https://clip.example:9000/x?y#token=t", "https://clip.example:9000/", "t")]
    public void ParsesServerAddress(string input, string want, string? token)
    {
        Assert.True(ServerAddress.TryParse(input, out var u, out var t));
        Assert.Equal(want, u.ToString());
        Assert.Equal(token, t);
    }

    [Theory]
    [InlineData("")]
    [InlineData("ftp://x")]
    [InlineData("http://")]
    public void RejectsBadAddress(string input) => Assert.False(ServerAddress.TryParse(input, out _, out _));

    [Fact]
    public void SettingsRoundTrip()
    {
        var path = Path.Combine(_tmp, "s", "settings.json");
        var s = new AppSettings { Token = "t", DeviceName = "Ноутбук", AutoCopyText = true };
        s.Save(path);
        var l = AppSettings.Load(path);
        Assert.Equal("t", l.Token);
        Assert.Equal("Ноутбук", l.DeviceName);
        Assert.True(l.AutoCopyText);
        Assert.True(l.IsConfigured);
        File.WriteAllText(path, "{broken");
        Assert.False(AppSettings.Load(path).IsConfigured);
    }

    sealed class SyncProgress(Action<double> report) : IProgress<double>
    {
        public void Report(double value) => report(value);
    }
}
