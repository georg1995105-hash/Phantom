#!/usr/bin/env python3
"""Real MediaStore screenshot / saved theme checks on the existing Android device."""
import importlib.util, json, time
from pathlib import Path
spec = importlib.util.spec_from_file_location('svk_native', Path(__file__).with_name('verify-emoji-preview.py'))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)

def tap_text(text, contains=False):
    for attempt in range(8):
        root = v.xml()
        for n in root.iter('node'):
            label = n.get('text') or n.get('content-desc','')
            if label == text or contains and text in label:
                v.tap_rect(list(map(int,v.re.findall(r'\d+',n.get('bounds')))))
                return
        time.sleep(.5)
    raise AssertionError(f'Control {text!r} not found: {[n.get("text") for n in root.iter("node")]}')

v.start()
v.adb('shell','am','start','-n',v.pkg+'/helium314.keyboard.smart.clipboard.ScreenshotPermissionActivity')
tap_text('Включить')
# Real runtime permission prompt, when access was not already granted.
root = v.xml()
if any('Разрешить' == n.get('text') for n in root.iter('node')): tap_text('Разрешить')
v.start()
prefs = v.adb('exec-out','cat',f'/data/user_de/0/{v.pkg}/shared_prefs/{v.pkg}_preferences.xml').decode()
assert 'name="sv_new_screenshots" value="true"' in prefs, 'Opt-in permission not enabled'
before = v.db('screenshots-before.db')
print('Screenshot opt-in enabled through Android UI',flush=True)
# A real system screenshot, including MediaStore insertion and SystemUI notification.
v.adb('shell','input','keyevent','120')
for _ in range(18):
    after = v.db('screenshots-live.db')
    added = [r for r in after if r[0] not in {r[0] for r in before} and r[3]=='Скриншот' and r[4]]
    if added: break
    time.sleep(1)
assert added, 'Live MediaStore screenshot was not stored'
v.screen('15-screenshot-chip.png')
print('Live screenshot saved with own image copy',flush=True)
# Missed-notification regression: process is stopped while SystemUI saves another screenshot.
v.adb('shell','am','force-stop',v.pkg)
v.adb('shell','input','keyevent','3')
v.adb('shell','input','keyevent','120')
time.sleep(2)
v.start()
for _ in range(18):
    reopened = v.db('screenshots-reopened.db')
    caught = [r for r in reopened if r[0] not in {r[0] for r in after} and r[3]=='Скриншот' and r[4]]
    if caught: break
    time.sleep(1)
assert caught, 'Screenshot missed while process was stopped was not caught on restart'
assert all(any(vv[0]==r[0] and vv[2:]==r[2:] for vv in reopened) for r in before), 'Earlier clips lost'
v.broadcast(page='clipboard'); v.screen('16-screenshot-history.png'); v.broadcast(page='alpha')
print('Screenshot while process stopped caught on IME opening; previous items remain',flush=True)
# Choose the new theme with genuine UI actions and prove persistence after a restart.
v.broadcast(page='functions'); tap_text('Тема'); v.tap('sv_theme_sv_calm')
v.start()
prefs = v.adb('exec-out','cat',f'/data/user_de/0/{v.pkg}/shared_prefs/{v.pkg}_preferences.xml').decode()
assert 'sv_calm' in prefs
v.broadcast(type='Приве'); v.screen('14-theme-gboard.png')
assert v.geometry()['inputShown']
record = json.loads((v.args.out/'native-checks.json').read_text())
record.update({'new_screenshot_live':True, 'new_screenshot_after_process_restart':True,
    'old_clipboard_entries_preserved':len(before), 'screenshot_import_count':len(added)+len(caught),
    'theme_calm_persisted':True})
logs = v.adb('logcat','-d').decode(errors='replace')
(v.args.out/'final-android-logcat.txt').write_text(logs)
assert 'ANR in '+v.pkg not in logs
assert 'FATAL EXCEPTION' not in v.adb('logcat','-b','crash','-d').decode()
record['new_run_no_anr_or_java_crash'] = True
(v.args.out/'native-checks.json').write_text(json.dumps(record,ensure_ascii=False,indent=2))
print('New theme persisted; no ANR/FATAL in final test log',flush=True)
