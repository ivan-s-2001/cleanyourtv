"""The bundled Happ profile must default to direct traffic and proxy only YouTube."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class YoutubeRoutingTest(unittest.TestCase):
    def test_only_youtube_has_a_proxy_rule(self):
        profile = json.loads((ROOT / "launcher/app/src/main/res/raw/happ_youtube_only.json").read_text())
        self.assertEqual(profile["GlobalProxy"], "false")
        self.assertEqual(profile["ProxySites"], ["geosite:youtube"])
        self.assertEqual(profile["ProxyIp"], [])
        self.assertEqual(profile["BlockSites"], [])
        self.assertEqual(profile["BlockIp"], [])
        self.assertNotIn("0.0.0.0/0", profile["DirectIp"])
        self.assertNotIn("::/0", profile["DirectIp"])
        self.assertEqual(profile["DomainStrategy"], "AsIs")


if __name__ == "__main__":
    unittest.main()
