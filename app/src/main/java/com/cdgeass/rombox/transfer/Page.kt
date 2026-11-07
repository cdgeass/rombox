package com.cdgeass.rombox.transfer

import com.cdgeass.rombox.core.rom.RomFile
import com.cdgeass.rombox.core.util.Fmt

/** 无线传输用的网页（单页，内联 CSS/JS，无外部依赖） */
object Page {

    fun render(
        items: List<RomFile>,
        dirPath: String,
        platformName: String,
        freeSpace: Long,
        accept: String,
    ): String {
        val rows = StringBuilder()
        items.forEach { rom ->
            val region = rom.region?.take(3)?.uppercase() ?: "GBA"
            rows.append(
                """
                <li class="rom">
                  <span class="badge">${esc(region)}</span>
                  <span class="meta">
                    <span class="name">${esc(rom.name)}</span>
                    <span class="sub">${esc(Fmt.bytes(rom.size))}${rom.gameCode?.let { " · ${esc(it)}" } ?: ""}</span>
                  </span>
                  <span class="act">
                    <button class="ghost" data-act="rename" data-name="${esc(rom.name)}">改名</button>
                    <button class="ghost danger" data-act="delete" data-name="${esc(rom.name)}">删除</button>
                  </span>
                </li>
                """.trimIndent()
            )
        }
        if (items.isEmpty()) {
            rows.append("""<li class="empty">还没有 ROM，把文件拖到上面上传吧</li>""")
        }

        val totalSize = items.sumOf { it.size }
        return """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RomBox · 无线传输</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 24px 16px 48px;
    font-family: -apple-system, "Segoe UI", "Microsoft YaHei", Roboto, sans-serif;
    background: #10150F; color: #E4EAE2;
    background-image: radial-gradient(circle at 20% -10%, #1D3527 0%, #10150F 55%);
    min-height: 100vh;
  }
  .wrap { max-width: 860px; margin: 0 auto; }
  h1 { font-size: 22px; margin: 0 0 4px; letter-spacing: .5px; }
  h1 span { color: #8BD6AA; }
  .hint { color: #9BAAA0; font-size: 13px; margin: 0 0 18px; }
  .hint b { color: #C8D6CC; font-weight: 600; }
  .card {
    background: #18201A; border: 1px solid #263229; border-radius: 16px;
    padding: 16px; margin-bottom: 16px;
  }
  .drop {
    border: 2px dashed #33463A; border-radius: 14px; padding: 26px 16px;
    text-align: center; cursor: pointer; transition: .15s; background: #131A15;
  }
  .drop:hover, .drop.over { border-color: #8BD6AA; background: #16231B; }
  .drop strong { display: block; font-size: 15px; margin-bottom: 6px; }
  .drop em { color: #8FA394; font-size: 12px; font-style: normal; }
  #file { display: none; }
  .bar { height: 6px; border-radius: 3px; background: #263229; overflow: hidden; margin-top: 14px; display: none; }
  .bar > i { display: block; height: 100%; width: 0; background: linear-gradient(90deg, #8BD6AA, #4EA277); transition: width .1s; }
  #status { font-size: 12px; color: #9BAAA0; margin-top: 8px; min-height: 16px; white-space: pre-wrap; }
  ul.list { list-style: none; margin: 0; padding: 0; }
  li.rom {
    display: flex; align-items: center; gap: 10px;
    padding: 10px 4px; border-bottom: 1px solid #232E26;
  }
  li.rom:last-child { border-bottom: none; }
  li.empty { color: #7E8D83; font-size: 13px; padding: 16px 4px; text-align: center; }
  .badge {
    flex: 0 0 auto; font-size: 10px; font-weight: 700; letter-spacing: .5px;
    color: #9BE9BC; background: #1F3527; border: 1px solid #2F4D39;
    border-radius: 6px; padding: 3px 6px; min-width: 38px; text-align: center;
  }
  .meta { flex: 1 1 auto; min-width: 0; }
  .name { display: block; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sub { display: block; font-size: 11px; color: #8FA394; margin-top: 2px; }
  .act { flex: 0 0 auto; display: flex; gap: 4px; }
  button {
    font-family: inherit; font-size: 12px; border-radius: 8px; cursor: pointer;
    border: 1px solid #33463A; background: #1D2A21; color: #D6E3DA; padding: 5px 10px;
  }
  button:hover { border-color: #8BD6AA; color: #B7F0CE; }
  button.danger:hover { border-color: #E38B8B; color: #FFC4C4; }
  .stat { display: flex; flex-wrap: wrap; gap: 18px; font-size: 12px; color: #9BAAA0; margin-bottom: 10px; }
  .stat b { color: #D6E3DA; font-weight: 600; }
  code { font-size: 11px; color: #8BD6AA; word-break: break-all; }
  .foot { text-align: center; font-size: 11px; color: #6C7B71; margin-top: 22px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Rom<span>Box</span></h1>
  <p class="hint">${esc(platformName)} · 上传到 <b>${esc(dirPath)}</b></p>

  <div class="card">
    <div class="drop" id="drop">
      <strong>点击选择 ROM，或把文件拖到这里</strong>
      <em>支持 ${esc(accept)}；上传 .zip 会自动解出里面的 ROM</em>
    </div>
    <input id="file" type="file" multiple accept="${esc(accept)}">
    <div class="bar" id="bar"><i id="barfill"></i></div>
    <div id="status"></div>
  </div>

  <div class="card">
    <div class="stat">
      <span>ROM：<b>${items.size}</b> 个</span>
      <span>占用：<b>${esc(Fmt.bytes(totalSize))}</b></span>
      <span>剩余空间：<b>${esc(Fmt.bytes(freeSpace))}</b></span>
    </div>
    <ul class="list" id="list">
      $rows
    </ul>
  </div>

  <p class="foot">RomBox · 关闭网页不影响传文件，手机端可随时停止服务</p>
</div>

<script>
(function () {
  var drop = document.getElementById('drop');
  var input = document.getElementById('file');
  var bar = document.getElementById('bar');
  var fill = document.getElementById('barfill');
  var status = document.getElementById('status');

  drop.addEventListener('click', function () { input.click(); });
  input.addEventListener('change', function () {
    if (input.files.length) upload(Array.prototype.slice.call(input.files));
  });
  ['dragenter', 'dragover'].forEach(function (type) {
    drop.addEventListener(type, function (e) { e.preventDefault(); drop.classList.add('over'); });
  });
  ['dragleave', 'drop'].forEach(function (type) {
    drop.addEventListener(type, function (e) { e.preventDefault(); drop.classList.remove('over'); });
  });
  drop.addEventListener('drop', function (e) {
    if (e.dataTransfer.files.length) upload(Array.prototype.slice.call(e.dataTransfer.files));
  });

  function human(n) {
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(0) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }

  async function upload(files) {
    bar.style.display = 'block';
    for (var i = 0; i < files.length; i++) {
      status.textContent = '上传中 (' + (i + 1) + '/' + files.length + ')：' + files[i].name;
      var ok = await uploadOne(files[i]);
      status.textContent = (ok ? '✓ ' : '✗ ') + files[i].name;
    }
    status.textContent = '完成，正在刷新列表…';
    location.reload();
  }

  function uploadOne(file) {
    return new Promise(function (resolve) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/upload');
      xhr.setRequestHeader('X-Requested-With', 'fetch');
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable) {
          fill.style.width = Math.round(e.loaded * 100 / e.total) + '%';
        }
      };
      xhr.onload = function () { resolve(xhr.status === 200); };
      xhr.onerror = function () { resolve(false); };
      var form = new FormData();
      form.append('file', file, file.name);
      xhr.send(form);
    });
  }

  document.getElementById('list').addEventListener('click', async function (e) {
    var btn = e.target.closest('button[data-act]');
    if (!btn) return;
    var name = btn.getAttribute('data-name');
    var act = btn.getAttribute('data-act');
    if (act === 'delete' && !confirm('删除 ' + name + ' ?')) return;
    var body = 'name=' + encodeURIComponent(name);
    var url = act === 'delete' ? '/delete' : '/rename';
    try {
      var res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body
      });
      var data = await res.json();
      if (data && data.ok === false) alert(data.error || '操作失败');
    } catch (err) {
      alert('请求失败：' + err);
    }
    location.reload();
  });
})();
</script>
</body>
</html>
        """.trimIndent()
    }

    fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
