"""Official WeCom chat archive sync, decrypt, store and search helpers.

This app uses the official Enterprise WeChat/WeCom chat archive SDK flow:

1. Get encrypted envelopes with GetChatData.
2. Decrypt encrypt_random_key with the configured private key.
3. Decrypt encrypt_chat_msg with DecryptData.
4. Store normalized messages and raw JSON in SQLite.
5. Download image, voice and file payloads with GetMediaData when requested.

It deliberately does not read or decrypt local WeCom client cache databases.
"""

from __future__ import annotations

import base64
import ctypes
import json
import os
import re
import sqlite3
import time
from contextlib import closing
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Tuple


class ArchiveError(RuntimeError):
    """Raised when official archive operations cannot be completed."""


@dataclass
class ArchiveConfig:
    corp_id: str = ""
    secret: str = ""
    private_key_path: str = ""
    sdk_lib_path: str = ""
    db_path: str = os.path.join("data", "wxwork_archive.db")
    media_dir: str = os.path.join("data", "media")
    proxy: str = ""
    passwd: str = ""
    timeout: int = 5
    base_dir: str = ""

    @classmethod
    def from_config(cls, cfg: Dict[str, Any], base_dir: str = "") -> "ArchiveConfig":
        root = os.path.abspath(base_dir or os.getcwd())
        archive = dict(cfg.get("wxwork_archive", {}) or {})
        config = cls(
            corp_id=str(archive.get("corp_id", "") or ""),
            secret=str(archive.get("secret", "") or ""),
            private_key_path=str(archive.get("private_key_path", "") or ""),
            sdk_lib_path=str(archive.get("sdk_lib_path", "") or ""),
            db_path=str(archive.get("db_path", cls.db_path) or cls.db_path),
            media_dir=str(archive.get("media_dir", cls.media_dir) or cls.media_dir),
            proxy=str(archive.get("proxy", "") or ""),
            passwd=str(archive.get("passwd", "") or ""),
            timeout=int(archive.get("timeout", 5) or 5),
            base_dir=root,
        )
        config.private_key_path = _resolve_path(config.private_key_path, root)
        config.sdk_lib_path = _resolve_path(config.sdk_lib_path, root)
        config.db_path = _resolve_path(config.db_path, root)
        config.media_dir = _resolve_path(config.media_dir, root)
        return config

    def to_dict(self) -> Dict[str, Any]:
        return {
            "corp_id": self.corp_id,
            "secret": self.secret,
            "private_key_path": self.private_key_path,
            "sdk_lib_path": self.sdk_lib_path,
            "db_path": self.db_path,
            "media_dir": self.media_dir,
            "proxy": self.proxy,
            "passwd": self.passwd,
            "timeout": self.timeout,
        }


def _resolve_path(path: str, base_dir: str) -> str:
    if not path:
        return ""
    expanded = os.path.expandvars(os.path.expanduser(str(path)))
    if os.path.isabs(expanded):
        return os.path.abspath(expanded)
    return os.path.abspath(os.path.join(base_dir, expanded))


def default_config() -> Dict[str, Any]:
    return {
        "wxwork_archive": {
            "corp_id": "",
            "secret": "",
            "private_key_path": "private_key.pem",
            "sdk_lib_path": "WeWorkFinanceSdk.dll",
            "db_path": os.path.join("data", "wxwork_archive.db"),
            "media_dir": os.path.join("data", "media"),
            "proxy": "",
            "passwd": "",
            "timeout": 5,
        }
    }


def load_config(config_path: str) -> Dict[str, Any]:
    if not os.path.exists(config_path):
        return default_config()
    with open(config_path, "r", encoding="utf-8-sig") as f:
        data = json.load(f)
    if "wxwork_archive" not in data:
        data["wxwork_archive"] = {}
    merged = default_config()
    merged["wxwork_archive"].update(data.get("wxwork_archive", {}) or {})
    return merged


