import os
import tempfile
import unittest

from app import DemoRuntime


class DemoRuntimeTests(unittest.TestCase):
    def test_save_config_returns_redacted_public_config(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            runtime = DemoRuntime(root_dir=tmpdir)

            public = runtime.save_config(
                {
                    "mode": "real",
                    "chatwoot_url": "https://chat.example.com",
                    "account_id": 7,
                    "inbox_id": 11,
                    "api_token": "token-123",
                    "webhook_secret": "hook-secret",
                }
            )

            self.assertEqual(public["mode"], "real")
            self.assertEqual(public["api_token"], "configured")
            self.assertEqual(public["webhook_secret"], "configured")
            self.assertNotIn("token-123", str(public))

    def test_mock_text_send_creates_contact_conversation_message_and_local_record(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            runtime = DemoRuntime(root_dir=tmpdir)

            result = runtime.send_text(
                {"phone": "+15551234567", "name": "Maria Gomez", "content": "Hello from logistics CRM"}
            )
            messages = runtime.store.search_messages("logistics")

            self.assertEqual(result["contact"]["phone_number"], "+15551234567")
            self.assertEqual(result["message"]["message_type"], "outgoing")
            self.assertEqual(len(messages), 1)
            self.assertEqual(messages[0]["direction"], "outgoing")
            self.assertEqual(messages[0]["contact_name"], "Maria Gomez")

    def test_template_send_records_template_name_for_search(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            runtime = DemoRuntime(root_dir=tmpdir)

            runtime.send_template(
                {
                    "phone": "+15557654321",
                    "name": "Ahmed Noor",
                    "template_name": "lead_followup",
                    "language": "en_US",
                    "parameters": ["YUEWEI", "Dubai"],
                }
            )
            messages = runtime.store.search_messages("lead_followup")

            self.assertEqual(len(messages), 1)
            self.assertEqual(messages[0]["message_type"], "template")
            self.assertEqual(messages[0]["direction"], "outgoing")

    def test_chatwoot_webhook_records_event_and_incoming_message(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            runtime = DemoRuntime(root_dir=tmpdir)

            result = runtime.ingest_webhook(
                {
                    "event": "message_created",
                    "message_type": "incoming",
                    "content": "Need air freight rate",
                    "conversation": {"id": 7001},
                    "sender": {
                        "id": 42,
                        "name": "Maria Gomez",
                        "phone_number": "+15551234567",
                    },
                }
            )
            messages = runtime.store.search_messages("air freight")
            events = runtime.store.list_webhook_events()

            self.assertEqual(result["event_name"], "message_created")
            self.assertEqual(len(events), 1)
            self.assertEqual(len(messages), 1)
            self.assertEqual(messages[0]["direction"], "incoming")
            self.assertEqual(messages[0]["conversation_id"], "7001")


if __name__ == "__main__":
    unittest.main()