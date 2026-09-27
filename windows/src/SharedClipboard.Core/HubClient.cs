using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace SharedClipboard.Core;

public enum HubState
{
    NotConfigured,
    Connecting,
    Online,
    Offline,
    AuthFailed,
}

/// <summary>
/// Connection to the clipd hub on the router: a WebSocket for live updates
/// (reconnects forever) plus HTTP for sending text and moving files.
/// Events are raised on background threads.
/// </summary>
public sealed class HubClient : IDisposable
{
    static readonly TimeSpan PingInterval = TimeSpan.FromSeconds(20);
    static readonly TimeSpan SilenceTimeout = TimeSpan.FromSeconds(50);
    static readonly TimeSpan MaxBackoff = TimeSpan.FromSeconds(15);

    readonly object _lock = new();
    readonly List<ClipItem> _items = []; // oldest first
    readonly HttpClient _http;
    AppSettings _settings = new();
    Uri? _base;
    CancellationTokenSource? _loopCts;

    public HubClient(HttpMessageHandler? handler = null)
    {
        _http = new HttpClient(handler ?? new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(5),
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        })
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };
    }

    public HubState State { get; private set; } = HubState.NotConfigured;
    public string? LastError { get; private set; }
    public IReadOnlyList<string> Devices { get; private set; } = [];
    public Limits Limits { get; private set; } = new();
    public string DeviceName => _settings.DeviceName;

    /// <summary>State changed (see <see cref="State"/>, <see cref="LastError"/>).</summary>
    public event Action<HubState>? StateChanged;
    /// <summary>Full snapshot after (re)connecting, oldest first.</summary>
    public event Action<IReadOnlyList<ClipItem>>? Synced;
    public event Action<ClipItem>? ItemAdded;
    public event Action<string>? ItemRemoved;
    public event Action<IReadOnlyList<string>>? DevicesChanged;

    public IReadOnlyList<ClipItem> Items
    {
        get { lock (_lock) return _items.ToList(); }
    }

    /// <summary>Applies new settings and (re)starts the connection loop.</summary>
    public void Configure(AppSettings settings)
    {
        Stop();
        _settings = settings.Clone();
        lock (_lock)
            _items.Clear();
        if (!settings.IsConfigured || !ServerAddress.TryParse(settings.ServerUrl, out var b, out _))
        {
            _base = null;
            SetState(HubState.NotConfigured);
            return;
        }
        _base = b;
        _loopCts = new CancellationTokenSource();
        var ct = _loopCts.Token;
        _ = Task.Run(() => RunAsync(ct));
    }

    public void Stop()
    {
        _loopCts?.Cancel();
        _loopCts?.Dispose();
        _loopCts = null;
    }

    public void Dispose()
    {
        Stop();
        _http.Dispose();
    }

    void SetState(HubState state, string? error = null)
    {
        LastError = error;
        if (State == state && error == null)
            return;
        State = state;
        StateChanged?.Invoke(state);
    }

    Uri WebSocketUri()
    {
        var b = new UriBuilder(new Uri(_base!, "ws"))
        {
            Scheme = _base!.Scheme == "https" ? "wss" : "ws",
            Query = "device=" + Uri.EscapeDataString(_settings.DeviceName),
        };
        return b.Uri;
    }

    async Task RunAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(1);
        while (!ct.IsCancellationRequested)
        {
            SetState(HubState.Connecting);
            using var ws = new ClientWebSocket();
            ws.Options.SetRequestHeader("Authorization", "Bearer " + _settings.Token);
            ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
            ws.Options.CollectHttpResponseDetails = true;
            try
            {
                using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                connectCts.CancelAfter(TimeSpan.FromSeconds(10));
                await ws.ConnectAsync(WebSocketUri(), connectCts.Token);
            }
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                if (ws.HttpStatusCode == HttpStatusCode.Unauthorized)
                {
                    SetState(HubState.AuthFailed, "Неверный токен");
                    await Delay(TimeSpan.FromSeconds(30), ct);
                    continue;
                }
                Log.Write($"hub: connect failed: {e.Message}");
                SetState(HubState.Offline, "Роутер недоступен");
                await Delay(backoff, ct);
                backoff = TimeSpan.FromTicks(Math.Min(backoff.Ticks * 2, MaxBackoff.Ticks));
                continue;
            }
            catch
            {
                return;
            }

            backoff = TimeSpan.FromSeconds(1);
            Log.Write("hub: connected");
            try
            {
                await ReceiveLoopAsync(ws, ct);
            }
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                Log.Write($"hub: connection lost: {e.Message}");
            }
            catch
            {
                return;
            }
            if (ct.IsCancellationRequested)
                return;
            SetState(HubState.Offline, "Соединение потеряно");
            await Delay(TimeSpan.FromSeconds(1), ct);
        }
    }

    static async Task Delay(TimeSpan t, CancellationToken ct)
    {
        try
        {
            await Task.Delay(t, ct);
        }
        catch (OperationCanceledException)
        {
        }
    }

    async Task ReceiveLoopAsync(ClientWebSocket ws, CancellationToken ct)
    {
        // The watchdog aborts the socket when the router goes silent (e.g.
        // the laptop moved to another network), otherwise a half-open TCP
        // connection could hang for a long time.
        using var watchdog = CancellationTokenSource.CreateLinkedTokenSource(ct);
        watchdog.CancelAfter(SilenceTimeout);
        var pinger = PingLoopAsync(ws, watchdog.Token);

        var buffer = new byte[64 * 1024];
        using var msg = new MemoryStream();
        try
        {
            while (ws.State == WebSocketState.Open)
            {
                var r = await ws.ReceiveAsync(buffer, watchdog.Token);
                if (r.MessageType == WebSocketMessageType.Close)
                    break;
                watchdog.CancelAfter(SilenceTimeout);
                msg.Write(buffer, 0, r.Count);
                if (!r.EndOfMessage)
                    continue;
                var data = msg.ToArray();
                msg.SetLength(0);
                HubMessage? m;
                try
                {
                    m = JsonSerializer.Deserialize<HubMessage>(data, Json.Options);
                }
                catch (JsonException)
                {
                    continue;
                }
                if (m != null && !ct.IsCancellationRequested)
                    Handle(m);
            }
        }
        finally
        {
            watchdog.Cancel();
            await pinger;
        }
    }

    async Task PingLoopAsync(ClientWebSocket ws, CancellationToken ct)
    {
        var ping = Encoding.UTF8.GetBytes("""{"type":"ping"}""");
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(PingInterval, ct);
                await ws.SendAsync(ping, WebSocketMessageType.Text, true, ct);
            }
        }
        catch
        {
            // The receive loop notices the broken connection.
        }
    }

    void Handle(HubMessage m)
    {
        switch (m.Type)
        {
            case "hello":
            {
                List<ClipItem> snapshot;
                lock (_lock)
                {
                    _items.Clear();
                    _items.AddRange((m.Items ?? []).Where(i => i.Id.Length > 0));
                    snapshot = _items.ToList();
                }
                if (m.Limits != null)
                    Limits = m.Limits;
                Devices = m.Devices ?? [];
                SetState(HubState.Online);
                Synced?.Invoke(snapshot);
                DevicesChanged?.Invoke(Devices);
                break;
            }
            case "clip" when m.Item is { Id.Length: > 0 } item:
                lock (_lock)
                {
                    if (_items.Any(i => i.Id == item.Id))
                        return;
                    _items.Add(item);
                }
                ItemAdded?.Invoke(item);
                break;
            case "delete" when m.Id != null:
                int removed;
                lock (_lock)
                    removed = _items.RemoveAll(i => i.Id == m.Id);
                if (removed > 0)
                    ItemRemoved?.Invoke(m.Id);
                break;
            case "devices":
                Devices = m.Devices ?? [];
                DevicesChanged?.Invoke(Devices);
                break;
            case "error":
                Log.Write($"hub: server error: {m.Error}");
                break;
        }
    }

    // ---- HTTP ----

    HttpRequestMessage Request(HttpMethod method, string path)
    {
        if (_base == null)
            throw new ClipException("Не настроен адрес роутера или токен");
        var r = new HttpRequestMessage(method, new Uri(_base, path));
        r.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _settings.Token);
        r.Headers.Add("X-Device", Uri.EscapeDataString(_settings.DeviceName));
        return r;
    }

    async Task<HttpResponseMessage> SendAsync(HttpRequestMessage req, CancellationToken ct,
        HttpCompletionOption opt = HttpCompletionOption.ResponseContentRead)
    {
        HttpResponseMessage resp;
        try
        {
            resp = await _http.SendAsync(req, opt, ct);
        }
        catch (HttpRequestException e)
        {
            throw new ClipException("Роутер недоступен", e);
        }
        if (resp.IsSuccessStatusCode)
            return resp;

        string? serverMessage = null;
        try
        {
            var body = await resp.Content.ReadFromJsonAsync<Dictionary<string, string>>(ct);
            body?.TryGetValue("error", out serverMessage);
        }
        catch
        {
        }
        var code = resp.StatusCode;
        resp.Dispose();
        throw new ClipException(code switch
        {
            HttpStatusCode.Unauthorized => "Неверный токен",
            HttpStatusCode.RequestEntityTooLarge => $"Слишком большой размер (максимум {Format.Size(Limits.MaxFileBytes)})",
            HttpStatusCode.InsufficientStorage => "На роутере закончилось место",
            HttpStatusCode.NotFound => "Запись уже удалена",
            _ => $"Ошибка сервера: {serverMessage ?? code.ToString()}",
        });
    }

    public async Task<ClipItem> SendTextAsync(string text, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(text))
            throw new ClipException("Пустой текст");
        if (Encoding.UTF8.GetByteCount(text) > Limits.MaxTextBytes)
            throw new ClipException($"Текст больше {Format.Size(Limits.MaxTextBytes)}");
        using var req = Request(HttpMethod.Post, "api/clip");
        req.Content = JsonContent.Create(new { text }, options: Json.Options);
        using var resp = await SendAsync(req, ct);
        return (await resp.Content.ReadFromJsonAsync<ClipItem>(Json.Options, ct))!;
    }

    /// <summary>Uploads a file as is. The hub echoes the new item over the WebSocket too.</summary>
    public async Task<ClipItem> UploadFileAsync(string path, string? name = null,
        IProgress<double>? progress = null, CancellationToken ct = default)
    {
        var fi = new FileInfo(path);
        if (!fi.Exists)
            throw new ClipException($"Файл не найден: {path}");
        if (fi.Length == 0)
            throw new ClipException($"Файл пустой: {fi.Name}");
        if (fi.Length > Limits.MaxFileBytes)
            throw new ClipException($"{fi.Name}: больше {Format.Size(Limits.MaxFileBytes)}");

        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 81920, useAsync: true);
        string sha;
        using (var h = SHA256.Create())
            sha = Convert.ToHexString(await h.ComputeHashAsync(fs, ct)).ToLowerInvariant();
        fs.Position = 0;

        using var req = Request(HttpMethod.Put, "api/files?name=" + Uri.EscapeDataString(name ?? fi.Name));
        req.Headers.Add("X-Sha256", sha);
        req.Content = new ProgressStreamContent(fs, fi.Length, progress);
        using var resp = await SendAsync(req, ct);
        return (await resp.Content.ReadFromJsonAsync<ClipItem>(Json.Options, ct))!;
    }

    /// <summary>Downloads a file item to <paramref name="destPath"/>, verifying its SHA-256.</summary>
    public async Task DownloadAsync(ClipItem item, string destPath, IProgress<double>? progress = null, CancellationToken ct = default)
    {
        if (!item.IsFile)
            throw new ArgumentException("not a file item", nameof(item));
        Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
        var part = destPath + ".part";
        using var req = Request(HttpMethod.Get, "api/files/" + Uri.EscapeDataString(item.Id));
        using var resp = await SendAsync(req, ct, HttpCompletionOption.ResponseHeadersRead);
        await using (var src = await resp.Content.ReadAsStreamAsync(ct))
        await using (var dst = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true))
        using (var h = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
        {
            var buf = new byte[81920];
            long done = 0;
            int n;
            while ((n = await src.ReadAsync(buf, ct)) > 0)
            {
                h.AppendData(buf, 0, n);
                await dst.WriteAsync(buf.AsMemory(0, n), ct);
                done += n;
                progress?.Report(item.File!.Size > 0 ? (double)done / item.File.Size : 1);
            }
            var sha = Convert.ToHexString(h.GetHashAndReset()).ToLowerInvariant();
            if (!string.Equals(sha, item.File!.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                dst.Close();
                System.IO.File.Delete(part);
                throw new ClipException($"{item.File.Name}: файл повреждён при передаче");
            }
        }
        System.IO.File.Move(part, destPath, overwrite: true);
    }

    /// <summary>
    /// Re-reads the list over HTTP and raises <see cref="Synced"/>. Used when the
    /// panel opens, in case the computer slept and events were missed.
    /// </summary>
    public async Task RefreshAsync(CancellationToken ct = default)
    {
        if (State != HubState.Online)
            return;
        using var req = Request(HttpMethod.Get, "api/items");
        using var resp = await SendAsync(req, ct);
        var list = await resp.Content.ReadFromJsonAsync<List<ClipItem>>(Json.Options, ct) ?? [];
        List<ClipItem> snapshot;
        lock (_lock)
        {
            _items.Clear();
            _items.AddRange(list.Where(i => i.Id.Length > 0));
            snapshot = _items.ToList();
        }
        Synced?.Invoke(snapshot);
    }

    public async Task DeleteAsync(string id, CancellationToken ct = default)
    {
        using var req = Request(HttpMethod.Delete, "api/items/" + Uri.EscapeDataString(id));
        try
        {
            using var _ = await SendAsync(req, ct);
        }
        catch (ClipException) when (!Items.Any(i => i.Id == id))
        {
            // Already gone.
        }
    }

    /// <summary>Checks address and token; returns null when fine or a message for the user.</summary>
    public static async Task<string?> CheckAsync(AppSettings settings, CancellationToken ct = default)
    {
        if (!ServerAddress.TryParse(settings.ServerUrl, out var b, out _))
            return "Неправильный адрес";
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, new Uri(b, "api/items"));
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
            using var resp = await http.SendAsync(req, ct);
            return resp.StatusCode switch
            {
                HttpStatusCode.OK => null,
                HttpStatusCode.Unauthorized => "Роутер отвечает, но токен неверный",
                _ => $"Роутер ответил: {(int)resp.StatusCode}. Это точно адрес clipd (порт {AppSettings.DefaultPort})?",
            };
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            return $"Нет связи с {b.Host}:{b.Port}";
        }
    }
}

/// <summary>Streams a file and reports upload progress (0..1).</summary>
sealed class ProgressStreamContent(Stream stream, long length, IProgress<double>? progress) : HttpContent
{
    protected override async Task SerializeToStreamAsync(Stream target, TransportContext? context)
    {
        var buf = new byte[81920];
        long done = 0;
        int n;
        while ((n = await stream.ReadAsync(buf)) > 0)
        {
            await target.WriteAsync(buf.AsMemory(0, n));
            done += n;
            progress?.Report(length > 0 ? (double)done / length : 1);
        }
    }

    protected override bool TryComputeLength(out long len)
    {
        len = length;
        return true;
    }
}
