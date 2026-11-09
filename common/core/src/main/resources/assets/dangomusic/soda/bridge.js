/**
 * 汽水音乐桥接（DangoMusic 启动，stdin/stdout 每行一个 JSON）。
 * 负责：二维码登录、读本机登录态、生成 x-helios/x-medusa 签名、请求汽水接口。
 * sessionid 只写进 QS_SESSION_FILE，不出现在响应里。
 */
const fs = require("fs");
const os = require("os");
const path = require("path");
const zlib = require("zlib");

const UA = "LunaPC/3.5.1(30050100)";
const PC_HOST = "https://api.qishui.com";
const LUNA_HOST = "https://beta-luna.douyin.com";
const SESSION_FILE = process.env.QS_SESSION_FILE || "";

function log(message) { process.stderr.write("[soda] " + message + "\n"); }

function findInstall() {
  const candidates = [];
  if (process.env.QS_CLIENT_BASE) candidates.push(process.env.QS_CLIENT_BASE);
  candidates.push("D:\\Soda Music");
  if (process.env.LOCALAPPDATA) candidates.push(path.join(process.env.LOCALAPPDATA, "Programs", "Soda Music"));
  if (process.env.ProgramFiles) candidates.push(path.join(process.env.ProgramFiles, "Soda Music"));
  for (const base of candidates) {
    try {
      if (!fs.existsSync(base)) continue;
      if (fs.existsSync(path.join(base, "resources", "app.asar.unpacked", "bdms.node"))) return base;
      const versions = fs.readdirSync(base)
        .filter(n => /^\d+\.\d+\.\d+$/.test(n))
        .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
      for (let i = versions.length - 1; i >= 0; i--) {
        const dir = path.join(base, versions[i]);
        if (fs.existsSync(path.join(dir, "resources", "app.asar.unpacked", "bdms.node"))) return dir;
      }
    } catch (_) { }
  }
  return "";
}

function cookieDatabase() {
  const candidates = [
    path.join(os.homedir(), "AppData", "Roaming", "SodaMusic", "Network", "Cookies"),
    path.join(os.homedir(), "Library", "Containers", "com.soda.music", "Data", "Library", "Application Support", "SodaMusic", "Cookies"),
  ];
  return candidates.find(p => fs.existsSync(p)) || "";
}

function readClientSessionId() {
  const source = cookieDatabase();
  if (!source) return "";
  let DatabaseSync;
  try {
    ({ DatabaseSync } = require("node:sqlite"));
  } catch (_) {
    throw new Error("Node.js 22.5 or newer is required (node:sqlite).");
  }
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "dango-soda-"));
  const copy = path.join(directory, "Cookies");
  try {
    try {
      fs.copyFileSync(source, copy);
      for (const suffix of ["-wal", "-shm"]) {
        if (fs.existsSync(source + suffix)) fs.copyFileSync(source + suffix, copy + suffix);
      }
    } catch (error) {
      if (error && error.code === "EBUSY") throw new Error("Close the Soda Music client, then press Connect again.");
      throw error;
    }
    const db = new DatabaseSync(copy, { readOnly: true });
    try {
      const row = db.prepare("SELECT value FROM cookies WHERE host_key IN ('.qishui.com','qishui.com') AND name='sessionid' LIMIT 1").get();
      return row && row.value ? String(row.value) : "";
    } finally {
      db.close();
    }
  } finally {
    try { fs.rmSync(directory, { recursive: true, force: true }); } catch (_) { }
  }
}

function readDevice() {
  const file = path.join(os.homedir(), "AppData", "Roaming", "SodaMusic", "DeviceV1");
  if (!fs.existsSync(file)) return null;
  try {
    const value = JSON.parse(zlib.gunzipSync(fs.readFileSync(file)).toString("utf8"));
    return value && value.did && value.iid ? value : null;
  } catch (_) {
    return null;
  }
}

function loadSessionFile() {
  if (!SESSION_FILE) return "";
  try { return fs.readFileSync(SESSION_FILE, "utf8").trim(); } catch (_) { return ""; }
}

function saveSession(session) {
  state.session = session;
  if (!SESSION_FILE) return;
  try {
    fs.mkdirSync(path.dirname(SESSION_FILE), { recursive: true });
    fs.writeFileSync(SESSION_FILE, session, "utf8");
  } catch (error) {
    log("cannot save session: " + error.message);
  }
}

const state = { install: "", session: "", device: null, bdms: null, login: null };

function ensureClient() {
  if (!state.install) state.install = findInstall();
  if (!state.install) throw new Error("Soda Music desktop client was not found.");
  if (state.device === null) state.device = readDevice();
  if (!state.device) throw new Error("Soda Music device identity (DeviceV1) is missing.");
  if (!state.session) state.session = loadSessionFile();
  return state;
}

function ensureSession() {
  ensureClient();
  if (state.session) return state.session;
  try {
    const session = readClientSessionId();
    if (session) saveSession(session);
  } catch (error) {
    log("client cookies: " + error.message);
    throw error;
  }
  return state.session;
}

