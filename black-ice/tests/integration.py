from pathlib import Path
import xml.etree.ElementTree as ET
r=Path(__file__).resolve().parents[2];m=r/'platforms/android/app/src/main'
a='{http://schemas.android.com/apk/res/android}'
doc=ET.parse(m/'AndroidManifest.xml')
launchers=[x.get(a+'name') for x in doc.findall('.//activity') if any(c.get(a+'name')=='android.intent.category.LAUNCHER' for c in x.findall('.//category'))]
assert launchers==['dev.aether.preview.MainActivity'],launchers
core=next(x for x in doc.findall('.//activity') if x.get(a+'name')=='.Main')
assert core.get(a+'process')==':emulation'
assert not any(x.get(a+'name')=='android.permission.PACKAGE_USAGE_STATS' for x in doc.findall('.//uses-permission'))
p=next(x for x in doc.findall('.//provider') if x.get(a+'name')=='dev.aether.preview.SessionProvider')
assert p.get(a+'exported')=='false'
s=(m/'java/dev/aether/preview/MainActivity.java').read_text()
assert 'new Intent(this,com.armsx2.Main.class)' in s
assert 'xyz.aethersx2.android' not in s and 'getLaunchIntentForPackage' not in s
s=(m/'java/com/armsx2/runtime/MainActivityRuntime.kt').read_text()
assert 'blackIceSession("checkpoint")' in s and 'blackIceForeground = false' in s
assert 'instance?.finishAndRemoveTask()' not in s
p=(r/'platforms/android/gradle.properties').read_text()
assert 'armsx2.versionName=1.0.8' in p and 'armsx2.versionCode=10008' in p
onboarding = (m/'java/com/armsx2/ui/onboarding/OnboardingViewModel.kt').read_text()
assert 'returnToBlackIce' in onboarding and 'MainActivityRuntime.instance?.finish()' in onboarding
screen = (m/'java/com/armsx2/ui/onboarding/OnboardingScreen.kt').read_text()
assert 'biosOnly || state.page == setupStepKeys.lastIndex' in screen
assert 'biosOnly -> "Save BIOS"' in screen
print('PASS: single Black Ice entry point, included-core intent, isolated core process, private VM tracking endpoint, safe task return, BIOS setup return, and update identity.')
