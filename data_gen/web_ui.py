"""
数据产生端 — 简易 Web 控制台
"""
import json
import os
import time
from datetime import datetime
from pathlib import Path

import docker
from flask import Flask, render_template_string, request, jsonify

app = Flask(__name__)

JSONL_DIR = Path(os.environ.get("JSONL_DIR", str(Path(__file__).parent / "jsonl")))
JSONL_DIR.mkdir(exist_ok=True)
JSONL_HOST_DIR = os.environ.get("JSONL_HOST_DIR", str(JSONL_DIR))

CONTAINER_NAME = "stock_collector"
COLLECTOR_IMAGE = os.environ.get("COLLECTOR_IMAGE", "stock_collector:latest")

dc = docker.from_env()

PAGE = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>股票行情采集器</title>
<style>
  * { box-sizing:border-box; margin:0; padding:0; }
  body { font-family: 'Segoe UI', system-ui, sans-serif; background:#f0f2f5; color:#333; min-height:100vh; }
  .container { max-width:780px; margin:0 auto; padding:24px 16px; }
  h1 { font-size:22px; margin-bottom:4px; }
  .sub { color:#888; font-size:13px; margin-bottom:24px; }

  .card { background:#fff; border-radius:10px; padding:20px 24px; margin-bottom:16px; box-shadow:0 1px 3px rgba(0,0,0,.08); }
  .card-title { font-size:15px; font-weight:600; margin-bottom:14px; }

  .modes { display:flex; gap:12px; }
  .mode-option { flex:1; border:2px solid #e0e0e0; border-radius:8px; padding:16px; cursor:pointer; text-align:center; transition:.15s; }
  .mode-option:hover { border-color:#b0b0b0; }
  .mode-option.active { border-color:#1677ff; background:#f0f5ff; }
  .mode-option input { display:none; }
  .mode-label { font-size:15px; font-weight:600; display:block; }
  .mode-desc { font-size:12px; color:#888; margin-top:4px; display:block; }

  .upload-zone { border:2px dashed #d9d9d9; border-radius:8px; padding:24px; text-align:center; cursor:pointer; transition:.15s; margin-top:12px; display:none; }
  .upload-zone:hover { border-color:#1677ff; }
  .upload-zone input { display:none; }
  .upload-icon { font-size:32px; margin-bottom:8px; }
  .upload-text { font-size:13px; color:#666; }

  .file-list { margin-top:10px; display:none; }
  .file-item { display:flex; justify-content:space-between; align-items:center; padding:6px 10px; background:#fafafa; border-radius:4px; margin-top:4px; font-size:13px; }
  .file-item .del { color:#ff4d4f; cursor:pointer; font-size:18px; }

  .controls { display:flex; gap:10px; align-items:center; }
  .btn { padding:8px 22px; border:none; border-radius:6px; font-size:14px; cursor:pointer; font-weight:500; }
  .btn-start { background:#1677ff; color:#fff; }
  .btn-start:hover { background:#4096ff; }
  .btn-stop { background:#ff4d4f; color:#fff; }
  .btn-stop:hover { background:#ff7875; }
  .btn:disabled { opacity:.5; cursor:not-allowed; }

  .status-dot { width:8px; height:8px; border-radius:50%; display:inline-block; margin-right:6px; }
  .status-dot.running { background:#52c41a; animation:pulse 1.5s infinite; }
  .status-dot.stopped { background:#d9d9d9; }
  @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }

  .log-box { background:#1e1e1e; color:#d4d4d4; border-radius:8px; padding:14px; font-family:Consolas,monospace; font-size:12px; height:320px; overflow-y:auto; white-space:pre-wrap; word-break:break-all; line-height:1.5; }
  .log-box .dim { color:#888; }
  .log-box .warn { color:#e8b340; }
  .log-box .info { color:#4fc1ff; }

  .form-row { margin-bottom:10px; }
  .form-row label { font-size:13px; color:#666; display:block; margin-bottom:4px; }
  .form-row input { width:100%; padding:6px 10px; border:1px solid #d9d9d9; border-radius:4px; font-size:13px; }

  .toast { position:fixed; top:16px; right:16px; background:#333; color:#fff; padding:10px 20px; border-radius:6px; font-size:13px; animation:fadeIn .2s; z-index:999; }
  @keyframes fadeIn { from{opacity:0;transform:translateY(-8px)} to{opacity:1;transform:translateY(0)} }
</style>
</head>
<body>
<div class="container">
  <h1>股票行情采集器</h1>
  <p class="sub">A 股数据采集 · 双数据源 · Kafka + Redis 输出</p>

  <div class="card">
    <div class="card-title">数据源</div>
    <div class="modes" id="modeSelector">
      <label class="mode-option active" onclick="selectMode('sina')">
        <input type="radio" name="source" value="sina" checked>
        <span class="mode-label">新浪 API 实时</span>
        <span class="mode-desc">HTTP 实时拉取 A 股行情</span>
      </label>
      <label class="mode-option" onclick="selectMode('jsonl')">
        <input type="radio" name="source" value="jsonl">
        <span class="mode-label">JSONL 文件回放</span>
        <span class="mode-desc">上传历史数据文件回放</span>
      </label>
    </div>

    <div class="upload-zone" id="uploadZone" onclick="document.getElementById('fileInput').click()">
      <div class="upload-icon">⬆</div>
      <div class="upload-text">点击上传 .jsonl 文件</div>
      <input type="file" id="fileInput" accept=".jsonl" onchange="uploadFile()">
    </div>
    <div class="file-list" id="fileList"></div>

    <div id="sinaParams" style="margin-top:12px;">
      <div class="form-row">
        <label>股票池大小（0=全量 ~5400 支）</label>
        <input type="number" id="stockLimit" value="200" min="0" max="6000">
      </div>
      <div class="form-row">
        <label>每轮采集数（滚动窗口）</label>
        <input type="number" id="rollingSize" value="200" min="10" max="1000">
      </div>
      <div class="form-row">
        <label>采集间隔（秒）</label>
        <input type="number" id="interval_sina" value="30" min="0.1" max="300" step="0.1">
      </div>
    </div>

    <div id="jsonlParams" style="display:none; margin-top:12px;">
      <div class="form-row">
        <label>回放间隔（秒）</label>
        <input type="number" id="interval_jsonl" value="1" min="0.1" max="60" step="0.1">
      </div>
      <div class="form-row">
        <label>日期过滤（只推送该 trade_date 的记录，留空=全部）</label>
        <input type="text" id="filter_date" placeholder="例: 2026-06-30" style="width:100%;padding:6px 10px;border:1px solid #d9d9d9;border-radius:4px;font-size:13px;">
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-title">控制</div>
    <div class="controls">
      <button class="btn btn-start" id="btnStart" onclick="startCollector()">▶ 启动采集</button>
      <button class="btn btn-stop" id="btnStop" onclick="stopCollector()" disabled>⏹ 停止</button>
      <span style="margin-left:auto;font-size:13px;">
        状态：<span class="status-dot stopped" id="statusDot"></span>
        <span id="statusText">已停止</span>
      </span>
    </div>
  </div>

  <div class="card">
    <div class="card-title" style="display:flex;justify-content:space-between;">
      运行日志
      <button class="btn" style="padding:4px 12px;font-size:12px;background:#f5f5f5;" onclick="fetchLogs()">刷新</button>
    </div>
    <div class="log-box" id="logBox">等待启动...</div>
  </div>
</div>

<div id="toastContainer"></div>

<script>
let currentMode = 'sina';
let pollingTimer = null;

function selectMode(mode) {
  currentMode = mode;
  document.querySelectorAll('.mode-option').forEach(el => el.classList.remove('active'));
  document.querySelector('input[value="' + mode + '"]').closest('.mode-option').classList.add('active');
  document.getElementById('uploadZone').style.display = mode === 'jsonl' ? 'block' : 'none';
  document.getElementById('fileList').style.display = mode === 'jsonl' ? 'block' : 'none';
  document.getElementById('sinaParams').style.display = mode === 'sina' ? 'block' : 'none';
  document.getElementById('jsonlParams').style.display = mode === 'jsonl' ? 'block' : 'none';
  if (mode === 'jsonl') refreshFiles();
}

function toast(msg) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = msg;
  document.getElementById('toastContainer').appendChild(el);
  setTimeout(() => el.remove(), 2000);
}

function uploadFile() {
  const file = document.getElementById('fileInput').files[0];
  if (!file) return;
  const form = new FormData();
  form.append('file', file);
  fetch('/upload', {method:'POST', body:form})
    .then(r => r.json()).then(d => { toast(d.msg); refreshFiles(); })
    .catch(e => toast('上传失败: ' + e));
  document.getElementById('fileInput').value = '';
}

function refreshFiles() {
  fetch('/files').then(r => r.json()).then(files => {
    const el = document.getElementById('fileList');
    if (files.length === 0) {
      el.innerHTML = '<div style="font-size:12px;color:#999;padding:6px;">暂无文件</div>';
      return;
    }
    el.innerHTML = files.map(f =>
      '<div class="file-item">' +
        '<span>' + f.name + ' <span style="color:#999;font-size:11px;">(' + f.size_mb + ' MB)</span></span>' +
        '<span class="del" onclick="deleteFile(&quot;' + f.name + '&quot;)" title="删除">x</span>' +
      '</div>'
    ).join('');
  });
}

function deleteFile(name) {
  fetch('/files/' + encodeURIComponent(name), {method:'DELETE'})
    .then(r => r.json()).then(d => { toast(d.msg); refreshFiles(); });
}

function startCollector() {
  const params = new URLSearchParams();
  params.append('source', currentMode);
  if (currentMode === 'sina') {
    params.append('limit', document.getElementById('stockLimit').value);
    params.append('rolling', document.getElementById('rollingSize').value);
    params.append('interval', document.getElementById('interval_sina').value);
  } else {
    params.append('interval', document.getElementById('interval_jsonl').value);
    params.append('filter_date', document.getElementById('filter_date').value);
  }
  fetch('/start?' + params.toString(), {method:'POST'})
    .then(r => r.json()).then(d => {
      toast(d.msg);
      if (d.ok) startPolling();
    });
}

function stopCollector() {
  fetch('/stop', {method:'POST'})
    .then(r => r.json()).then(d => {
      toast(d.msg);
      stopPolling();
      refreshStatus();
    });
}

function refreshStatus() {
  fetch('/status').then(r => r.json()).then(d => {
    const running = d.running;
    document.getElementById('btnStart').disabled = running;
    document.getElementById('btnStop').disabled = !running;
    const dot = document.getElementById('statusDot');
    dot.className = 'status-dot ' + (running ? 'running' : 'stopped');
    document.getElementById('statusText').textContent = running ? '运行中' : '已停止';
    if (running && !pollingTimer) startPolling();
    if (!running && pollingTimer) stopPolling();
  });
}

function fetchLogs() {
  fetch('/logs').then(r => r.json()).then(d => {
    const box = document.getElementById('logBox');
    box.innerHTML = d.lines.map(l => formatLog(l)).join('\\n') || '暂无日志';
    box.scrollTop = box.scrollHeight;
  });
}

function formatLog(line) {
  if (/WARNING|WARN/.test(line)) return '<span class="warn">' + esc(line) + '</span>';
  if (/INFO/.test(line)) return '<span class="info">' + esc(line) + '</span>';
  return '<span class="dim">' + esc(line) + '</span>';
}
function esc(s) { return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function startPolling() {
  if (pollingTimer) return;
  pollingTimer = setInterval(() => { refreshStatus(); fetchLogs(); }, 2000);
  refreshStatus(); fetchLogs();
}

function stopPolling() {
  clearInterval(pollingTimer);
  pollingTimer = null;
}

refreshStatus(); fetchLogs();
</script>
</body>
</html>"""


def _get_container():
    """Return the collector container or None."""
    try:
        return dc.containers.get(CONTAINER_NAME)
    except Exception:
        return None


def _stop_container():
    c = _get_container()
    if c:
        try:
            c.stop()
        except Exception:
            pass
        time.sleep(1)


@app.route("/")
def index():
    resp = app.make_response(render_template_string(PAGE))
    resp.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    return resp


@app.route("/upload", methods=["POST"])
def upload():
    f = request.files.get("file")
    if not f or not f.filename.endswith(".jsonl"):
        return jsonify(ok=False, msg="请选择 .jsonl 文件")
    path = JSONL_DIR / f.filename
    f.save(str(path))
    size_mb = path.stat().st_size / 1024 / 1024
    return jsonify(ok=True, msg=f"已上传 {f.filename} ({size_mb:.1f} MB)")


@app.route("/files")
def list_files():
    files = []
    for p in sorted(JSONL_DIR.glob("*.jsonl")):
        files.append({
            "name": p.name,
            "size_mb": round(p.stat().st_size / 1024 / 1024, 1),
        })
    return jsonify(files)


@app.route("/files/<name>", methods=["DELETE"])
def delete_file(name):
    p = (JSONL_DIR / name).resolve()
    if not str(p).startswith(str(JSONL_DIR.resolve()) + os.sep):
        return jsonify(ok=False, msg="非法文件名"), 403
    if p.exists():
        p.unlink()
        return jsonify(ok=True, msg=f"已删除 {name}")
    return jsonify(ok=False, msg="文件不存在")


@app.route("/status")
def status():
    c = _get_container()
    running = c is not None and c.status == "running"
    return jsonify(running=running)


@app.route("/logs")
def logs():
    c = _get_container()
    if c:
        try:
            out = c.logs(tail=50).decode("utf-8", errors="replace")
            return jsonify(lines=out.strip().split("\n") if out else [])
        except Exception:
            pass
    return jsonify(lines=[])


@app.route("/start", methods=["POST"])
def start():
    source = request.args.get("source", "sina")
    interval = request.args.get("interval", "30")
    stock_limit = request.args.get("limit", "200")
    rolling_size = request.args.get("rolling", "200")
    filter_date = request.args.get("filter_date", "")

    _stop_container()

    env = {
        "SOURCE_TYPE": source,
        "INTERVAL": interval,
        "STOCK_LIMIT": stock_limit,
        "ROLLING_SIZE": rolling_size,
        "JSONL_FILTER_DATE": filter_date,
        "REDIS_PASSWORD": os.environ.get("REDIS_PASSWORD", ""),
    }

    volumes = {}
    if source == "jsonl":
        volumes[JSONL_HOST_DIR] = {"bind": "/app/jsonl", "mode": "rw"}

    try:
        dc.containers.run(
            COLLECTOR_IMAGE,
            name=CONTAINER_NAME,
            detach=True,
            remove=True,
            network_mode="host",
            environment=env,
            volumes=volumes,
        )
        return jsonify(ok=True, msg=f"采集器已启动（{source} 模式）")
    except Exception as e:
        return jsonify(ok=False, msg=f"启动失败: {e}")


@app.route("/stop", methods=["POST"])
def stop():
    _stop_container()
    return jsonify(ok=True, msg="采集器已停止")


if __name__ == "__main__":
    print(f"JSONL dir: {JSONL_DIR}")
    print("启动 Web 控制台: http://127.0.0.1:5050")
    app.run(host="0.0.0.0", port=5050, debug=False)