def save_config(config_path: str, cfg: Dict[str, Any]) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(config_path)) or ".", exist_ok=True)
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
        f.write("\n")


def update_config(config_path: str, patch: Dict[str, Any]) -> Dict[str, Any]:
    cfg = load_config(config_path)
    archive = dict(cfg.get("wxwork_archive", {}) or {})
    allowed = {
        "corp_id",
        "secret",
        "private_key_path",
        "sdk_lib_path",
        "db_path",
        "media_dir",
        "proxy",
        "passwd",
        "timeout",
    }
    for key in allowed:
        if key not in patch:
            continue
        value = patch[key]
        if key in {"secret", "passwd"} and value == "":
            continue
        if key == "timeout":
            value = max(1, int(value or 5))
        archive[key] = value
    cfg["wxwork_archive"] = archive
    save_config(config_path, cfg)
    return cfg


def _mask(value: str) -> str:
    value = str(value or "")
    if not value:
        return ""
    if len(value) <= 6:
        return "*" * len(value)
    return value[:3] + ("*" * max(3, len(value) - 6)) + value[-3:]


def build_config_status(config: ArchiveConfig) -> Dict[str, Any]:
    checks = {
        "corp_id": {"ok": bool(config.corp_id), "message": "configured" if config.corp_id else "missing"},
        "secret": {"ok": bool(config.secret), "message": "configured" if config.secret else "missing"},
        "private_key_path": _path_check(config.private_key_path),
        "sdk_lib_path": _path_check(config.sdk_lib_path),
        "db_path": _parent_check(config.db_path),
        "media_dir": _dir_or_parent_check(config.media_dir),
    }
    configured = all(item["ok"] for item in checks.values())
    return {
        "configured": configured,
        "masked": {
            "corp_id": config.corp_id,
            "secret": _mask(config.secret),
            "passwd": _mask(config.passwd),
        },
        "paths": {
            "private_key_path": config.private_key_path,
            "sdk_lib_path": config.sdk_lib_path,
            "db_path": config.db_path,
            "media_dir": config.media_dir,
        },
        "network": {
            "proxy": config.proxy,
            "timeout": config.timeout,
            "passwd_set": bool(config.passwd),
        },
        "checks": checks,
    }


def _path_check(path: str) -> Dict[str, Any]:
    if not path:
        return {"ok": False, "message": "missing"}
    return {"ok": os.path.exists(path), "message": "exists" if os.path.exists(path) else "not found"}


def _parent_check(path: str) -> Dict[str, Any]:
    if not path:
        return {"ok": False, "message": "missing"}
    parent = os.path.dirname(os.path.abspath(path)) or "."
    return {"ok": os.path.isdir(parent), "message": "parent exists" if os.path.isdir(parent) else "parent missing"}


def _dir_or_parent_check(path: str) -> Dict[str, Any]:
    if not path:
        return {"ok": False, "message": "missing"}
    if os.path.isdir(path):
        return {"ok": True, "message": "exists"}
    return _parent_check(path)


class ArchiveKeyResolver:
    def __init__(self, private_key_path: str):
        self.private_key_path = private_key_path
        self._key = None

    def _load_key(self):
        if self._key is None:
            if not self.private_key_path:
                raise ArchiveError("wxwork_archive.private_key_path is required")
            try:
                from Crypto.Cipher import PKCS1_v1_5
                from Crypto.PublicKey import RSA
            except ImportError as exc:
                raise ArchiveError("pycryptodome is required to decrypt encrypt_random_key") from exc
            with open(self.private_key_path, "rb") as f:
                self._key = RSA.import_key(f.read())
            self._cipher_factory = PKCS1_v1_5
        return self._key

    def decrypt_random_key(self, publickey_ver: int, encrypted_random_key: str) -> str:
        del publickey_ver
        key = self._load_key()
        encrypted = base64.b64decode(encrypted_random_key)
        cipher = self._cipher_factory.new(key)
        sentinel = object()
        plain = cipher.decrypt(encrypted, sentinel)
        if plain is sentinel:
            raise ArchiveError("failed to decrypt encrypt_random_key with private key")
        return plain.decode("utf-8")


