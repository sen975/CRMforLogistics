import json
import unittest

from chatwoot_demo.client import ChatwootApiClient, MockChatwootClient
from chatwoot_demo.config import AppConfig


class MockChatwootClientTests(unittest.TestCase):
    def test_mock_flow_creates_contact_conversation_and_text_message(self):
        client = MockChatwootClient()

        contact = client.create_contact("+15551234567", "Maria Gomez")
        conversation = client.create_conversation(contact["id"], "+15551234567")
        message = client.send_text(conversation["id"], "Hello from CRM")

        self.assertEqual(contact["phone_number"], "+15551234567")
        self.assertEqual(conversation["contact_id"], contact["id"])
        self.assertEqual(message["message_type"], "outgoing")
        self.assertEqual(message["content"], "Hello from CRM")

    def test_mock_template_message_preserves_template_payload(self):
        client = MockChatwootClient()

        message = client.send_template(
            conversation_id=12,
            template_name="lead_followup",
            language="en_US",
            parameters=["YUEWEI", "Shanghai"],
        )

        self.assertEqual(message["template_params"]["name"], "lead_followup")
        self.assertEqual(message["template_params"]["language"], "en_US")
        self.assertEqual(message["template_params"]["parameters"], ["YUEWEI", "Shanghai"])


class ChatwootApiClientTests(unittest.TestCase):
    def test_real_client_builds_authorized_json_request(self):
        config = AppConfig(
            chatwoot_url="https://chat.example.com/",
            account_id=7,
            inbox_id=11,
            api_token="token-123",
            mode="real",
        )
        captured = {}

        def fake_opener(req, timeout=0):
            captured["url"] = req.full_url
            captured["method"] = req.get_method()
            captured["headers"] = dict(req.header_items())
            captured["body"] = json.loads(req.data.decode("utf-8"))

            class Response:
                def __enter__(self):
                    return self

                def __exit__(self, exc_type, exc, tb):
                    return False

                def read(self):
                    return b'{"id": 99, "content": "created"}'

            return Response()

        client = ChatwootApiClient(config=config, opener=fake_opener)

        result = client.send_text(conversation_id=55, content="Hello")

        self.assertEqual(result["id"], 99)
        self.assertEqual(
            captured["url"],
            "https://chat.example.com/api/v1/accounts/7/conversations/55/messages",
        )
        self.assertEqual(captured["method"], "POST")
        headers = {key.lower(): value for key, value in captured["headers"].items()}
        self.assertEqual(headers["api-access-token"], "token-123")
        self.assertEqual(headers["content-type"], "application/json")
        self.assertEqual(captured["body"], {"content": "Hello", "message_type": "outgoing", "private": False})


if __name__ == "__main__":
    unittest.main()