function signer() {
  if (state.bdms) return state.bdms;
  const unpacked = path.join(state.install, "resources", "app.asar.unpacked");
  process.env.PATH = unpacked + ";" + path.join(state.install, "mssdk") + ";" + process.env.PATH;
  const bdms = require(path.join(unpacked, "bdms.node"));
  bdms.init({ deviceId: state.device.did });
  state.bdms = bdms;
  return bdms;
}

function signature(url, headers) {
  const text = Object.entries(headers).flatMap(([key, value]) => [key, value]).join("\r\n");
  const raw = String(signer().generateHttpSignatureHeaders(url, text) || "");
  const result = {};
  const parts = raw.split("\r\n").filter(Boolean);
  for (let i = 0; i < parts.length; i += 2) result[parts[i]] = parts[i + 1];
  return result;
}

async function request(options) {
  ensureClient();
  const base = options.host === "luna" ? LUNA_HOST : PC_HOST;
  const url = new URL(base + options.path);
  const query = { ...options.query };
  if (options.host !== "luna") {
    const defaults = {
      aid: "386088", app_name: "luna_pc", device_platform: "windows", device_type: "Windows",
      os_version: "Windows 11", version_name: "3.5.1", version_code: "30050100",
      device_id: state.device.did, iid: state.device.iid, fp: state.device.did,
      channel: "official", tz_name: "Asia/Shanghai",
    };
    for (const [key, value] of Object.entries(defaults)) if (query[key] === undefined) query[key] = value;
  }
  for (const [key, value] of Object.entries(query)) url.searchParams.set(key, String(value));
  const headers = { "content-type": "application/json; charset=utf-8", "user-agent": UA };
  // beta-luna 的公开接口带上账号 Cookie 会被风控拒绝（1000006），只给 api.qishui.com 带。
  if (state.session && options.host !== "luna") headers.cookie = "sessionid=" + state.session;
  if (options.signed) Object.assign(headers, signature(url.toString(), headers));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  let response;
  try {
    response = await fetch(url.toString(), {
      method: options.method || "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }
  const text = await response.text();
  if (!text) throw new Error("Soda Music returned an empty response. The client signature was rejected; update the client and retry.");
  let json;
  try { json = JSON.parse(text); } catch (_) { throw new Error("Soda Music returned an unreadable response."); }
  const code = Number(json.status_code ?? json.error_code ?? json.status_info?.status_code ?? 0);
  if (code !== 0) throw new Error("Soda Music rejected the request (" + code + "). " + (json.status_info?.status_msg || json.description || ""));
  return json;
}

function image(value) {
  if (!value) return "";
  if (typeof value === "string") return value;
  const base = Array.isArray(value.urls) ? value.urls[0] : value.url;
  if (!base) return "";
  const uri = value.uri || "";
  const prefix = value.template_prefix || "";
  const suffix = prefix ? "~" + prefix + "-crop-center:300:300.jpg" : "";
  return (uri && !base.endsWith(uri) ? base + uri : base) + suffix;
}

function trackSummary(track) {
  return {
    id: String(track.id || ""),
    name: track.name || "",
    durationMs: Number(track.duration || 0),
    album: track.album ? { id: String(track.album.id || ""), name: track.album.name || "", cover: image(track.album.url_cover || track.album.cover_url) } : null,
    artists: (track.artists || []).map(a => ({
      id: String(a.id || ""),
      name: a.name || a.simple_display_name || a.user_info?.nickname || "",
    })).filter(a => a.name),
  };
}

async function status() {
  ensureClient();
  const result = {
    loggedIn: false, client: path.basename(state.install), deviceId: state.device.did,
    userId: "", nickname: "", avatar: "", expired: false,
  };
  if (!ensureSession()) return result;
  result.loggedIn = true;
  try {
    const body = await request({ host: "pc", method: "GET", path: "/luna/pc/me", query: { aid: "386088" } });
    const user = body.my_info || body.user || body.data || body;
    result.userId = String(user.id || user.user_id || "");
    result.nickname = user.nickname || user.name || "";
    result.avatar = image(user.avatar_url || user.avatar || user.url_avatar);
  } catch (error) {
    log("profile: " + error.message);
    if (/1000016|登录状态已失效/.test(error.message)) {
      result.loggedIn = false;
      result.expired = true;
    }
  }
  return result;
}

async function qrStart() {
  ensureClient();
  const query = new URLSearchParams({
    passport_jssdk_version: "2.8.8", passport_jssdk_type: "normal", is_from_ttaccountsdk: "1",
    aid: "386088", next: "https://music.douyin.com", need_logo: "false", need_short_url: "false", is_new_login: "1",
  });
  const response = await fetch(PC_HOST + "/passport/web/get_qrcode/?" + query.toString(), {
    headers: { "user-agent": UA },
  });
  const cookies = response.headers.getSetCookie ? response.headers.getSetCookie() : [];
  const body = await response.json();
  const data = body.data || {};
  const token = data.token || "";
  if (!token) throw new Error(data.description || "Soda Music did not return a login QR code.");
  state.login = { token, cookies: cookies.map(line => line.split(";")[0]).join("; ") };
  const scan = "https://bff-pc.qishui.com/light/invoke/scan_login?token=" + encodeURIComponent(token) +
    "&os=Windows&computer_name=" + encodeURIComponent("Dango");
  return { qrUrl: scan, expiresIn: Math.max(60, (data.expire_time || 0) * 1000 - Date.now()) };
}

async function qrPoll() {
  ensureClient();
  if (!state.login) throw new Error("Start the Soda Music login first.");
  const query = new URLSearchParams({
    passport_jssdk_version: "2.8.8", passport_jssdk_type: "normal", is_from_ttaccountsdk: "1",
    aid: "386088", iid: state.device.iid,
  });
  const form = new URLSearchParams({
    need_logo: "false", need_short_url: "false", is_frontier: "true",
    token: state.login.token, is_new_login: "1", next: "https://music.douyin.com",
  });
  const response = await fetch(PC_HOST + "/passport/web/check_qrconnect/?" + query.toString(), {
    method: "POST",
    redirect: "manual",
    headers: {
      "content-type": "application/x-www-form-urlencoded", "user-agent": UA,
      cookie: state.login.cookies, referer: "https://music.douyin.com", origin: "https://music.douyin.com",
    },
    body: form.toString(),
  });
  const cookies = response.headers.getSetCookie ? response.headers.getSetCookie() : [];
  let captured = false;
  for (const line of cookies) {
    const pair = line.split(";")[0];
    if (pair.startsWith("sessionid=")) {
      saveSession(pair.slice("sessionid=".length));
      captured = true;
    }
  }
  let body = {};
  try { body = JSON.parse(await response.text()); } catch (_) { }
  const data = body.data || body;
  const code = Number(data.error_code ?? data.status_code ?? 0);
  if (code === 7) return { status: "waiting", limited: true, error: "Too many login requests. Wait a minute and try again." };
  if (code !== 0) throw new Error(data.description || ("Soda Music login failed (" + code + ")."));
  const raw = String(data.status ?? "");
  const status = ({ "1": "waiting", "new": "waiting", "2": "scanned", "scanned": "scanned",
    "3": "confirmed", "confirmed": "confirmed", "4": "expired", "5": "expired", "refused": "expired", "expired": "expired" })[raw] || raw;
  if (captured || status === "confirmed") return { status: "confirmed", loggedIn: !!state.session };
  return { status: status || "waiting", loggedIn: false };
}

async function track(trackId) {
  ensureSession();
  const body = await request({
    host: "pc", method: "POST", path: "/luna/pc/track_v2", signed: true,
    body: { track_id: trackId, media_type: "track", queue_type: "search_one_track", scene_name: "search" },
  });
  const model = JSON.parse(body.track_player?.video_model || "{}");
  const variants = (model.video_list || []).filter(v => v.main_url).map(v => ({
    codec: v.video_meta?.codec_type || "",
    bitrate: Number(v.video_meta?.bitrate || 0),
    url: v.main_url,
    spadeA: v.encrypt_info?.spade_a || "",
  })).sort((a, b) => b.bitrate - a.bitrate);
  return {
    track: trackSummary(body.track || {}),
    streamMs: Math.round(Number(model.video_duration || 0) * 1000),
    lyric: body.lyric?.content || "",
    variants,
  };
}

async function api(message) {
  const body = await request({
    host: message.host || "pc",
    method: message.method || "GET",
    path: message.path,
    query: message.query || {},
    body: message.body,
    signed: (message.host || "pc") !== "luna",
  });
  return { data: body };
}

function handle(message) {
  switch (message.op) {
    case "status": return status();
    case "track": return track(String(message.trackId || ""));
    case "api": return api(message);
    case "qrStart": return qrStart();
    case "qrPoll": return qrPoll();
    case "logout": state.session = ""; state.login = null; if (SESSION_FILE) { try { fs.rmSync(SESSION_FILE, { force: true }); } catch (_) { } } return { ok: true };
    case "ping": return { pong: true };
    default: throw new Error("Unknown operation: " + message.op);
  }
}

process.stdout.write(JSON.stringify({ ready: true, node: process.version }) + "\n");
const readline = require("readline").createInterface({ input: process.stdin });
readline.on("line", line => {
  if (!line.trim()) return;
  let message;
  try { message = JSON.parse(line); } catch (_) { return; }
  Promise.resolve()
    .then(() => handle(message))
    .then(data => ({ id: message.id, ok: true, ...data }))
    .catch(error => ({ id: message.id, ok: false, error: String((error && error.message) || error) }))
    .then(reply => process.stdout.write(JSON.stringify(reply) + "\n"));
});
readline.on("close", () => process.exit(0));