class FakeKeyResolver:
    """Small test helper kept in core so tests can avoid importing private SDK code."""

    def __init__(self, random_key: str):
        self.random_key = random_key
        self.last_args: Optional[Tuple[int, str]] = None

    def decrypt_random_key(self, publickey_ver: int, encrypted_random_key: str) -> str:
        self.last_args = (publickey_ver, encrypted_random_key)
        return self.random_key


class OfficialFinanceSdk:
    """ctypes wrapper for the official WeCom finance SDK."""

    @staticmethod
    def _configure_ctypes(lib) -> None:
        void_p = ctypes.c_void_p
        char_p = ctypes.c_char_p
        int_t = ctypes.c_int
        ulong_t = ctypes.c_ulonglong

        lib.NewSdk.restype = void_p
        lib.DestroySdk.argtypes = [void_p]
        lib.Init.argtypes = [void_p, char_p, char_p]
        lib.Init.restype = int_t

        lib.NewSlice.restype = void_p
        lib.FreeSlice.argtypes = [void_p]
        lib.GetContentFromSlice.argtypes = [void_p]
        lib.GetContentFromSlice.restype = void_p
        lib.GetSliceLen.argtypes = [void_p]
        lib.GetSliceLen.restype = int_t

        lib.GetChatData.argtypes = [void_p, ulong_t, ulong_t, char_p, char_p, int_t, void_p]
        lib.GetChatData.restype = int_t
        lib.DecryptData.argtypes = [char_p, char_p, void_p]
        lib.DecryptData.restype = int_t

        lib.NewMediaData.restype = void_p
        lib.FreeMediaData.argtypes = [void_p]
        lib.GetMediaData.argtypes = [void_p, char_p, char_p, char_p, char_p, int_t, void_p]
        lib.GetMediaData.restype = int_t
        lib.GetData.argtypes = [void_p]
        lib.GetData.restype = void_p
        lib.GetDataLen.argtypes = [void_p]
        lib.GetDataLen.restype = int_t
        lib.GetOutIndexBuf.argtypes = [void_p]
        lib.GetOutIndexBuf.restype = void_p
        lib.IsMediaDataFinish.argtypes = [void_p]
        lib.IsMediaDataFinish.restype = int_t

    def __init__(self, config: ArchiveConfig):
        if not config.sdk_lib_path:
            raise ArchiveError("wxwork_archive.sdk_lib_path is required")
        self.config = config
        self.lib = ctypes.cdll.LoadLibrary(config.sdk_lib_path)
        self._configure_ctypes(self.lib)
        self.sdk = self.lib.NewSdk()
        init_ret = self.lib.Init(
            self.sdk,
            config.corp_id.encode("utf-8"),
            config.secret.encode("utf-8"),
        )
        if init_ret != 0:
            raise ArchiveError(f"WeWorkFinanceSdk Init failed: {init_ret}")

    def _new_slice(self):
        ptr = self.lib.NewSlice()
        if not ptr:
            raise ArchiveError("WeWorkFinanceSdk NewSlice failed")
        return ptr

    def _free_slice(self, ptr) -> None:
        if ptr:
            self.lib.FreeSlice(ptr)

    def _slice_to_bytes(self, ptr) -> bytes:
        data = self.lib.GetContentFromSlice(ptr)
        size = self.lib.GetSliceLen(ptr)
        if not data or size <= 0:
            return b""
        return ctypes.string_at(data, size)

    def get_chat_data(self, seq, limit, proxy="", passwd="", timeout=5) -> Dict[str, Any]:
        out = self._new_slice()
        try:
            ret = self.lib.GetChatData(
                self.sdk,
                int(seq),
                int(limit),
                (proxy or "").encode("utf-8"),
                (passwd or "").encode("utf-8"),
                int(timeout),
                out,
            )
            if ret != 0:
                raise ArchiveError(f"GetChatData failed: {ret}")
            payload = self._slice_to_bytes(out).decode("utf-8")
            return json.loads(payload)
        finally:
            self._free_slice(out)

    def decrypt_data(self, random_key, encrypted_chat_msg) -> str:
        out = self._new_slice()
        try:
            ret = self.lib.DecryptData(
                random_key.encode("utf-8"),
                encrypted_chat_msg.encode("utf-8"),
                out,
            )
            if ret != 0:
                raise ArchiveError(f"DecryptData failed: {ret}")
            return self._slice_to_bytes(out).decode("utf-8")
        finally:
            self._free_slice(out)

    def get_media_data(self, sdkfileid, output_path, proxy="", passwd="", timeout=5) -> Dict[str, Any]:
        indexbuf = ""
        total = 0
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
        with open(output_path, "wb") as f:
            while True:
                media = self.lib.NewMediaData()
                try:
                    ret = self.lib.GetMediaData(
                        self.sdk,
                        indexbuf.encode("utf-8"),
                        sdkfileid.encode("utf-8"),
                        (proxy or "").encode("utf-8"),
                        (passwd or "").encode("utf-8"),
                        int(timeout),
                        media,
                    )
                    if ret != 0:
                        raise ArchiveError(f"GetMediaData failed: {ret}")
                    data = self.lib.GetData(media)
                    data_len = self.lib.GetDataLen(media)
                    if data and data_len > 0:
                        chunk = ctypes.string_at(data, data_len)
                        f.write(chunk)
                        total += len(chunk)
                    outindex = self.lib.GetOutIndexBuf(media)
                    indexbuf = ctypes.string_at(outindex).decode("utf-8") if outindex else ""
                    if self.lib.IsMediaDataFinish(media):
                        break
                finally:
                    self.lib.FreeMediaData(media)
        return {"errcode": 0, "errmsg": "ok", "path": output_path, "size": total}

    def close(self) -> None:
        if getattr(self, "sdk", None):
            self.lib.DestroySdk(self.sdk)
            self.sdk = None


