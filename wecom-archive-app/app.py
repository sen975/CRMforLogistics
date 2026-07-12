"""Minimal interactive app for official WeCom chat archive operations."""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import posixpath
import threading
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict
from urllib.parse import parse_qs, unquote, urlparse

from archive_core import (
    ArchiveError,
    ArchiveStore,
    build_archive_service,
    build_config_status,
    config_from_file,
    load_config,
    query_int,
    update_config,
)


APP_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONFIG_PATH = os.path.join(APP_DIR, "config.json")
STATIC_DIR = os.path.join(APP_DIR, "static")


class ArchiveHttpServer(ThreadingHTTPServer):
    def __init__(self, server_address, RequestHandlerClass, config_path: str):
        super().__init__(server_address, RequestHandlerClass)
        self.config_path = os.path.abspath(config_path)
        self._thread = None

    def start_background(self):
        self._thread = threading.Thread(target=self.serve_forever, daemon=True)
        self._thread.start()

    def stop_background(self):
        self.shutdown()
        self.server_close()
        if self._thread:
            self._thread.join(timeout=3)


class ArchiveRequestHandler(SimpleHTTPRequestHandler):
    server_version = "WeComArchiveApp/0.1"

    def log_message(self, fmt, *args):
        print("%s - - [%s] %s" % (self.client_address[0], self.log_date_time_string(), fmt % args))

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/config/status":
            return self._api_config_status()
        if parsed.path == "/api/messages":
            return self._api_messages(parsed.query)
        if parsed.path == "/api/media":
            return self._api_media(parsed.query)
        if parsed.path.startswith("/media/"):
            return self._serve_media(parsed.path)
        return self._serve_static(parsed.path)

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/config":
            return self._api_save_config()
        if parsed.path == "/api/sync":
            return self._api_sync()
        if parsed.path == "/api/media/download":
            return self._api_download_media()
        return self._json({"ok": False, "error": "not found"}, HTTPStatus.NOT_FOUND)

    def _api_config_status(self):
        config = config_from_file(self.server.config_path)
        return self._json({"ok": True, "status": build_config_status(config)})

    def _api_save_config(self):
        body = self._read_json()
        cfg = update_config(self.server.config_path, body)
        config = config_from_file(self.server.config_path)
        return self._json({"ok": True, "config": _public_config(cfg), "status": build_config_status(config)})

    def _api_sync(self):
        body = self._read_json()
        try:
            service = build_archive_service(self.server.config_path)
            result = service.sync_once(limit=query_int(body.get("limit"), 1000), seq=query_int(body.get("seq")))
            return self._json({"ok": True, "result": result})
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def _api_messages(self, query: str):
        params = parse_qs(query)
        try:
            config = config_from_file(self.server.config_path)
            store = ArchiveStore(config.db_path)
            rows = store.search_messages(
                keyword=_param(params, "keyword"),
                sender=_param(params, "sender"),
                roomid=_param(params, "roomid"),
                msgtype=_param(params, "msgtype"),
                start_time=query_int(_param(params, "start_time"), None),
                end_time=query_int(_param(params, "end_time"), None),
                limit=query_int(_param(params, "limit"), 100) or 100,
                offset=query_int(_param(params, "offset"), 0) or 0,
            )
            return self._json({"ok": True, "items": rows, "count": len(rows), "last_seq": store.get_last_seq()})
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def _api_media(self, query: str):
        params = parse_qs(query)
        try:
            config = config_from_file(self.server.config_path)
            rows = ArchiveStore(config.db_path).list_media(msgid=_param(params, "msgid"))
            return self._json({"ok": True, "items": [_with_media_url(config.media_dir, row) for row in rows]})
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def _api_download_media(self):
        body = self._read_json()
        try:
            service = build_archive_service(self.server.config_path)
            media = service.download_media(
                msgid=str(body.get("msgid") or ""),
                sdkfileid=str(body.get("sdkfileid") or ""),
                media_type=str(body.get("media_type") or body.get("type") or "file"),
                file_name=str(body.get("file_name") or body.get("name") or ""),
            )
            media = _with_media_url(service.config.media_dir, media)
            return self._json({"ok": True, "media": media})
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def _serve_static(self, path: str):
        if path in {"", "/"}:
            path = "/index.html"
        if path == "/favicon.ico":
            return self._empty(HTTPStatus.NO_CONTENT)
        rel = unquote(path.lstrip("/"))
        target = os.path.abspath(os.path.join(STATIC_DIR, rel))
        static_root = os.path.abspath(STATIC_DIR)
        if not target.startswith(static_root + os.sep) or not os.path.isfile(target):
            return self._json({"ok": False, "error": "not found"}, HTTPStatus.NOT_FOUND)
        return self._send_file(target)

    def _serve_media(self, path: str):
        try:
            config = config_from_file(self.server.config_path)
            media_root = os.path.abspath(config.media_dir)
            encoded = path[len("/media/") :]
            rel = posixpath.normpath(unquote(encoded)).replace("/", os.sep)
            if rel.startswith("..") or os.path.isabs(rel):
                raise ArchiveError("unsafe media path")
            target = os.path.abspath(os.path.join(media_root, rel))
            if not target.startswith(media_root + os.sep) or not os.path.isfile(target):
                raise FileNotFoundError(target)
            return self._send_file(target)
        except Exception:
            return self._json({"ok": False, "error": "not found"}, HTTPStatus.NOT_FOUND)

    def _send_file(self, path: str):
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        with open(path, "rb") as f:
            data = f.read()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self) -> Dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0:
            return {}
        data = self.rfile.read(length).decode("utf-8")
        return json.loads(data) if data else {}

    def _json(self, payload: Dict[str, Any], status=HTTPStatus.OK):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _empty(self, status=HTTPStatus.NO_CONTENT):
        self.send_response(status)
        self.end_headers()


def _param(params: Dict[str, Any], name: str, default: str = "") -> str:
    value = params.get(name) or []
    return str(value[0]).strip() if value else default


def _public_config(cfg: Dict[str, Any]) -> Dict[str, Any]:
    archive = dict(cfg.get("wxwork_archive", {}) or {})
    if archive.get("secret"):
        archive["secret"] = "***"
    if archive.get("passwd"):
        archive["passwd"] = "***"
    return {"wxwork_archive": archive}


def _with_media_url(media_dir: str, row: Dict[str, Any]) -> Dict[str, Any]:
    item = dict(row)
    path = str(item.get("path") or "")
    media_root = os.path.abspath(media_dir)
    if path:
        abs_path = os.path.abspath(path)
        if abs_path.startswith(media_root + os.sep):
            rel = os.path.relpath(abs_path, media_root).replace(os.sep, "/")
            item["url"] = "/media/" + rel
    return item


def create_server(address=("127.0.0.1", 8067), config_path: str = DEFAULT_CONFIG_PATH):
    _ensure_runtime_files(config_path)
    return ArchiveHttpServer(address, ArchiveRequestHandler, config_path=config_path)


def _ensure_runtime_files(config_path: str) -> None:
    if not os.path.exists(config_path):
        from archive_core import default_config, save_config

        save_config(config_path, default_config())
    os.makedirs(STATIC_DIR, exist_ok=True)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="WeCom official chat archive app")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8067)
    parser.add_argument("--config", default=DEFAULT_CONFIG_PATH)
    args = parser.parse_args(argv)

    server = create_server((args.host, args.port), config_path=args.config)
    host, port = server.server_address
    print(f"WeCom archive app running: http://{host}:{port}")
    print(f"Config: {os.path.abspath(args.config)}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping server")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
