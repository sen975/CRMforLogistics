import json
import os
import tempfile
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from app import create_server
from archive_core import ArchiveConfig, ArchiveStore, save_config


def post_json(url, payload):
    req = Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(req, timeout=5) as res:
        return json.loads(res.read().decode("utf-8"))


def get_json(url):
    with urlopen(url, timeout=5) as res:
        return json.loads(res.read().decode("utf-8"))


class AppApiTests(unittest.TestCase):
    def test_api_messages_returns_stored_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            config_path = os.path.join(tmp, "config.json")
            db_path = os.path.join(tmp, "data", "archive.db")
            save_config(
                config_path,
                {
                    "wxwork_archive": {
                        "corp_id": "ww-test",
                        "secret": "secret",
                        "db_path": db_path,
                        "media_dir": os.path.join(tmp, "data", "media"),
                    }
                },
            )
            store = ArchiveStore(db_path)
            store.save_messages(
                [
                    {
                        "seq": 1,
                        "msgid": "msg-1",
                        "publickey_ver": 1,
                        "action": "send",
                        "sender": "alice",
                        "tolist": ["bob"],
                        "roomid": "",
                        "msgtime": 1718274897000,
                        "msgtype": "text",
                        "content": "hello from api",
                        "raw_json": {"msgid": "msg-1"},
                    }
                ]
            )

            server = create_server(("127.0.0.1", 0), config_path=config_path)
            try:
                port = server.server_address[1]
                server.start_background()

                payload = get_json(f"http://127.0.0.1:{port}/api/messages?keyword=api")

                self.assertEqual(payload["ok"], True)
                self.assertEqual(payload["items"][0]["msgid"], "msg-1")
            finally:
                server.stop_background()

    def test_media_endpoint_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            config_path = os.path.join(tmp, "config.json")
            cfg = ArchiveConfig(
                corp_id="ww-test",
                secret="secret",
                db_path=os.path.join(tmp, "data", "archive.db"),
                media_dir=os.path.join(tmp, "data", "media"),
            )
            save_config(config_path, {"wxwork_archive": cfg.to_dict()})
            server = create_server(("127.0.0.1", 0), config_path=config_path)
            try:
                port = server.server_address[1]
                server.start_background()

                with self.assertRaises(HTTPError) as ctx:
                    urlopen(f"http://127.0.0.1:{port}/media/../../config.json", timeout=5)

                self.assertEqual(ctx.exception.code, 404)
            finally:
                server.stop_background()

    def test_api_config_status_is_json_and_masks_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            config_path = os.path.join(tmp, "config.json")
            save_config(
                config_path,
                {
                    "wxwork_archive": {
                        "corp_id": "ww-test",
                        "secret": "secret-value",
                        "db_path": os.path.join(tmp, "data", "archive.db"),
                        "media_dir": os.path.join(tmp, "data", "media"),
                    }
                },
            )
            server = create_server(("127.0.0.1", 0), config_path=config_path)
            try:
                port = server.server_address[1]
                server.start_background()

                payload = get_json(f"http://127.0.0.1:{port}/api/config/status")

                self.assertTrue(payload["ok"])
                self.assertEqual(payload["status"]["masked"]["secret"], "sec******lue")
            finally:
                server.stop_background()


if __name__ == "__main__":
    unittest.main()
