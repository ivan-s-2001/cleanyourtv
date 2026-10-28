"""Security and Android-14 contracts for the personal shell setup, without a real Binder device."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'launcher/app/src/main/java/com/gathin/tvhome'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class PersonalFeatureSetupTest(unittest.TestCase):
    def test_call_checks_caller_before_reading_extras_or_preferences(self):
        source = (JAVA / 'ThemeProvider.java').read_text()
        auth = source.index('context.enforceCallingPermission("android.permission.WRITE_SECURE_SETTINGS"')
        self.assertLess(auth, source.index('extras.keySet()'))
        self.assertLess(auth, source.index('context.getSharedPreferences('))
        self.assertIn('!allowed.contains(key) || !(extras.get(key) instanceof Boolean)', source)

    def test_provider_still_has_manifest_permission_and_no_setup_broadcast_receiver(self):
        manifest = ET.parse(ROOT / 'launcher/app/src/main/AndroidManifest.xml').getroot()
        provider = next(p for p in manifest.findall('./application/provider')
                        if p.get(ANDROID + 'name') == '.ThemeProvider')
        self.assertEqual('android.permission.WRITE_SECURE_SETTINGS', provider.get(ANDROID + 'permission'))
        self.assertFalse(any('configure_features' in ET.tostring(r, encoding='unicode')
                             for r in manifest.findall('./application/receiver')))

    def test_setup_only_accepts_explicit_feature_flags_and_rejects_unsupported_cleanup(self):
        source = (JAVA / 'ThemeProvider.java').read_text()
        self.assertIn('String[] keys = {WatchShelf.KEY, ShelfImages.NETWORK_KEY,', source)
        self.assertIn('AppAutomationService.VPN_KEY, AppAutomationService.CLEANUP_KEY}', source)
        self.assertIn('context.checkSelfPermission(WatchRepository.PERMISSION)', source)
        self.assertIn('!HappControl.supported(context)', source)
        self.assertIn('android.os.Build.VERSION.SDK_INT >= 34', source)

    def test_windows_setup_backs_up_before_permissions_and_preserves_other_helpers(self):
        source = (ROOT / 'launcher/enable-features.ps1').read_text()
        backup = source.index("'feature-flags.txt'")
        self.assertLess(backup, source.index('Tv "pm grant'))
        self.assertLess(backup, source.index('Tv "cmd appops set'))
        self.assertIn('$old -split', source)
        self.assertIn('$services += $short', source)
        self.assertIn("$cleanup = if ($sdk -lt 34) { 'true' } else { 'false' }", source)
        self.assertIn("'service_bound=true'", source)
        self.assertNotIn('pm uninstall', source)
        self.assertNotIn('am force-stop', source)
        self.assertNotIn('disable-user', source)

    def test_packaged_languages_match_the_personal_build_overlays(self):
        for build in ('debug', 'release'):
            config = ROOT / f'launcher/app/src/{build}/res/xml/locales_config.xml'
            offered = {p.get(ANDROID + 'name') for p in ET.parse(config).getroot()}
            self.assertEqual({'en', 'ru'}, offered)
        self.assertIn("resourceConfigurations += ['en', 'ru']", (ROOT / 'launcher/app/build.gradle').read_text())

    def test_jdk25_cannot_be_silently_used_by_windows_installer(self):
        source = (ROOT / 'launcher/build-and-install.ps1').read_text()
        self.assertIn('bin\\javac.exe', source)
        self.assertIn('bin\\jlink.exe', source)
        self.assertIn('JAVA_VERSION="17\\.', source)
        self.assertIn('"-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon', source)


if __name__ == '__main__':
    unittest.main()
