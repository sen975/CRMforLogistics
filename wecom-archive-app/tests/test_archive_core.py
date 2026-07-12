import json
import os
import tempfile
import unittest

from archive_core import (
    ArchiveConfig,
    ArchiveStore,
    ArchiveService,
    FakeKeyResolver,
    build_config_status,
    extract_media_refs,
    load_config,
    save_config,
)


class FakeFinanceSdk:
    def __init__(self):
        self.media_calls = []
        self.decrypt_calls = []

    def get_chat_data(self, seq, limit, proxy="", passwd="", timeout=5):
        return {
            "errcode": 0,
            "errmsg": "ok",
            "chatdata": [
                {
                    "seq": int(seq) + 1,
                    "msgid": "msg-1",
                    "publickey_ver": 1,
                    "encrypt_random_key": "encrypted-key",
                    "encrypt_chat_msg": "encrypted-message",
                }
            ],
        }

    def decrypt_data(self, random_key, encrypted_chat_msg):
        self.decrypt_calls.append((random_key, encrypted_chat_msg))
        return json.dumps(
            {
                "msgid": "msg-1",
                "action": "send",
                "from": "alice",
                "tolist": ["bob"],
                "roomid": "",
                "msgtime": 1718274897000,
                "msgtype": "image",
                "text": {"content": ""},
                "image": {"sdkfileid": "image-sdk-id", "md5sum": "abc"},
            },
            ensure_ascii=False,
        )

    def get_media_data(self, sdkfileid, output_path, proxy="", passwd="", timeout=5):
        self.media_calls.append((sdkfileid, output_path, proxy, passwd, timeout))
        with open(output_path, "wb") as f:
            f.write(b"raw-media")
        return {"errcode": 0, "errmsg": "ok", "path": output_path, "size": 9}


class ArchiveCoreTests(unittest.TestCase):
    def test_config_status_masks_secret_and_reports_missing_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg = {
                "wxwork_archive": {
                    "corp_id": "ww1234567890abcdef",
                    "secret": "very-secret-value",
                    "private_key_path": os.path.join(tmp, "missing.pem"),
                    "sdk_lib_path": os.path.join(tmp, "missing.dll"),
                    "db_path": os.path.join(tmp, "data", "archive.db"),
                    "media_dir": os.path.join(tmp, "media"),
                }
            }
            path = os.path.join(tmp, "config.json")
            save_config(path, cfg)

            loaded = load_config(path)
            config = ArchiveConfig.from_config(loaded, base_dir=tmp)
            status = build_config_status(config)

            self.assertEqual(status["masked"]["corp_id"], "ww1234567890abcdef")
            self.assertEqual(status["masked"]["secret"], "ver***********lue")
            self.assertFalse(status["configured"])
            self.assertFalse(status["checks"]["private_key_path"]["ok"])
            self.assertFalse(status["checks"]["sdk_lib_path"]["ok"])

    def test_load_config_accepts_utf8_bom_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "config.json")
            payload = {
                "wxwork_archive": {
                    "corp_id": "ww-bom",
                    "secret": "secret",
                }
            }
            with open(path, "w", encoding="utf-8-sig") as f:
                json.dump(payload, f)

            loaded = load_config(path)

            self.assertEqual(loaded["wxwork_archive"]["corp_id"], "ww-bom")
    def test_sync_once_decrypts_and_stores_messages(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = ArchiveConfig(
                corp_id="ww-test",
                secret="secret",
                db_path=os.path.join(tmp, "archive.db"),
                media_dir=os.path.join(tmp, "media"),
            )
            store = ArchiveStore(config.db_path)
            sdk = FakeFinanceSdk()
            service = ArchiveService(
                config=config,
                sdk=sdk,
                key_resolver=FakeKeyResolver("random-key"),
                store=store,
            )

            result = service.sync_once(limit=200)

            self.assertEqual(result["stored"], 1)
            self.assertEqual(result["next_seq"], 1)
            self.assertEqual(sdk.decrypt_calls, [("random-key", "encrypted-message")])
            rows = store.search_messages(msgtype="image")
            self.assertEqual(rows[0]["msgid"], "msg-1")
            self.assertEqual(rows[0]["sender"], "alice")
            self.assertEqual(rows[0]["content"], "[image]")

    def test_extract_media_refs_for_image_voice_file_and_mixed(self):
        raw = {
            "msgid": "mixed-1",
            "msgtype": "mixed",
            "mixed": {
                "item": [
                    {"type": "text", "content": "hello"},
                    {"type": "image", "image": {"sdkfileid": "image-1"}},
                    {"type": "voice", "voice": {"sdkfileid": "voice-1"}},
                    {"type": "file", "file": {"sdkfileid": "file-1", "filename": "quote.pdf"}},
                ]
            },
        }

        refs = extract_media_refs(raw)

        self.assertEqual(
            [(item["media_type"], item["sdkfileid"], item["file_name"]) for item in refs],
            [
                ("image", "image-1", ""),
                ("voice", "voice-1", ""),
                ("file", "file-1", "quote.pdf"),
            ],
        )

    def test_download_media_keeps_output_inside_media_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = ArchiveConfig(
                corp_id="ww-test",
                secret="secret",
                db_path=os.path.join(tmp, "archive.db"),
                media_dir=os.path.join(tmp, "media"),
            )
            store = ArchiveStore(config.db_path)
            service = ArchiveService(
                config=config,
                sdk=FakeFinanceSdk(),
                key_resolver=FakeKeyResolver("random-key"),
                store=store,
            )

            media = service.download_media(
                msgid="msg/../1",
                sdkfileid="voice-sdk-id",
                media_type="voice",
                file_name="..\\..\\secret.amr",
            )

            media_root = os.path.abspath(config.media_dir)
            saved_path = os.path.abspath(media["path"])
            self.assertTrue(saved_path.startswith(media_root + os.sep))
            self.assertTrue(os.path.exists(saved_path))
            with open(saved_path, "rb") as f:
                self.assertEqual(f.read(), b"raw-media")
            self.assertEqual(store.list_media("msg/../1")[0]["media_type"], "voice")


if __name__ == "__main__":
    unittest.main()



