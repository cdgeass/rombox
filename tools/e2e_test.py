#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RomBox 端到端测试：直接打设备上的 HTTP 服务"""
import hashlib
import os
import http.client
import io
import json
import subprocess
import sys
import zipfile

HOST = os.environ.get("ROMBOX_HOST", "192.168.31.69")
PORT = 8080
BASE = "/storage/emulated/0/PocketGames/roms/gba"
LOCAL_ROM = os.environ.get("ROMBOX_ROM", "t.gba")

ok_count = 0
fail_count = 0


def check(name, cond, extra=""):
    global ok_count, fail_count
    if cond:
        ok_count += 1
        print(f"  [PASS] {name} {extra}")
    else:
        fail_count += 1
        print(f"  [FAIL] {name} {extra}")


def request(method, path, body=None, headers=None, timeout=120):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    conn.request(method, path, body=body, headers=headers or {})
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    return resp.status, data


def multipart(fields):
    """fields: list of (filename_bytes_or_None, field_name, content_bytes)"""
    boundary = "----romboxtestboundary"
    out = io.BytesIO()
    for filename, name, content in fields:
        out.write(f"--{boundary}\r\n".encode())
        if filename is None:
            out.write(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        else:
            head = b'Content-Disposition: form-data; name="' + name.encode() + b'"; filename="'
            head += filename + b'"\r\nContent-Type: application/octet-stream\r\n\r\n'
            out.write(head)
        out.write(content)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), f"multipart/form-data; boundary={boundary}"


def upload(fields):
    body, ctype = multipart(fields)
    status, data = request("POST", "/upload", body, {
        "Content-Type": ctype,
        "Content-Length": str(len(body)),
        "X-Requested-With": "fetch",
    })
    return status, data


def api_roms():
    status, data = request("GET", "/api/roms")
    return json.loads(data.decode("utf-8"))


def form(path, **fields):
    from urllib.parse import urlencode
    body = urlencode(fields).encode()
    return request("POST", path, body, {
        "Content-Type": "application/x-www-form-urlencoded",
        "Content-Length": str(len(body)),
        "X-Requested-With": "fetch",
    })


def main():
    rom = open(LOCAL_ROM, "rb").read()
    print(f"本地测试 ROM: {LOCAL_ROM} {len(rom)} bytes")

    print("\n1) 服务状态")
    status, data = request("GET", "/api/info")
    info = json.loads(data.decode())
    check("GET /api/info 200", status == 200)
    check("服务在运行", info.get("running") is True, info.get("dir", ""))

    before = api_roms()["roms"]
    print(f"   现有 ROM: {len(before)} 个")

    print("\n2) 上传 UTF-8 中文文件名")
    status, data = upload([('测试 上传.gba'.encode("utf-8"), "file", rom)])
    result = json.loads(data.decode())
    check("上传返回 ok", result.get("ok") is True, str(result))
    check("文件名正确", "测试 上传.gba" in result.get("saved", []), str(result.get("saved")))

    print("\n3) 上传 GBK 编码文件名（模拟 Windows curl）")
    status, data = upload([('国标 名字.gba'.encode("gbk"), "file", rom)])
    result = json.loads(data.decode())
    check("上传返回 ok", result.get("ok") is True, str(result))
    check("GBK 文件名被正确解码", "国标 名字.gba" in result.get("saved", []), str(result.get("saved")))

    print("\n4) 上传 zip（自动解出包内 ROM）")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("inside/包内一号.gba", rom)
        zf.writestr("inside/包内二号.gba", rom[: len(rom) - 1])
        zf.writestr("inside/readme.txt", b"hello")
    status, data = upload([("打包 roms.zip".encode("utf-8"), "file", buf.getvalue())])
    result = json.loads(data.decode())
    saved = result.get("saved", [])
    check("解压出 2 个 ROM", len(saved) == 2, str(saved))
    check("readme 被忽略", all(n.endswith(".gba") for n in saved), str(saved))

    print("\n5) 列表接口")
    roms = api_roms()["roms"]
    names = [r["name"] for r in roms]
    check("数量增加 4", len(roms) == len(before) + 4, f'{len(before)} -> {len(roms)}')
    check("列表含新文件", "测试 上传.gba" in names)

    print("\n6) 下载校验")
    from urllib.parse import quote
    status, data = request("GET", "/file?name=" + quote("测试 上传.gba"))
    check("下载 200", status == 200)
    check("内容一致", hashlib.sha256(data).hexdigest() == hashlib.sha256(rom).hexdigest())

    print("\n7) 规范重命名")
    status, data = form("/rename", name="测试 上传.gba")
    result = json.loads(data.decode())
    check("重命名 ok", result.get("ok") is True, str(result))
    check("新名字带区域", "Japan" in result.get("name", ""), str(result))

    print("\n8) 拒绝非法扩展名 / 路径穿越")
    status, data = upload([("malware.exe".encode(), "file", b"MZ")])
    result = json.loads(data.decode())
    check("exe 被拒绝", result.get("saved") == [] and result.get("failed"), str(result))
    status, data = form("/delete", name="../../etc/hosts")
    check("路径穿越被拒绝", status == 404, str(status))

    print("\n9) 删除上传的文件")
    for name in names:
        if name not in [r["name"] for r in before]:
            form("/delete", name=name)
    if "测试 上传 (Japan).gba" not in [r["name"] for r in before]:
        form("/delete", name="测试 上传 (Japan).gba")
    final = api_roms()["roms"]
    check("回到原始数量", len(final) == len(before), f'{len(final)} vs {len(before)}')

    print(f"\n结果: {ok_count} 通过, {fail_count} 失败")
    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
