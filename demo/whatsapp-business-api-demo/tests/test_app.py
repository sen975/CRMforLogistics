import tempfile
import unittest

from app import DemoRuntime


class DemoRuntimeTests(unittest.TestCase):
    def test_dry_run_send_text_records_outgoing_message(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            runtime = DemoRuntime(root_dir=tmpdir)
            runtime.save_config({"mode": "dry_run", "phone_number_id": "111", "access_token": "token"})

            result = runtime.send_text({"to": "+15551234567", "body": "Hello direct API"})
            messages = runtime.store.search_messages("direct")

            self.assertTrue(result["response"]["dry_run"])
            self.assertEqual(messages[0]["direction"], "outgoing")
            self.assertEqual(messages[0]["message_type"], "text")

    def test_webhook_ingest_records_incoming_messages_and_statuses(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            runtime = DemoRuntime(root_dir=tmpdir)
            payload = {
                "entry": [
                    {
                        "changes": [
                            {
                                "value": {
                                    "contacts": [{"wa_id": "15551234567", "profile": {"name": "Maria"}}],
                                    "messages": [
                                        {
                                            "id": "wamid.1",
                                            "from": "15551234567",
                                            "timestamp": "1783393125",
                                            "type": "text",
                                            "text": {"body": "Need quote"},
                                        }
                                    ],
                                    "statuses": [
                                        {"id": "wamid.2", "recipient_id": "15551234567", "status": "sent"}
                                    ],
                                }
                            }
                        ]
                    }
                ]
            }

            result = runtime.ingest_webhook(payload)

            self.assertEqual(result["messages"], 1)
            self.assertEqual(result["statuses"], 1)
            self.assertEqual(runtime.store.search_messages("Need quote")[0]["direction"], "incoming")
            self.assertEqual(runtime.store.list_statuses()[0]["status"], "sent")


if __name__ == "__main__":
    unittest.main()