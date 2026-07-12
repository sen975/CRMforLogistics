import os
import tempfile
import unittest

from wa_demo.client import WhatsAppCloudClient
from wa_demo.config import AppConfig
from wa_demo.store import MessageStore
from wa_demo.webhook import extract_webhook_messages, verify_challenge


class ConfigTests(unittest.TestCase):
    def test_config_redacts_access_token(self):
        config = AppConfig(
            mode="real",
            graph_version="v20.0",
            phone_number_id="123456",
            business_account_id="789",
            access_token="secret-token",
            verify_token="local-verify",
        )

        public = config.to_public_dict()

        self.assertEqual(public["mode"], "real")
        self.assertEqual(public["phone_number_id"], "123456")
        self.assertEqual(public["access_token"], "configured")
        self.assertEqual(public["verify_token"], "configured")
        self.assertNotIn("secret-token", str(public))


class PayloadTests(unittest.TestCase):
    def test_text_payload_matches_whatsapp_cloud_api_shape(self):
        client = WhatsAppCloudClient(AppConfig(phone_number_id="111", access_token="token"))

        payload = client.build_text_payload(to="15551234567", body="Hello")

        self.assertEqual(payload["messaging_product"], "whatsapp")
        self.assertEqual(payload["to"], "15551234567")
        self.assertEqual(payload["type"], "text")
        self.assertEqual(payload["text"], {"preview_url": False, "body": "Hello"})

    def test_template_payload_uses_components_for_body_parameters(self):
        client = WhatsAppCloudClient(AppConfig(phone_number_id="111", access_token="token"))

        payload = client.build_template_payload(
            to="15551234567",
            template_name="lead_followup",
            language="en_US",
            body_parameters=["YUEWEI", "Shanghai"],
        )

        self.assertEqual(payload["type"], "template")
        self.assertEqual(payload["template"]["name"], "lead_followup")
        self.assertEqual(payload["template"]["language"]["code"], "en_US")
        self.assertEqual(payload["template"]["components"][0]["type"], "body")
        self.assertEqual(payload["template"]["components"][0]["parameters"][0], {"type": "text", "text": "YUEWEI"})

    def test_media_payload_can_use_link(self):
        client = WhatsAppCloudClient(AppConfig(phone_number_id="111", access_token="token"))

        payload = client.build_media_payload(
            to="15551234567",
            media_type="image",
            link="https://example.com/rate.png",
            caption="rate sheet",
        )

        self.assertEqual(payload["type"], "image")
        self.assertEqual(payload["image"], {"link": "https://example.com/rate.png", "caption": "rate sheet"})


class WebhookTests(unittest.TestCase):
    def test_verify_challenge_returns_challenge_when_token_matches(self):
        result = verify_challenge(
            query={"hub.mode": ["subscribe"], "hub.verify_token": ["expected"], "hub.challenge": ["12345"]},
            verify_token="expected",
        )

        self.assertEqual(result, "12345")

    def test_webhook_message_extraction_handles_incoming_text(self):
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
                            }
                        }
                    ]
                }
            ]
        }

        messages = extract_webhook_messages(payload)

        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0]["wa_id"], "15551234567")
        self.assertEqual(messages[0]["profile_name"], "Maria")
        self.assertEqual(messages[0]["content"], "Need quote")
        self.assertEqual(messages[0]["message_type"], "text")


class StoreTests(unittest.TestCase):
    def test_store_records_incoming_and_outgoing_messages(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            store = MessageStore(os.path.join(tmpdir, "wa.db"))
            contact_id = store.upsert_contact(wa_id="15551234567", phone="15551234567", name="Maria")
            store.record_message(
                wa_message_id="wamid.1",
                contact_id=contact_id,
                direction="incoming",
                message_type="text",
                content="Need quote",
                payload={"id": "wamid.1"},
            )
            store.record_message(
                wa_message_id="local.1",
                contact_id=contact_id,
                direction="outgoing",
                message_type="text",
                content="Quote sent",
                payload={"dry_run": True},
            )

            results = store.search_messages("quote")

            self.assertEqual(len(results), 2)
            self.assertEqual(results[0]["direction"], "outgoing")
            self.assertEqual(results[1]["direction"], "incoming")


if __name__ == "__main__":
    unittest.main()