class ArchiveStore:
    def __init__(self, db_path: str):
        self.db_path = db_path
        self._ensure_schema()

    def _connect(self):
        os.makedirs(os.path.dirname(os.path.abspath(self.db_path)) or ".", exist_ok=True)
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _ensure_schema(self) -> None:
        with closing(self._connect()) as conn:
            conn.execute(
                "CREATE TABLE IF NOT EXISTS archive_state ("
                "name TEXT PRIMARY KEY, value TEXT NOT NULL)"
            )
            conn.execute(
                "CREATE TABLE IF NOT EXISTS archive_messages ("
                "seq INTEGER PRIMARY KEY, "
                "msgid TEXT UNIQUE, "
                "publickey_ver INTEGER, "
                "action TEXT, "
                "sender TEXT, "
                "tolist TEXT, "
                "roomid TEXT, "
                "msgtime INTEGER, "
                "msgtype TEXT, "
                "content TEXT, "
                "raw_json TEXT NOT NULL, "
                "created_at INTEGER DEFAULT (strftime('%s','now')))"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_archive_messages_sender "
                "ON archive_messages(sender, msgtime)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_archive_messages_type "
                "ON archive_messages(msgtype, msgtime)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_archive_messages_room "
                "ON archive_messages(roomid, msgtime)"
            )
            conn.execute(
                "CREATE TABLE IF NOT EXISTS archive_media ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                "msgid TEXT NOT NULL, "
                "sdkfileid TEXT NOT NULL, "
                "media_type TEXT, "
                "file_name TEXT, "
                "path TEXT, "
                "size INTEGER, "
                "created_at INTEGER DEFAULT (strftime('%s','now')), "
                "UNIQUE(msgid, sdkfileid))"
            )
            conn.commit()

    def get_state(self, name: str, default: Optional[str] = None) -> Optional[str]:
        with closing(self._connect()) as conn:
            row = conn.execute("SELECT value FROM archive_state WHERE name=?", (name,)).fetchone()
            return row["value"] if row else default

    def set_state(self, name: str, value: Any) -> None:
        with closing(self._connect()) as conn:
            conn.execute(
                "INSERT INTO archive_state(name, value) VALUES(?, ?) "
                "ON CONFLICT(name) DO UPDATE SET value=excluded.value",
                (name, str(value)),
            )
            conn.commit()

    def get_last_seq(self) -> int:
        return int(self.get_state("last_seq", "0") or 0)

    def set_last_seq(self, seq: int) -> None:
        self.set_state("last_seq", int(seq))

    def save_messages(self, messages: Iterable[Dict[str, Any]]) -> int:
        count = 0
        with closing(self._connect()) as conn:
            for msg in messages:
                conn.execute(
                    "INSERT INTO archive_messages "
                    "(seq, msgid, publickey_ver, action, sender, tolist, roomid, "
                    "msgtime, msgtype, content, raw_json) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(seq) DO UPDATE SET "
                    "msgid=excluded.msgid, publickey_ver=excluded.publickey_ver, "
                    "action=excluded.action, sender=excluded.sender, tolist=excluded.tolist, "
                    "roomid=excluded.roomid, msgtime=excluded.msgtime, "
                    "msgtype=excluded.msgtype, content=excluded.content, "
                    "raw_json=excluded.raw_json",
                    (
                        msg.get("seq"),
                        msg.get("msgid"),
                        msg.get("publickey_ver"),
                        msg.get("action", ""),
                        msg.get("sender", ""),
                        json.dumps(msg.get("tolist", []), ensure_ascii=False),
                        msg.get("roomid", ""),
                        msg.get("msgtime"),
                        msg.get("msgtype", ""),
                        msg.get("content", ""),
                        json.dumps(msg.get("raw_json", msg), ensure_ascii=False),
                    ),
                )
                count += 1
            conn.commit()
        return count

    def search_messages(
        self,
        keyword: str = "",
        sender: str = "",
        roomid: str = "",
        msgtype: str = "",
        start_time: Optional[int] = None,
        end_time: Optional[int] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[Dict[str, Any]]:
        where = []
        args: List[Any] = []
        if keyword:
            where.append("(content LIKE ? OR raw_json LIKE ?)")
            like = f"%{keyword}%"
            args.extend([like, like])
        if sender:
            where.append("sender = ?")
            args.append(sender)
        if roomid:
            where.append("roomid = ?")
            args.append(roomid)
        if msgtype:
            where.append("msgtype = ?")
            args.append(msgtype)
        if start_time is not None:
            where.append("msgtime >= ?")
            args.append(int(start_time))
        if end_time is not None:
            where.append("msgtime <= ?")
            args.append(int(end_time))
        sql = (
            "SELECT seq, msgid, publickey_ver, action, sender, tolist, roomid, "
            "msgtime, msgtype, content, raw_json FROM archive_messages"
        )
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += " ORDER BY msgtime DESC, seq DESC LIMIT ? OFFSET ?"
        args.extend([min(max(int(limit), 1), 1000), max(int(offset), 0)])
        with closing(self._connect()) as conn:
            rows = conn.execute(sql, args).fetchall()
            return [_decode_message_row(row) for row in rows]

    def save_media(self, media: Dict[str, Any]) -> None:
        with closing(self._connect()) as conn:
            conn.execute(
                "INSERT INTO archive_media "
                "(msgid, sdkfileid, media_type, file_name, path, size) "
                "VALUES (?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(msgid, sdkfileid) DO UPDATE SET "
                "media_type=excluded.media_type, file_name=excluded.file_name, "
                "path=excluded.path, size=excluded.size",
                (
                    media.get("msgid"),
                    media.get("sdkfileid"),
                    media.get("media_type", ""),
                    media.get("file_name", ""),
                    media.get("path", ""),
                    int(media.get("size", 0) or 0),
                ),
            )
            conn.commit()

    def list_media(self, msgid: str = "") -> List[Dict[str, Any]]:
        sql = "SELECT msgid, sdkfileid, media_type, file_name, path, size FROM archive_media"
        args: List[Any] = []
        if msgid:
            sql += " WHERE msgid=?"
            args.append(msgid)
        sql += " ORDER BY created_at DESC, id DESC"
        with closing(self._connect()) as conn:
            rows = conn.execute(sql, args).fetchall()
            return [dict(row) for row in rows]


def _decode_message_row(row) -> Dict[str, Any]:
    item = dict(row)
    try:
        item["tolist"] = json.loads(item.get("tolist") or "[]")
    except json.JSONDecodeError:
        item["tolist"] = []
    try:
        item["raw_json"] = json.loads(item.get("raw_json") or "{}")
    except json.JSONDecodeError:
        item["raw_json"] = {}
    item["media_refs"] = extract_media_refs(item["raw_json"])
    return item


def _message_content(plain: Dict[str, Any]) -> str:
    msgtype = plain.get("msgtype") or ""
    if msgtype == "text":
        return str((plain.get("text") or {}).get("content") or "")
    if msgtype == "image":
        return "[image]"
    if msgtype == "voice":
        return "[voice]"
    if msgtype == "file":
        file_obj = plain.get("file") or {}
        return str(file_obj.get("filename") or "[file]")
    if msgtype == "emotion":
        return "[emotion]"
    if msgtype == "link":
        link = plain.get("link") or {}
        return str(link.get("title") or link.get("description") or "[link]")
    if msgtype == "mixed":
        return json.dumps(plain.get("mixed") or plain, ensure_ascii=False)
    return json.dumps(plain, ensure_ascii=False)


def _normalize_message(envelope: Dict[str, Any], plain: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "seq": int(envelope.get("seq", 0) or 0),
        "msgid": plain.get("msgid") or envelope.get("msgid"),
        "publickey_ver": int(envelope.get("publickey_ver", 0) or 0),
        "action": plain.get("action", ""),
        "sender": plain.get("from", ""),
        "tolist": plain.get("tolist", []) or [],
        "roomid": plain.get("roomid", ""),
        "msgtime": int(plain.get("msgtime", 0) or 0),
        "msgtype": plain.get("msgtype", ""),
        "content": _message_content(plain),
        "raw_json": plain,
    }


def extract_media_refs(raw: Dict[str, Any]) -> List[Dict[str, str]]:
    refs: List[Dict[str, str]] = []

    def add(media_type: str, obj: Dict[str, Any]) -> None:
        sdkfileid = str((obj or {}).get("sdkfileid") or "")
        if not sdkfileid:
            return
        refs.append(
            {
                "media_type": media_type,
                "sdkfileid": sdkfileid,
                "file_name": str((obj or {}).get("filename") or (obj or {}).get("fileext") or ""),
            }
        )

    msgtype = str(raw.get("msgtype") or "")
    if msgtype in {"image", "voice", "video", "file", "emotion"}:
        add(msgtype, raw.get(msgtype) or {})

    mixed_items = ((raw.get("mixed") or {}).get("item") or [])
    for item in mixed_items:
        item_type = str(item.get("type") or item.get("msgtype") or "")
        if item_type in {"image", "voice", "video", "file", "emotion"}:
            add(item_type, item.get(item_type) or item)
    return refs


def _safe_filename(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\r\n\t]+', "_", str(name or ""))
    name = re.sub(r"\s+", " ", name).strip(" .")
    return name[:160] or "media.bin"


def _default_media_name(msgid: str, media_type: str) -> str:
    suffix = {
        "image": ".jpg",
        "voice": ".amr",
        "video": ".mp4",
        "file": ".bin",
        "emotion": ".bin",
    }.get(media_type, ".bin")
    return f"{_safe_filename(msgid)}_{media_type}_{int(time.time())}{suffix}"


class ArchiveService:
    def __init__(
        self,
        config: ArchiveConfig,
        sdk=None,
        key_resolver=None,
        store: Optional[ArchiveStore] = None,
    ):
        self.config = config
        self.sdk = sdk or OfficialFinanceSdk(config)
        self.key_resolver = key_resolver or ArchiveKeyResolver(config.private_key_path)
        self.store = store or ArchiveStore(config.db_path)

    def sync_once(self, limit: int = 1000, seq: Optional[int] = None) -> Dict[str, Any]:
        start_seq = self.store.get_last_seq() if seq is None else int(seq)
        response = self.sdk.get_chat_data(
            start_seq,
            min(max(int(limit), 1), 1000),
            self.config.proxy,
            self.config.passwd,
            self.config.timeout,
        )
        errcode = int(response.get("errcode", 0) or 0)
        if errcode != 0:
            raise ArchiveError(f"GetChatData error {errcode}: {response.get('errmsg', '')}")

        messages = []
        max_seq = start_seq
        for envelope in response.get("chatdata", []) or []:
            publickey_ver = int(envelope.get("publickey_ver", 0) or 0)
            random_key = self.key_resolver.decrypt_random_key(
                publickey_ver,
                str(envelope.get("encrypt_random_key", "") or ""),
            )
            plain_text = self.sdk.decrypt_data(random_key, str(envelope.get("encrypt_chat_msg", "") or ""))
            plain = json.loads(plain_text)
            message = _normalize_message(envelope, plain)
            messages.append(message)
            max_seq = max(max_seq, int(envelope.get("seq", max_seq) or max_seq))

        stored = self.store.save_messages(messages)
        if max_seq > start_seq:
            self.store.set_last_seq(max_seq)
        return {
            "errcode": 0,
            "errmsg": "ok",
            "fetched": len(messages),
            "stored": stored,
            "start_seq": start_seq,
            "next_seq": max_seq,
        }

    def download_media(
        self,
        msgid: str,
        sdkfileid: str,
        media_type: str = "file",
        file_name: str = "",
    ) -> Dict[str, Any]:
        if not msgid:
            raise ArchiveError("msgid is required")
        if not sdkfileid:
            raise ArchiveError("sdkfileid is required")
        file_name = _safe_filename(file_name or _default_media_name(msgid, media_type))
        output_dir = os.path.join(self.config.media_dir, _safe_filename(str(msgid)))
        os.makedirs(output_dir, exist_ok=True)
        output_path = os.path.abspath(os.path.join(output_dir, file_name))
        media_root = os.path.abspath(self.config.media_dir)
        if not output_path.startswith(media_root + os.sep):
            raise ArchiveError("unsafe media output path")
        result = self.sdk.get_media_data(
            sdkfileid,
            output_path,
            self.config.proxy,
            self.config.passwd,
            self.config.timeout,
        )
        media = {
            "msgid": msgid,
            "sdkfileid": sdkfileid,
            "media_type": media_type,
            "file_name": file_name,
            "path": result.get("path", output_path),
            "size": int(result.get("size", 0) or 0),
        }
        self.store.save_media(media)
        return media

    def search_messages(self, **kwargs):
        return self.store.search_messages(**kwargs)


def config_from_file(config_path: str) -> ArchiveConfig:
    base_dir = os.path.dirname(os.path.abspath(config_path))
    return ArchiveConfig.from_config(load_config(config_path), base_dir=base_dir)


def build_archive_service(config_path: str) -> ArchiveService:
    config = config_from_file(config_path)
    return ArchiveService(config)


def query_int(value: Any, default=None):
    if value in (None, ""):
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default

