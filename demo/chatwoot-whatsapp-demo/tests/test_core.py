import os
import tempfile
import unittest

from chatwoot_demo.config import AppConfig
from chatwoot_demo.store import DemoStore


class ConfigTests(unittest.TestCase):
    def test_config_redacts_api_token(self):
        config = AppConfig(
            chatwoot_url="https://chat.example.com",
            account_id=7,
            inbox_id=11,
            api_token="secret-token",
            mode="real",
        )

        public = config.to_public_dict()

        self.assertEqual(public["chatwoot_url"], "https://chat.example.com")
        self.assertEqual(public["account_id"], 7)
        self.assertEqual(public["inbox_id"], 11)
        self.assertEqual(public["mode"], "real")
        self.assertEqual(public["api_token"], "configured")

    def test_invalid_mode_is_rejected(self):
        with self.assertRaises(ValueError):
            AppConfig(mode="bad")


class StoreTests(unittest.TestCase):
    def test_contact_and_messages_can_be_searched(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            store = DemoStore(os.path.join(tmpdir, "demo.db"))
            contact_id = store.upsert_contact(
                phone="+15551234567",
                name="Maria Gomez",
                source_id="whatsapp:+15551234567",
                external_id="42",
            )
            store.record_message(
                conversation_id="9001",
                contact_id=contact_id,
                direction="incoming",
                message_type="text",
                content="Need Shanghai freight quote",
                payload={"event": "message_created"},
            )
            store.record_message(
                conversation_id="9001",
                contact_id=contact_id,
                direction="outgoing",
                message_type="text",
                content="Quote template sent",
                payload={"id": 100},
            )

            results = store.search_messages("freight")

            self.assertEqual(len(results), 1)
            self.assertEqual(results[0]["contact_name"], "Maria Gomez")
            self.assertEqual(results[0]["phone"], "+15551234567")
            self.assertEqual(results[0]["content"], "Need Shanghai freight quote")

    def test_webhook_events_are_recorded_with_raw_payload(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            store = DemoStore(os.path.join(tmpdir, "demo.db"))

            event_id = store.record_webhook(
                event_name="message_created",
                payload={"message_type": "incoming", "content": "hello"},
            )
            events = store.list_webhook_events(limit=5)

            self.assertEqual(event_id, events[0]["id"])
            self.assertEqual(events[0]["event_name"], "message_created")
            self.assertIn('"hello"', events[0]["payload_json"])


if __name__ == "__main__":
    unittest.main()
